package net.corda.finance.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.SwapIdentitiesFlow
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import java.util.*

/**
 * Initiates a flow that sends cash to a recipient.
 *
 * @param amount the amount of a currency to pay to the recipient.
 * @param recipient the party to pay the currency to.
 * @param issuerConstraint if specified, the payment will be made using only cash issued by the given parties.
 * @param anonymous whether to anonymous the recipient party. Should be true for normal usage, but may be false
 * for testing purposes.
 */
@StartableByRPC
open class CashPaymentFlow(
        val amount: Amount<Currency>,
        val recipient: Party,
        val anonymous: Boolean,
        progressTracker: ProgressTracker,
        val issuerConstraint: Set<Party> = emptySet()) : AbstractCashFlow<AbstractCashFlow.Result>(progressTracker) {
    /** A straightforward constructor that constructs spends using cash states of any issuer. */
    constructor(amount: Amount<Currency>, recipient: Party) : this(amount, recipient, true, tracker())
    /** A straightforward constructor that constructs spends using cash states of any issuer. */
    constructor(amount: Amount<Currency>, recipient: Party, anonymous: Boolean) : this(amount, recipient, anonymous, tracker())
    constructor(request: PaymentRequest) : this(request.amount, request.recipient, request.anonymous, tracker(), request.issuerConstraint)

    @Suspendable
    override fun call(): AbstractCashFlow.Result {
        progressTracker.currentStep = GENERATING_ID
        val txIdentities = if (anonymous) {
            // TODO Now that SwapIdentitiesFlow is inlined we have two options:
            // 1. Either delegate the question of anonymisation to the caller by making recipient an AbstractParty and remove
            // the anonymous parameter. This makes most sense to me as CashPaymentFlow seems best suited as a sub-class being
            // called by an outer flow. This outerflow would have a counterpart flow and these two flows would sub-flow
            // SwapIdentitiesFlow at the correct places. With this solution the BoC demo should be updated with this outerflow
            // design.
            //
            // 2. The other solution is to keep this similar to what was before. If anonymous is true then subFlow SwapIdentitiesFlow.
            // However now that SwapIdentitiesFlow is inlined, this requires making CashPaymentFlow an @InitiatingFlow and thus
            // requires an @InitiatedBy flow as well, whose only job would be to sub-flow SwapIdentitiesFlow. This probably has the
            // least impact on the existing code.
            // subFlow(SwapIdentitiesFlow(recipient))
            emptyMap<Party, AnonymousParty>()
        } else {
            emptyMap<Party, AnonymousParty>()
        }
        val anonymousRecipient = txIdentities[recipient] ?: recipient
        progressTracker.currentStep = GENERATING_TX
        val builder = TransactionBuilder(null as Party?)
        // TODO: Have some way of restricting this to states the caller controls
        val (spendTX, keysForSigning) = try {
            Cash.generateSpend(serviceHub,
                    builder,
                    amount,
                    anonymousRecipient,
                    issuerConstraint)
        } catch (e: InsufficientBalanceException) {
            throw CashException("Insufficient cash for spend: ${e.message}", e)
        }

        progressTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(spendTX, keysForSigning)

        progressTracker.currentStep = FINALISING_TX
        finaliseTx(setOf(recipient), tx, "Unable to notarise spend")
        return Result(tx, anonymousRecipient)
    }

    @CordaSerializable
    class PaymentRequest(amount: Amount<Currency>, val recipient: Party, val anonymous: Boolean, val issuerConstraint: Set<Party> = emptySet()) : AbstractRequest(amount)
}