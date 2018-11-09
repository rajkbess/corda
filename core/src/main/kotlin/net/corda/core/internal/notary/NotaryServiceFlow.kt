package net.corda.core.internal.notary

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TimeWindow
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap

/**
 * A flow run by a notary service that handles notarisation requests.
 *
 * It checks that the time-window command is valid (if present) and commits the input state, or returns a conflict
 * if any of the input states have been previously committed.
 *
 * Additional transaction validation logic can be added when implementing [validateRequest].
 */
// See AbstractStateReplacementFlow.Acceptor for why it's Void?
abstract class NotaryServiceFlow(val otherSideSession: FlowSession, val service: TrustedAuthorityNotaryService) : FlowLogic<Void?>() {
    companion object {
        // TODO: Determine an appropriate limit and also enforce in the network parameters and the transaction builder.
        private const val maxAllowedInputsAndReferences = 10_000
    }

    @Suspendable
    override fun call(): Void? {
        check(serviceHub.myInfo.legalIdentities.any { serviceHub.networkMapCache.isNotary(it) }) {
            "We are not a notary on the network"
        }
        val requestPayload = otherSideSession.receive<NotarisationPayload>().unwrap { it }
        var txId: SecureHash? = null
        try {
            val parts = validateRequest(requestPayload)
            txId = parts.id
            checkNotary(parts.notary)
            checkParametersHash(parts.networkParametersHash)
            // TODO should it commit all parts?
            service.commitInputStates(parts.inputs, txId, otherSideSession.counterparty, requestPayload.requestSignature, parts.timestamp, parts.references)
            signTransactionAndSendResponse(txId)
        } catch (e: NotaryInternalException) {
            throw NotaryException(e.error, txId)
        }
        return null
    }

    /** Checks whether the number of input states is too large. */
    protected fun checkInputs(inputs: List<StateRef>) {
        if (inputs.size > maxAllowedInputsAndReferences) {
            val error = NotaryError.TransactionInvalid(
                    IllegalArgumentException("A transaction cannot have more than $maxAllowedInputsAndReferences " +
                            "inputs or references, received: ${inputs.size}")
            )
            throw NotaryInternalException(error)
        }
    }

    /**
     * Implement custom logic to perform transaction verification based on validity and privacy requirements.
     */
    @Suspendable
    protected abstract fun validateRequest(requestPayload: NotarisationPayload): TransactionParts

    /**
     * Check that network parameters hash on this transaction is the current hash for the network.
     */
     // TODO Implement network parameters fuzzy checking. By design in Corda network we have propagation time delay.
     //     We will never end up in perfect synchronization with all the nodes. However, network parameters update process
     //     lets us predict what is the reasonable time window for changing parameters on most of the nodes.
    @Suspendable
    protected fun checkParametersHash(networkParametersHash: SecureHash?) {
        if (networkParametersHash == null && serviceHub.networkParameters.minimumPlatformVersion < 4) return
        val notaryParametersHash = serviceHub.networkParametersStorage.currentParametersHash
        if (notaryParametersHash != networkParametersHash) {
            throw NotaryInternalException(NotaryError.ParametersMismatch(networkParametersHash, notaryParametersHash))
        }
    }

    /** Verifies that the correct notarisation request was signed by the counterparty. */
    protected fun validateRequestSignature(request: NotarisationRequest, signature: NotarisationRequestSignature) {
        val requestingParty = otherSideSession.counterparty
        request.verifySignature(signature, requestingParty)
    }

    /** Check if transaction is intended to be signed by this notary. */
    @Suspendable
    protected fun checkNotary(notary: Party?) {
        if (notary?.owningKey != service.notaryIdentityKey) {
            throw NotaryInternalException(NotaryError.WrongNotary)
        }
    }

    @Suspendable
    protected fun checkParametersPresent(networkParametersHash: SecureHash?) {
        if (serviceHub.networkParameters.minimumPlatformVersion >= 4 && networkParametersHash == null) {
            throw NotaryInternalException(NotaryError.TransactionInvalid(
                    IllegalArgumentException("Notary received transaction without network parameters hash")
            ))
        }
    }

    @Suspendable
    private fun signTransactionAndSendResponse(txId: SecureHash) {
        val signature = service.sign(txId)
        otherSideSession.send(NotarisationResponse(listOf(signature)))
    }

    /**
     * The minimum amount of information needed to notarise a transaction. Note that this does not include
     * any sensitive transaction details.
     */
    protected data class TransactionParts @JvmOverloads constructor(
            val id: SecureHash,
            val inputs: List<StateRef>,
            val timestamp: TimeWindow?,
            val notary: Party?,
            val references: List<StateRef> = emptyList(),
            val networkParametersHash: SecureHash?
    ) {
        fun copy(id: SecureHash, inputs: List<StateRef>, timestamp: TimeWindow?, notary: Party?, networkParametersHash: SecureHash?): TransactionParts {
            return TransactionParts(id, inputs, timestamp, notary, references, networkParametersHash)
        }
    }
}

/** Exception internal to the notary service. Does not get exposed to CorDapps and flows calling [NotaryFlow.Client]. */
class NotaryInternalException(val error: NotaryError) : FlowException("Unable to notarise: $error")