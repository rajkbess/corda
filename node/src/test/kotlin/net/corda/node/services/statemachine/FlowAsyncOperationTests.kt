package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import net.corda.core.internal.FlowAsyncOperation
import net.corda.core.internal.concurrent.OpenFuture
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.internal.concurrent.transpose
import net.corda.core.internal.executeAsync
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.testing.node.internal.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import kotlin.test.assertFailsWith

class FlowAsyncOperationTests {
    private lateinit var mockNet: InternalMockNetwork
    private lateinit var aliceNode: TestStartedNode
    @Before
    fun setup() {
        mockNet = InternalMockNetwork(
                cordappsForAllNodes = listOf(DUMMY_CONTRACTS_CORDAPP, enclosedCordapp()),
                notarySpecs = emptyList()
        )
        aliceNode = mockNet.createNode()
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `operation errors are propagated correctly`() {
        val flow = object : FlowLogic<Unit>() {
            @Suspendable
            override fun call() {
                executeAsync(ErroredExecute())
            }
        }

        assertFailsWith<ExecutionException> { aliceNode.services.startFlow(flow).resultFuture.get() }
    }

    private class ErroredExecute : FlowAsyncOperation<Unit> {
        override fun execute(deduplicationId: String): CordaFuture<Unit> {
            throw Exception()
        }
    }

    @Test
    fun `operation result errors are propagated correctly`() {
        val flow = object : FlowLogic<Unit>() {
            @Suspendable
            override fun call() {
                executeAsync(ErroredResult())
            }
        }

        assertFailsWith<ExecutionException> { aliceNode.services.startFlow(flow).resultFuture.get() }
    }

    private class ErroredResult : FlowAsyncOperation<Unit> {
        override fun execute(deduplicationId: String): CordaFuture<Unit> {
            val future = openFuture<Unit>()
            future.setException(Exception())
            return future
        }
    }

    @Test(timeout = 30_000)
    fun `flows waiting on an async operation do not block the thread`() {
        // Kick off 10 flows that submit a task to the service and wait until completion
        val numFlows = 10
        val futures = (1..10).map {
            aliceNode.services.startFlow(TestFlowWithAsyncAction(false)).resultFuture
        }
        // Make sure all flows submitted a task to the service and are awaiting completion
        val service = aliceNode.services.cordaService(WorkerService::class.java)
        while (service.pendingCount != numFlows) Thread.sleep(100)
        // Complete all pending tasks. If async operations aren't handled as expected, and one of the previous flows is
        // actually blocking the thread, the following flow will deadlock and the test won't finish.
        aliceNode.services.startFlow(TestFlowWithAsyncAction(true)).resultFuture.get()
        // Make sure all waiting flows completed successfully
        futures.transpose().get()
    }

    private class TestFlowWithAsyncAction(val completeAllTasks: Boolean) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val scv = serviceHub.cordaService(WorkerService::class.java)
            executeAsync(WorkerServiceTask(completeAllTasks, scv))
        }
    }

    private class WorkerServiceTask(val completeAllTasks: Boolean, val service: WorkerService) : FlowAsyncOperation<Unit> {
        override fun execute(deduplicationId: String): CordaFuture<Unit> {
            return service.performTask(completeAllTasks)
        }
    }

    /** A dummy worker service that queues up tasks and allows clearing the entire task backlog. */
    @CordaService
    class WorkerService(val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {
        private val pendingTasks = ConcurrentLinkedQueue<OpenFuture<Unit>>()
        val pendingCount: Int get() = pendingTasks.count()

        fun performTask(completeAllTasks: Boolean): CordaFuture<Unit> {
            val taskFuture = openFuture<Unit>()
            pendingTasks.add(taskFuture)
            if (completeAllTasks) {
                synchronized(this) {
                    while (!pendingTasks.isEmpty()) {
                        val fut = pendingTasks.poll()!!
                        fut.set(Unit)
                    }
                }
            }
            return taskFuture
        }
    }
}

