package net.corda.serialization.internal.amqp

import com.google.common.primitives.Primitives
import net.corda.core.DeleteForDJVM
import net.corda.core.KeepForDJVM
import net.corda.core.StubOutForDJVM
import net.corda.core.internal.kotlinObjectInstance
import net.corda.core.internal.uncheckedCast
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.trace
import net.corda.serialization.internal.carpenter.*
import org.apache.qpid.proton.amqp.*
import java.io.NotSerializableException
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.annotation.concurrent.ThreadSafe

@KeepForDJVM
data class SerializationSchemas(val schema: Schema, val transforms: TransformsSchema)
@KeepForDJVM
data class FactorySchemaAndDescriptor(val schemas: SerializationSchemas, val typeDescriptor: Any)
@KeepForDJVM
data class CustomSerializersCacheKey(val clazz: Class<*>, val declaredType: Type)

/**
 * Represents a type which has been
 */
data class RemoteType(val type: Type, val typeNotation: TypeNotation, val localDescriptor: Symbol) {
    val remoteDescriptor get() = typeNotation.descriptor.name!!
    val name: String get() = typeNotation.name
}

/**
 * A cache which maintains information about types for which we have received schemas.
 */
interface RemoteTypeResolver {
    /**
     * For every [TypeNotation] in the provided list, obtain a suitable Java [Type].
     */
    fun resolveTypes(typeNotations: List<TypeNotation>): List<RemoteType>
}

class CacheingRemoteTypeResolver(
        private val cache: MutableMap<Symbol, RemoteType>,
        private val classCarpenter: ClassCarpenter,
        private val fingerPrinter: FingerPrinter,
        private val classLoader: ClassLoader
): RemoteTypeResolver {

    companion object {
        val logger = contextLogger()
    }

    override fun resolveTypes(typeNotations: List<TypeNotation>): List<RemoteType> {
        val resolvedTypes = mutableListOf<RemoteType>()
        val typesRequiringCarpentry = mutableListOf<TypeNotation>()

        // First pass (before carpentry)
        for (typeNotation in typeNotations) {
            try {
                resolvedTypes += cache.computeIfAbsent(typeNotation.descriptor.name!!) {
                    getRemoteType(typeNotation)
                }
            } catch (e: ClassNotFoundException) {
                logger.trace { "typeNotation=\"${typeNotation.name}\" action=\"carpentry required\"" }
                typesRequiringCarpentry += typeNotation
            }
        }

        synthesizeMissingTypes(typesRequiringCarpentry)

        // Second pass (after carpentry)
        for (typeNotation in typesRequiringCarpentry) {
            try {
                resolvedTypes += cache.computeIfAbsent(typeNotation.descriptor.name!!) {
                    getRemoteType(typeNotation)
                }
            } catch (e: ClassNotFoundException) {
                logger.error("typeNotation=${typeNotation.name} error=\"after Carpentry attempt failed to load\"")
                throw e
            }
        }

        return resolvedTypes
    }

    fun getRemoteType(typeNotation: TypeNotation): RemoteType {
        val type = typeForName(typeNotation.name)
        val localDescriptor = Symbol.valueOf("$DESCRIPTOR_DOMAIN:${fingerPrinter.fingerprint(type)}")
        return RemoteType(type, typeNotation, localDescriptor)
    }

    @StubOutForDJVM
    private fun synthesizeMissingTypes(typesRequiringCarpentry: MutableList<TypeNotation>) {
        val metaSchema = CarpenterMetaSchema.newInstance()
        for (typeNotation in typesRequiringCarpentry) {
            metaSchema.buildFor(typeNotation, classLoader)
        }
        val mc = MetaCarpenter(metaSchema, classCarpenter)
        try {
            mc.build()
        } catch (e: MetaCarpenterException) {
            // preserve the actual message locally
            loggerFor<SerializerFactory>().apply {
                error("${e.message} [hint: enable trace debugging for the stack trace]")
                trace("", e)
            }

            // prevent carpenter exceptions escaping into the world, convert things into a nice
            // NotSerializableException for when this escapes over the wire
            NotSerializableException(e.name)
        }
    }

    private fun typeForName(name: String): Type {
        return if (name.endsWith("[]")) {
            val elementType = typeForName(name.substring(0, name.lastIndex - 1))
            if (elementType is ParameterizedType || elementType is GenericArrayType) {
                DeserializedGenericArrayType(elementType)
            } else if (elementType is Class<*>) {
                java.lang.reflect.Array.newInstance(elementType, 0).javaClass
            } else {
                throw AMQPNoTypeNotSerializableException("Not able to deserialize array type: $name")
            }
        } else if (name.endsWith("[p]")) {
            // There is no need to handle the ByteArray case as that type is coercible automatically
            // to the binary type and is thus handled by the main serializer and doesn't need a
            // special case for a primitive array of bytes
            when (name) {
                "int[p]" -> IntArray::class.java
                "char[p]" -> CharArray::class.java
                "boolean[p]" -> BooleanArray::class.java
                "float[p]" -> FloatArray::class.java
                "double[p]" -> DoubleArray::class.java
                "short[p]" -> ShortArray::class.java
                "long[p]" -> LongArray::class.java
                else -> throw AMQPNoTypeNotSerializableException("Not able to deserialize array type: $name")
            }
        } else {
            DeserializedParameterizedType.make(name, classLoader)
        }
    }
}

