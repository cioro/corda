package net.corda.node.services.transactions

import net.corda.core.flows.NotaryFlow
import net.corda.core.identity.Party
import net.corda.core.node.services.TimeWindowChecker
import net.corda.core.node.services.TrustedAuthorityNotaryService
import net.corda.nodeapi.ServiceType
import net.corda.node.services.api.ServiceHubInternal
import java.security.PublicKey

/** A Notary service that validates the transaction chain of the submitted transaction before committing it */
class ValidatingNotaryService(override val services: ServiceHubInternal, override val notaryIdentityKey: PublicKey) : TrustedAuthorityNotaryService() {
    companion object {
        val type = ServiceType.notary.getSubType("validating")
    }

    override val timeWindowChecker = TimeWindowChecker(services.clock)
    override val uniquenessProvider = PersistentUniquenessProvider()

    override fun createServiceFlow(otherParty: Party): NotaryFlow.Service = ValidatingNotaryFlow(otherParty, this)

    override fun start() {}
    override fun stop() {}
}