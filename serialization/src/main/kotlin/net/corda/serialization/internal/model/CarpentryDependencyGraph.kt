package net.corda.serialization.internal.model

import java.io.NotSerializableException
import java.lang.reflect.Type

/**
 * As we have the complete graph of types requiring carpentry to hand, we can use it to sort those types in reverse-
 * dependency order, i.e. beginning with those types that have no dependencies on other types, then the types that
 * depended on those types, and so on. This saves us having to use the [CarpenterMetaSchema].
 */
class CarpentryDependencyGraph private constructor(private val typesRequiringCarpentry: Set<RemoteTypeInformation>) {

    companion object {
        fun carpentInOrder(
                carpenter: RemoteTypeCarpenter,
                cache: MutableMap<TypeIdentifier, Type>,
                typesRequiringCarpentry: Collection<RemoteTypeInformation>): Map<TypeIdentifier, Type> =
            CarpentryDependencyGraph(typesRequiringCarpentry.toSet()).carpentInOrder(carpenter, cache)
    }

    private val dependencies = mutableMapOf<RemoteTypeInformation, MutableSet<RemoteTypeInformation>>()

    private fun carpentInOrder(carpenter: RemoteTypeCarpenter, cache: MutableMap<TypeIdentifier, Type>): Map<TypeIdentifier, Type> {
        typesRequiringCarpentry.forEach { it.recordDependencies() }
        return topologicalSort(typesRequiringCarpentry).associate { information -> information.typeIdentifier to
            cache.computeIfAbsent(information.typeIdentifier) {
                carpenter.carpent(information)
            }
        }
    }

    private fun RemoteTypeInformation.recordDependencies() = when (this) {
        is RemoteTypeInformation.Composable -> recordComposableDependencies()
        is RemoteTypeInformation.AnInterface -> recordInterfaceDependencies()
        is RemoteTypeInformation.AnArray -> add(this, componentType)
        is RemoteTypeInformation.Parameterised -> typeParameters.dependedOnBy(this)
        else -> {}
    }

    private fun List<RemoteTypeInformation>.dependedOnBy(dependent: RemoteTypeInformation) = forEach { dependee ->
        add(dependent, dependee)
    }

    private fun Map<String, RemotePropertyInformation>.dependedOnBy(dependent: RemoteTypeInformation) = forEach { _, property ->
        add(dependent, property.type)
    }

    private fun RemoteTypeInformation.AnInterface.recordInterfaceDependencies() {
        properties.dependedOnBy(this)
        typeParameters.dependedOnBy(this)
        interfaces.dependedOnBy(this)
    }

    private fun RemoteTypeInformation.Composable.recordComposableDependencies() {
        properties.dependedOnBy(this)
        typeParameters.dependedOnBy(this)
        interfaces.dependedOnBy(this)
    }

    private fun add(dependent: RemoteTypeInformation, dependee: RemoteTypeInformation) {
        if (dependee in typesRequiringCarpentry)
            dependencies.compute(dependent) { _, dependees ->
                dependees?.apply { add(dependee) } ?: mutableSetOf(dependee)
            }
    }

    private fun topologicalSort(types: Set<RemoteTypeInformation>): Sequence<RemoteTypeInformation> {
        // Find the types which don't depend on any other types, and can be built immediately
        val noDependents = types - dependencies.keys
        val remaining = dependencies.keys.toSet()
        val toRemove = dependencies.asSequence().mapNotNull { (dependee, dependencies) ->
            dependencies.removeAll(noDependents)
            if (dependencies.isEmpty()) dependee else null
        }.toSet()

        if (toRemove.isEmpty() && dependencies.isNotEmpty()) {
            throw NotSerializableException(
                    "Cannot build dependencies for " +
                            dependencies.keys.map { it.typeIdentifier.prettyPrint(false) })
        }

        dependencies.keys.removeAll(toRemove)
        return noDependents.asSequence() + toRemove.asSequence() +
                if (dependencies.isEmpty()) emptySequence() else topologicalSort(remaining)
    }
}