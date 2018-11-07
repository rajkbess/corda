package net.corda.core.serialization.internal

import net.corda.core.contracts.Attachment

/**
 * Drop-in replacement for [AttachmentsClassLoaderBuilder] in the serialization module.
 * TODO - ChrisR is this still needed if the AttachmentBuilder is no longer coupled to the ServiceHub?
 */
@Suppress("UNUSED", "UNUSED_PARAMETER")
internal object AttachmentsClassLoaderBuilder {
    fun build(attachments: List<Attachment>): AttachmentsClassLoader = TODO("Not implemented")
}