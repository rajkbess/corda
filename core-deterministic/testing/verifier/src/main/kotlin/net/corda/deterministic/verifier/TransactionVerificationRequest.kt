package net.corda.deterministic.verifier

import net.corda.core.contracts.*
import net.corda.core.internal.DEPLOYED_CORDAPP_UPLOADER
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.WireTransaction

@Suppress("MemberVisibilityCanBePrivate")
@CordaSerializable
class TransactionVerificationRequest(val wtxToVerify: SerializedBytes<WireTransaction>,
                                     val dependencies: Array<SerializedBytes<WireTransaction>>,
                                     val attachments: Array<ByteArray>) {
    fun toLedgerTransaction(): LedgerTransaction {
        val deps = dependencies.map { it.deserialize() }.associateBy(WireTransaction::id)
        val attachments = attachments.map { it.deserialize<Attachment>() }
        val attachmentMap = attachments
                .mapNotNull { it as? MockContractAttachment }
                .associateBy(Attachment::id) { ContractAttachment(it, it.contract, uploader = DEPLOYED_CORDAPP_UPLOADER) }
        val contractAttachmentMap = emptyMap<ContractClassName, ContractAttachment>()
        // TODO figure out what is going on here
        val wtx = wtxToVerify.deserialize()
        @Suppress("DEPRECATION")
        // TODO it's hack just so compiles
        return LedgerTransaction(emptyList(), emptyList<TransactionState<ContractState>>(), emptyList(), emptyList(), wtx.id, wtx.notary, wtx.timeWindow, wtx.privacySalt, null, emptyList<StateAndRef<ContractState>>())
//        return wtxToVerify.deserialize().toLedgerTransaction(
//            resolveIdentity = { null },
//            resolveAttachment = { attachmentMap[it] },
//            resolveStateRef = { deps[it.txhash]?.outputs?.get(it.index) },
//            resolveContractAttachment = { contractAttachmentMap[it.contract]?.id }
//        )
    }
}
