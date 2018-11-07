package net.corda.core.serialization.internal

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.core.DeleteForDJVM
import net.corda.core.KeepForDJVM
import net.corda.core.contracts.Attachment
import net.corda.core.contracts.ContractAttachment
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.isUploaderTrusted
import net.corda.core.serialization.CordaSerializable
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * A custom ClassLoader that knows how to load classes from a set of attachments. The attachments themselves only
 * need to provide JAR streams, and so could be fetched from a database, local disk, etc. Constructing an
 * AttachmentsClassLoader is somewhat expensive, as every attachment is scanned to ensure that there are no overlapping
 * file paths.
 */
@KeepForDJVM
class AttachmentsClassLoader(attachments: List<Attachment>, parent: ClassLoader = ClassLoader.getSystemClassLoader()) : URLClassLoader(attachments.map { attch ->
    val tempFile = File.createTempFile("${attch.id}", ".jar")
    attch.open().copyTo(tempFile.outputStream())
    tempFile.deleteOnExit()
    tempFile.toURI().toURL()
}.toTypedArray(), parent) {

    companion object {
        val excludeFromCheck = setOf("meta-inf/manifest.mf")
    }

    @CordaSerializable
    class OverlappingAttachments(val path: String) : Exception() {
        override fun toString() = "Multiple attachments define a file at path $path"
    }

    init {
        require(attachments.mapNotNull { it as? ContractAttachment }.all { isUploaderTrusted(it.uploader) }) {
            "Attempting to load Contract Attachments downloaded from the network"
        }

        val classLoaderEntries = mutableSetOf<String>()
        for (attachment in attachments) {
            File.createTempFile("${attachment.id}", ".jar").let { tempFile ->
                attachment.open().copyTo(tempFile.outputStream())
                JarFile(tempFile).use { jarFile ->
                    jarFile.entries().iterator().forEach { nextJarEntry ->
                        // We already verified that paths are not strange/game playing when we inserted the attachment
                        // into the storage service. So we don't need to repeat it here.
                        //
                        // We forbid files that differ only in case, or path separator to avoid issues for Windows/Mac developers where the
                        // filesystem tries to be case insensitive. This may break developers who attempt to use ProGuard.
                        //
                        // Also convert to Unix path separators as all resource/class lookups will expect this.

                        // If 2 entries have the same CRC, it means the same file is present in both attachments, so that is ok. TODO - Mike, wdyt? Should we implement this?
                        val path = nextJarEntry.name.toLowerCase().replace('\\', '/')
                        if (path !in excludeFromCheck) {
                            if (path in classLoaderEntries) throw OverlappingAttachments(path)
                            classLoaderEntries.add(path)
                        }
                    }
                }
            }
        }
    }
}

/*
 * This class is internal rather than private so that serialization-deterministic
 * can replace it with an alternative version.
 * TODO: is @DeleteForDJVM still necessary, as there ?
 */
@DeleteForDJVM
internal object AttachmentsClassLoaderBuilder {
    private val cache: Cache<List<SecureHash>, AttachmentsClassLoader> = Caffeine.newBuilder().weakValues().maximumSize(1024).build()

    // TODO - find out if the right classloader is used as a parent
    fun build(attachments: List<Attachment>): AttachmentsClassLoader {
        return cache.get(attachments.map { it.id }.sorted()) {
            AttachmentsClassLoader(attachments)
        }!!
    }
}