/**
 * Factory of serializers designed to be shared across threads and invocations.
 *
 * @property evolutionSerializerGetter controls how evolution serializers are generated by the factory. The normal
 * use case is an [EvolutionSerializer] type is returned. However, in some scenarios, primarily testing, this
 * can be altered to fit the requirements of the test.
 * @property onlyCustomSerializers used for testing, when set will cause the factory to throw a
 * [NotSerializableException] if it cannot find a registered custom serializer for a given type
 */
// TODO: support for intern-ing of deserialized objects for some core types (e.g. PublicKey) for memory efficiency
// TODO: maybe support for caching of serialized form of some core types for performance
// TODO: profile for performance in general
// TODO: use guava caches etc so not unbounded
// TODO: allow definition of well known types that are left out of the schema.
// TODO: migrate some core types to unsigned integer descriptor
// TODO: document and alert to the fact that classes cannot default superclass/interface properties otherwise they are "erased" due to matching with constructor.
// TODO: type name prefixes for interfaces and abstract classes?  Or use label?
// TODO: generic types should define restricted type alias with source of the wildcarded version, I think, if we're to generate classes from schema
// TODO: need to rethink matching of constructor to properties in relation to implementing interfaces and needing those properties etc.
// TODO: need to support super classes as well as interfaces with our current code base... what's involved?  If we continue to ban, what is the impact?
@KeepForDJVM
@ThreadSafe
open class SerializerFactory(
        val whitelist: ClassWhitelist,
        val classCarpenter: ClassCarpenter,
        private val evolutionSerializerGetter: EvolutionSerializerGetterBase = EvolutionSerializerGetter(),
        val fingerPrinterConstructor: (SerializerFactory) -> FingerPrinter = ::SerializerFingerPrinter,
        private val serializersByType: MutableMap<Type, AMQPSerializer<Any>>,
        val serializersByDescriptor: MutableMap<Any, AMQPSerializer<Any>>,
        private val customSerializers: MutableList<SerializerFor>,
        private val customSerializersCache: MutableMap<CustomSerializersCacheKey, AMQPSerializer<Any>?>,
        val transformsCache: MutableMap<String, EnumMap<TransformTypes, MutableList<Transform>>>,
        private val onlyCustomSerializers: Boolean = false
) {
    @DeleteForDJVM
    constructor(whitelist: ClassWhitelist,
                classCarpenter: ClassCarpenter,
                evolutionSerializerGetter: EvolutionSerializerGetterBase = EvolutionSerializerGetter(),
                fingerPrinterConstructor: (SerializerFactory) -> FingerPrinter = ::SerializerFingerPrinter,
                onlyCustomSerializers: Boolean = false
    ) : this(
            whitelist,
            classCarpenter,
            evolutionSerializerGetter,
            fingerPrinterConstructor,
            ConcurrentHashMap(),
            ConcurrentHashMap(),
            CopyOnWriteArrayList(),
            ConcurrentHashMap(),
            ConcurrentHashMap(),
            onlyCustomSerializers
    )

    @DeleteForDJVM
    constructor(whitelist: ClassWhitelist,
                carpenterClassLoader: ClassLoader,
                lenientCarpenter: Boolean = false,
                evolutionSerializerGetter: EvolutionSerializerGetterBase = EvolutionSerializerGetter(),
                fingerPrinterConstructor: (SerializerFactory) -> FingerPrinter = ::SerializerFingerPrinter,
                onlyCustomSerializers: Boolean = false
    ) : this(
            whitelist,
            ClassCarpenterImpl(whitelist, carpenterClassLoader, lenientCarpenter),
            evolutionSerializerGetter,
            fingerPrinterConstructor,
            onlyCustomSerializers)

    val remoteTypeResolver by lazy {
        CacheingRemoteTypeResolver(
                ConcurrentHashMap(),
                classCarpenter,
                fingerPrinter,
                classloader)
    }

    val fingerPrinter by lazy { fingerPrinterConstructor(this) }

    val classloader: ClassLoader get() = classCarpenter.classloader

    private fun getEvolutionSerializer(remoteType: RemoteType,
                                       newSerializer: AMQPSerializer<Any>,
                                       schemas: SerializationSchemas): AMQPSerializer<Any> {
        return evolutionSerializerGetter.getEvolutionSerializer(this, remoteType.typeNotation, newSerializer, schemas)
    }

    /**
     * Look up, and manufacture if necessary, a serializer for the given type.
     *
     * @param actualClass Will be null if there isn't an actual object instance available (e.g. for
     * restricted type processing).
     */
    @Throws(NotSerializableException::class)
    fun get(actualClass: Class<*>?, declaredType: Type): AMQPSerializer<Any> {
        // can be useful to enable but will be *extremely* chatty if you do
        logger.trace { "Get Serializer for $actualClass ${declaredType.typeName}" }

        val declaredClass = declaredType.asClass()
        val actualType: Type = if (actualClass == null) declaredType
            else inferTypeVariables(actualClass, declaredClass, declaredType) ?: declaredType

        val serializer = when {
        // Declared class may not be set to Collection, but actual class could be a collection.
        // In this case use of CollectionSerializer is perfectly appropriate.
            (Collection::class.java.isAssignableFrom(declaredClass) ||
                    (actualClass != null && Collection::class.java.isAssignableFrom(actualClass))) &&
                    !EnumSet::class.java.isAssignableFrom(actualClass ?: declaredClass) -> {
                val declaredTypeAmended = CollectionSerializer.deriveParameterizedType(declaredType, declaredClass, actualClass)
                serializersByType.computeIfAbsent(declaredTypeAmended) {
                    CollectionSerializer(declaredTypeAmended, this)
                }
            }
        // Declared class may not be set to Map, but actual class could be a map.
        // In this case use of MapSerializer is perfectly appropriate.
            (Map::class.java.isAssignableFrom(declaredClass) ||
                    (actualClass != null && Map::class.java.isAssignableFrom(actualClass))) -> {
                val declaredTypeAmended = MapSerializer.deriveParameterizedType(declaredType, declaredClass, actualClass)
                serializersByType.computeIfAbsent(declaredTypeAmended) {
                    makeMapSerializer(declaredTypeAmended)
                }
            }
            Enum::class.java.isAssignableFrom(actualClass ?: declaredClass) -> {
                logger.trace {
                    "class=[${actualClass?.simpleName} | $declaredClass] is an enumeration " +
                    "declaredType=${declaredType.typeName} " +
                    "isEnum=${declaredType::class.java.isEnum}"
                }

                serializersByType.computeIfAbsent(actualClass ?: declaredClass) {
                    whitelist.requireWhitelisted(actualType)
                    EnumSerializer(actualType, actualClass ?: declaredClass, this)
                }
            }
            else -> {
                makeClassSerializer(actualClass ?: declaredClass, actualType, declaredType)
            }
        }

        serializersByDescriptor.putIfAbsent(serializer.typeDescriptor, serializer)

        return serializer
    }

    /**
     * Lookup and manufacture a serializer for the given AMQP type descriptor, assuming we also have the necessary types
     * contained in the [Schema].
     */
    @Throws(NotSerializableException::class)
    fun get(typeDescriptor: Any, schema: SerializationSchemas): AMQPSerializer<Any> {
        return serializersByDescriptor[typeDescriptor] ?: {
            logger.trace("get Serializer descriptor=${typeDescriptor}")
            processSchema(FactorySchemaAndDescriptor(schema, typeDescriptor))
            serializersByDescriptor[typeDescriptor] ?: throw NotSerializableException(
                    "Could not find type matching descriptor $typeDescriptor.")
        }()
    }

    /**
     * Register a custom serializer for any type that cannot be serialized or deserialized by the default serializer
     * that expects to find getters and a constructor with a parameter for each property.
     */
    open fun register(customSerializer: CustomSerializer<out Any>) {
        logger.trace("action=\"Registering custom serializer\", class=\"${customSerializer.type}\"")
        if (!serializersByDescriptor.containsKey(customSerializer.typeDescriptor)) {
            customSerializers += customSerializer
            serializersByDescriptor[customSerializer.typeDescriptor] = customSerializer
            for (additional in customSerializer.additionalSerializers) {
                register(additional)
            }
        }
    }

    fun registerExternal(customSerializer: CorDappCustomSerializer) {
        logger.trace("action=\"Registering external serializer\", class=\"${customSerializer.type}\"")
        if (!serializersByDescriptor.containsKey(customSerializer.typeDescriptor)) {
            customSerializers += customSerializer
            serializersByDescriptor[customSerializer.typeDescriptor] = customSerializer
        }
    }

    /**
     * Iterate over an AMQP schema, for each type ascertain whether it's on ClassPath of [classloader] and,
     * if not, use the [ClassCarpenter] to generate a class to use in its place.
     */
    private fun processSchema(schemaAndDescriptor: FactorySchemaAndDescriptor) {
        val remoteTypes = remoteTypeResolver.resolveTypes(schemaAndDescriptor.schemas.schema.types)
        for (remoteType in remoteTypes) {
            logger.trace { "descriptor=${schemaAndDescriptor.typeDescriptor}, typeNotation=${remoteType.name}" }
            val serialiser = processRemoteType(remoteType)

            // if we just successfully built a serializer for the type but the type fingerprint
            // doesn't match that of the serialised object then we may be dealing with  different
            // instance of the class, such that we would need to build an EvolutionSerializer
            if (remoteType.localDescriptor != remoteType.remoteDescriptor) {
                logger.trace { "typeNotation=${remoteType.name} action=\"may require Evolution\"" }
                val maybeEvolving = getEvolutionSerializer(remoteType, serialiser, schemaAndDescriptor.schemas)
                if (maybeEvolving !== serialiser) {
                    logger.info("typeNotation=${remoteType.name} action=\"required Evolution to ${serialiser.type.typeName}\"")
                }
            }
        }
    }

    private fun processRemoteType(remoteType: RemoteType) = when (remoteType.typeNotation) {
        // java.lang.Class (whether a class or interface)
        is CompositeType -> {
            logger.trace("typeNotation=${remoteType.typeNotation.name} amqpType=CompositeType")
            get(remoteType.type.asClass(), remoteType.type)
        }
        // Collection / Map, possibly with generics
        is RestrictedType -> {
            logger.trace("typeNotation=${remoteType.typeNotation.name} amqpType=RestrictedType")
            get(null, remoteType.type)
        }
    }

    private fun makeClassSerializer(
            clazz: Class<*>,
            type: Type,
            declaredType: Type
    ): AMQPSerializer<Any> = serializersByType.computeIfAbsent(type) {
        logger.debug { "class=${clazz.simpleName}, type=$type is a composite type" }
        if (clazz.isSynthetic) {
            // Explicitly ban synthetic classes, we have no way of recreating them when deserializing. This also
            // captures Lambda expressions and other anonymous functions
            throw AMQPNotSerializableException(
                    type,
                    "Serializer does not support synthetic classes")
        } else if (isPrimitive(clazz)) {
            AMQPPrimitiveSerializer(clazz)
        } else {
            findCustomSerializer(clazz, declaredType) ?: run {
                if (onlyCustomSerializers) {
                    throw AMQPNotSerializableException(type, "Only allowing custom serializers")
                }
                if (type.isArray()) {
                    // Don't need to check the whitelist since each element will come back through the whitelisting process.
                    if (clazz.componentType.isPrimitive) PrimArraySerializer.make(type, this)
                    else ArraySerializer.make(type, this)
                } else {
                    val singleton = clazz.kotlinObjectInstance
                    if (singleton != null) {
                        whitelist.requireWhitelisted(clazz)
                        SingletonSerializer(clazz, singleton, this)
                    } else {
                        whitelist.requireWhitelisted(type)
                        ObjectSerializer.forType(type, this)
                    }
                }
            }
        }
    }

    internal fun findCustomSerializer(clazz: Class<*>, declaredType: Type): AMQPSerializer<Any>? {
        return customSerializersCache.computeIfAbsent(CustomSerializersCacheKey(clazz, declaredType), ::doFindCustomSerializer)
    }

    private fun doFindCustomSerializer(key: CustomSerializersCacheKey): AMQPSerializer<Any>? {
        val (clazz, declaredType) = key

        // e.g. Imagine if we provided a Map serializer this way, then it won't work if the declared type is
        // AbstractMap, only Map. Otherwise it needs to inject additional schema for a RestrictedType source of the
        // super type.  Could be done, but do we need it?
        for (customSerializer in customSerializers) {
            if (customSerializer.isSerializerFor(clazz)) {
                val declaredSuperClass = declaredType.asClass().superclass


                return if (declaredSuperClass == null
                        || !customSerializer.isSerializerFor(declaredSuperClass)
                        || !customSerializer.revealSubclassesInSchema
                ) {
                    logger.debug("action=\"Using custom serializer\", class=${clazz.typeName}, " +
                            "declaredType=${declaredType.typeName}")

                    @Suppress("UNCHECKED_CAST")
                    customSerializer as? AMQPSerializer<Any>
                } else {
                    // Make a subclass serializer for the subclass and return that...
                    CustomSerializer.SubClass(clazz, uncheckedCast(customSerializer))
                }
            }
        }
        return null
    }

    private fun makeMapSerializer(declaredType: ParameterizedType): AMQPSerializer<Any> {
        val rawType = declaredType.rawType as Class<*>
        rawType.checkSupportedMapType()
        return MapSerializer(declaredType, this)
    }

    companion object {
        private val logger = contextLogger()

        fun isPrimitive(type: Type): Boolean = primitiveTypeName(type) != null

        fun primitiveTypeName(type: Type): String? {
            val clazz = type as? Class<*> ?: return null
            return primitiveTypeNames[Primitives.unwrap(clazz)]
        }

        fun primitiveType(type: String): Class<*>? {
            return namesOfPrimitiveTypes[type]
        }

        private val primitiveTypeNames: Map<Class<*>, String> = mapOf(
                Character::class.java to "char",
                Char::class.java to "char",
                Boolean::class.java to "boolean",
                Byte::class.java to "byte",
                UnsignedByte::class.java to "ubyte",
                Short::class.java to "short",
                UnsignedShort::class.java to "ushort",
                Int::class.java to "int",
                UnsignedInteger::class.java to "uint",
                Long::class.java to "long",
                UnsignedLong::class.java to "ulong",
                Float::class.java to "float",
                Double::class.java to "double",
                Decimal32::class.java to "decimal32",
                Decimal64::class.java to "decimal62",
                Decimal128::class.java to "decimal128",
                Date::class.java to "timestamp",
                UUID::class.java to "uuid",
                ByteArray::class.java to "binary",
                String::class.java to "string",
                Symbol::class.java to "symbol")

        private val namesOfPrimitiveTypes: Map<String, Class<*>> = primitiveTypeNames.map { it.value to it.key }.toMap()

        fun nameForType(type: Type): String = when (type) {
            is Class<*> -> {
                primitiveTypeName(type) ?: if (type.isArray) {
                    "${nameForType(type.componentType)}${if (type.componentType.isPrimitive) "[p]" else "[]"}"
                } else type.name
            }
            is ParameterizedType -> {
                "${nameForType(type.rawType)}<${type.actualTypeArguments.joinToString { nameForType(it) }}>"
            }
            is GenericArrayType -> "${nameForType(type.genericComponentType)}[]"
            is WildcardType -> "?"
            is TypeVariable<*> -> "?"
            else -> throw AMQPNotSerializableException(type, "Unable to render type $type to a string.")
        }
    }

    object AnyType : WildcardType {
        override fun getUpperBounds(): Array<Type> = arrayOf(Object::class.java)

        override fun getLowerBounds(): Array<Type> = emptyArray()

        override fun toString(): String = "?"
    }
}
