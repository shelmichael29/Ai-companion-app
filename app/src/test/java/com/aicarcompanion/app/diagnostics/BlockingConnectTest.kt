package com.aicarcompanion.app.diagnostics

import com.aicarcompanion.app.diagnostics.bluetooth.blockingConnectWithCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BlockingConnectTest {
    @Test fun `timeout closes a socket while its blocking connect is still pending`() = runBlocking {
        val unblock = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        try {
            blockingConnectWithCancellation(100, {
                closed.set(true)
                unblock.countDown()
            }) {
                unblock.await(2, TimeUnit.SECONDS)
            }
            throw AssertionError("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertTrue("The blocked connection must be aborted", closed.get())
        } finally {
            unblock.countDown()
        }
    }

    @Test fun `cancelling a connection closes the pending socket`() = runBlocking {
        val unblock = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            blockingConnectWithCancellation(5_000, {
                closed.set(true)
                unblock.countDown()
            }) {
                entered.countDown()
                unblock.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        delay(20)
        job.cancelAndJoin()
        assertTrue("Explicit cancellation must abort the socket", closed.get())
        unblock.countDown()
    }
}
