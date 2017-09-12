package net.corda.finance.flows

import net.corda.core.identity.Party
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.`issued by`
import net.corda.finance.contracts.asset.Cash
import net.corda.testing.BOC
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.node.InMemoryMessagingNetwork.ServicePeerAllocationStrategy.RoundRobin
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashExitFlowTests {
    private val mockNet = MockNetwork(servicePeerAllocationStrategy = RoundRobin())
    private val initialBalance = 2000.DOLLARS
    private val ref = OpaqueBytes.of(0x01)
    private lateinit var bankOfCordaNode: MockNode
    private lateinit var bankOfCorda: Party
    private lateinit var notaryNode: MockNode
    private lateinit var notary: Party

    @Before
    fun start() {
        notaryNode = mockNet.createNotaryNode(null, DUMMY_NOTARY.name)
        bankOfCordaNode = mockNet.createPartyNode(notaryNode.network.myAddress, BOC.name)
        notary = notaryNode.info.notaryIdentity
        bankOfCorda = bankOfCordaNode.info.legalIdentity

        mockNet.runNetwork()
        val future = bankOfCordaNode.services.startFlow(CashIssueFlow(initialBalance, ref, notary)).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `exit some cash`() {
        val exitAmount = 500.DOLLARS
        val future = bankOfCordaNode.services.startFlow(CashExitFlow(exitAmount, ref)).resultFuture
        mockNet.runNetwork()
        val exitTx = future.getOrThrow().stx.tx
        val expected = (initialBalance - exitAmount).`issued by`(bankOfCorda.ref(ref))
        assertEquals(1, exitTx.inputs.size)
        assertEquals(1, exitTx.outputs.size)
        val output = exitTx.outputsOfType<Cash.State>().single()
        assertEquals(expected, output.amount)
    }

    @Test
    fun `exit zero cash`() {
        val expected = 0.DOLLARS
        val future = bankOfCordaNode.services.startFlow(CashExitFlow(expected, ref)).resultFuture
        mockNet.runNetwork()
        assertFailsWith<CashException> {
            future.getOrThrow()
        }
    }
}
