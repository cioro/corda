package net.corda.node.services

import net.corda.core.contracts.*
import net.corda.core.crypto.generateKeyPair
import net.corda.core.flows.NotaryChangeFlow
import net.corda.core.flows.StateReplacementException
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.node.internal.StartedNode
import net.corda.node.services.network.NetworkMapService
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.ServiceInfo
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.chooseIdentity
import net.corda.testing.contracts.DummyContract
import net.corda.testing.dummyCommand
import net.corda.testing.getTestPartyAndCertificate
import net.corda.testing.node.MockNetwork
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotaryChangeTests {
    lateinit var mockNet: MockNetwork
    lateinit var oldNotaryNode: StartedNode<MockNetwork.MockNode>
    lateinit var newNotaryNode: StartedNode<MockNetwork.MockNode>
    lateinit var clientNodeA: StartedNode<MockNetwork.MockNode>
    lateinit var clientNodeB: StartedNode<MockNetwork.MockNode>
    lateinit var notaryNewId: Party
    lateinit var notaryOldId: Party

    @Before
    fun setUp() {
        mockNet = MockNetwork()
        oldNotaryNode = mockNet.createNode(
                legalName = DUMMY_NOTARY.name,
                advertisedServices = *arrayOf(ServiceInfo(NetworkMapService.type), ServiceInfo(SimpleNotaryService.type)))
        clientNodeA = mockNet.createNode(networkMapAddress = oldNotaryNode.network.myAddress)
        clientNodeB = mockNet.createNode(networkMapAddress = oldNotaryNode.network.myAddress)
        newNotaryNode = mockNet.createNode(networkMapAddress = oldNotaryNode.network.myAddress, advertisedServices = ServiceInfo(SimpleNotaryService.type))
        mockNet.registerIdentities()
        mockNet.runNetwork() // Clear network map registration messages
        oldNotaryNode.internals.ensureRegistered()
        notaryNewId = newNotaryNode.info.legalIdentities[1]
        notaryOldId = oldNotaryNode.info.legalIdentities[1]
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `should change notary for a state with single participant`() {
        val state = issueState(clientNodeA, oldNotaryNode, notaryOldId)
        val newNotary = notaryNewId
        val flow = NotaryChangeFlow(state, newNotary)
        val future = clientNodeA.services.startFlow(flow)

        mockNet.runNetwork()

        val newState = future.resultFuture.getOrThrow()
        assertEquals(newState.state.notary, newNotary)
    }

    @Test
    fun `should change notary for a state with multiple participants`() {
        val state = issueMultiPartyState(clientNodeA, clientNodeB, oldNotaryNode, notaryOldId)
        val newNotary = notaryNewId
        val flow = NotaryChangeFlow(state, newNotary)
        val future = clientNodeA.services.startFlow(flow)

        mockNet.runNetwork()

        val newState = future.resultFuture.getOrThrow()
        assertEquals(newState.state.notary, newNotary)
        val loadedStateA = clientNodeA.services.loadState(newState.ref)
        val loadedStateB = clientNodeB.services.loadState(newState.ref)
        assertEquals(loadedStateA, loadedStateB)
    }

    @Test
    fun `should throw when a participant refuses to change Notary`() {
        val state = issueMultiPartyState(clientNodeA, clientNodeB, oldNotaryNode, notaryOldId)
        val newEvilNotary = getTestPartyAndCertificate(CordaX500Name(organisation = "Evil R3", locality = "London", country = "GB"), generateKeyPair().public)
        val flow = NotaryChangeFlow(state, newEvilNotary.party)
        val future = clientNodeA.services.startFlow(flow)

        mockNet.runNetwork()

        assertThatExceptionOfType(StateReplacementException::class.java).isThrownBy {
            future.resultFuture.getOrThrow()
        }
    }

    @Test
    fun `should not break encumbrance links`() {
        val issueTx = issueEncumberedState(clientNodeA, notaryOldId)

        val state = StateAndRef(issueTx.outputs.first(), StateRef(issueTx.id, 0))
        val newNotary = notaryNewId
        val flow = NotaryChangeFlow(state, newNotary)
        val future = clientNodeA.services.startFlow(flow)
        mockNet.runNetwork()
        val newState = future.resultFuture.getOrThrow()
        assertEquals(newState.state.notary, newNotary)

        val recordedTx = clientNodeA.services.validatedTransactions.getTransaction(newState.ref.txhash)!!
        val notaryChangeTx = recordedTx.resolveNotaryChangeTransaction(clientNodeA.services)

        // Check that all encumbrances have been propagated to the outputs
        val originalOutputs = issueTx.outputStates
        val newOutputs = notaryChangeTx.outputStates
        assertTrue(originalOutputs.minus(newOutputs).isEmpty())

        // Check that encumbrance links aren't broken after notary change
        val encumbranceLink = HashMap<ContractState, ContractState?>()
        issueTx.outputs.forEach {
            val currentState = it.data
            val encumbranceState = it.encumbrance?.let { issueTx.outputs[it].data }
            encumbranceLink[currentState] = encumbranceState
        }
        notaryChangeTx.outputs.forEach {
            val currentState = it.data
            val encumbranceState = it.encumbrance?.let { notaryChangeTx.outputs[it].data }
            assertEquals(encumbranceLink[currentState], encumbranceState)
        }
    }

    private fun issueEncumberedState(node: StartedNode<*>, notaryIdentity: Party): WireTransaction {
        val owner = node.info.chooseIdentity().ref(0)
        val stateA = DummyContract.SingleOwnerState(Random().nextInt(), owner.party)
        val stateB = DummyContract.SingleOwnerState(Random().nextInt(), owner.party)
        val stateC = DummyContract.SingleOwnerState(Random().nextInt(), owner.party)

        val tx = TransactionBuilder(null).apply {
            addCommand(Command(DummyContract.Commands.Create(), owner.party.owningKey))
            addOutputState(stateA, DummyContract.PROGRAM_ID, notaryIdentity, encumbrance = 2) // Encumbered by stateB
            addOutputState(stateC, DummyContract.PROGRAM_ID, notaryIdentity)
            addOutputState(stateB, DummyContract.PROGRAM_ID, notaryIdentity, encumbrance = 1) // Encumbered by stateC
        }
        val stx = node.services.signInitialTransaction(tx)
        node.services.recordTransactions(stx)
        return tx.toWireTransaction()
    }

    // TODO: Add more test cases once we have a general flow/service exception handling mechanism:
    //       - A participant is offline/can't be found on the network
    //       - The requesting party is not a participant
    //       - The requesting party wants to change additional state fields
    //       - Multiple states in a single "notary change" transaction
    //       - Transaction contains additional states and commands with business logic
    //       - The transaction type is not a notary change transaction at all.
}

fun issueState(node: StartedNode<*>, notaryNode: StartedNode<*>, notaryIdentity: Party): StateAndRef<*> {
    val tx = DummyContract.generateInitial(Random().nextInt(), notaryIdentity, node.info.chooseIdentity().ref(0))
    val signedByNode = node.services.signInitialTransaction(tx)
    val stx = notaryNode.services.addSignature(signedByNode, notaryIdentity.owningKey)
    node.services.recordTransactions(stx)
    return StateAndRef(tx.outputStates().first(), StateRef(stx.id, 0))
}

fun issueMultiPartyState(nodeA: StartedNode<*>, nodeB: StartedNode<*>, notaryNode: StartedNode<*>, notaryIdentity: Party): StateAndRef<DummyContract.MultiOwnerState> {
    val state = TransactionState(DummyContract.MultiOwnerState(0,
            listOf(nodeA.info.chooseIdentity(), nodeB.info.chooseIdentity())), DummyContract.PROGRAM_ID, notaryIdentity)
    val tx = TransactionBuilder(notary = notaryIdentity).withItems(state, dummyCommand())
    val signedByA = nodeA.services.signInitialTransaction(tx)
    val signedByAB = nodeB.services.addSignature(signedByA)
    val stx = notaryNode.services.addSignature(signedByAB, notaryIdentity.owningKey)
    nodeA.services.recordTransactions(stx)
    nodeB.services.recordTransactions(stx)
    val stateAndRef = StateAndRef(state, StateRef(stx.id, 0))
    return stateAndRef
}

fun issueInvalidState(node: StartedNode<*>, notary: Party): StateAndRef<*> {
    val tx = DummyContract.generateInitial(Random().nextInt(), notary, node.info.chooseIdentity().ref(0))
    tx.setTimeWindow(Instant.now(), 30.seconds)
    val stx = node.services.signInitialTransaction(tx)
    node.services.recordTransactions(stx)
    return StateAndRef(tx.outputStates().first(), StateRef(stx.id, 0))
}
