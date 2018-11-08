package net.corda.core.serialization.internal

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.ContractAttachment
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.isUploaderTrusted
import net.corda.core.serialization.CordaSerializable
import java.io.IOException
import java.net.*

/**
 * A custom ClassLoader that knows how to load classes from a set of attachments. The attachments themselves only
 * need to provide JAR streams, and so could be fetched from a database, local disk, etc. Constructing an
 * AttachmentsClassLoader is somewhat expensive, as every attachment is scanned to ensure that there are no overlapping
 * file paths.
 */
class AttachmentsClassLoader(attachments: List<Attachment>, parent: ClassLoader = ClassLoader.getSystemClassLoader()) :
        URLClassLoader(attachments.map { it.toUrl() }.toTypedArray(), parent) {

    companion object {
        val excludeFromCheck = setOf("meta-inf/manifest.mf")
        private val ignore = URL.setURLStreamHandlerFactory(AttachmentURLStreamHandlerFactory)
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
            attachment.openAsJAR().use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break

                    // We already verified that paths are not strange/game playing when we inserted the attachment
                    // into the storage service. So we don't need to repeat it here.
                    //
                    // We forbid files that differ only in case, or path separator to avoid issues for Windows/Mac developers where the
                    // filesystem tries to be case insensitive. This may break developers who attempt to use ProGuard.
                    //
                    // Also convert to Unix path separators as all resource/class lookups will expect this.
                    // If 2 entries have the same CRC, it means the same file is present in both attachments, so that is ok. TODO - Mike, wdyt?
                    val path = entry.name.toLowerCase().replace('\\', '/')
                    if (path !in excludeFromCheck) {
                        if (path in classLoaderEntries) throw OverlappingAttachments(path)
                        classLoaderEntries.add(path)
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
internal object AttachmentsClassLoaderBuilder {
    private val cache = mutableMapOf<List<SecureHash>, AttachmentsClassLoader>()

    // TODO - find out if the right classloader is used as a parent
    fun build(attachments: List<Attachment>): AttachmentsClassLoader {
        return cache.computeIfAbsent(attachments.map { it.id }.sorted()) {
            AttachmentsClassLoader(attachments)
        }
    }
}

fun Attachment.toUrl(): URL {
    val id = this.id.toString()
    AttachmentURLStreamHandlerFactory.content[id] = this
    return URL("attachment", "", -1, id, AttachmentURLStreamHandler(AttachmentURLStreamHandlerFactory.content))
}

class AttachmentURLStreamHandler(val attachments: Map<String, Attachment>) : URLStreamHandler() {
    @Throws(IOException::class)
    override fun openConnection(u: URL): URLConnection {
        if (u.protocol != "attachment") {
            throw IOException("Cannot handle protocol: ${u.protocol}")
        }
        return object : URLConnection(u) {
            val attachmentId = u.path
            override fun getContentLengthLong() = attachments[attachmentId]!!.size.toLong() ?: 0

            @Throws(IOException::class)
            override fun getInputStream() = attachments[attachmentId]!!.open()

            @Throws(IOException::class)
            override fun connect() {
                connected = true
            }
        }
    }
}

object AttachmentURLStreamHandlerFactory : URLStreamHandlerFactory {
    val content = mutableMapOf<String, Attachment>()

    override fun createURLStreamHandler(protocol: String): URLStreamHandler? {
        return if ("attachment" == protocol) {
            AttachmentURLStreamHandler(content)
        } else null
    }
}
