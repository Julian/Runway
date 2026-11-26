package com.grayvines.runway.system.apps

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshQueueTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `burst of enqueues coalesces into one running pass and one trailing pass`() = runTest {
        val firstStarted = CountDownLatch(1)
        val allowFirstFinish = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val batches = CopyOnWriteArrayList<RefreshBatch>()
        val queue =
            RefreshQueue(scope = backgroundScope, dispatcher = Dispatchers.IO) { batch ->
                batches += batch
                if (batches.size == 1) {
                    firstStarted.countDown()
                    allowFirstFinish.await(5, TimeUnit.SECONDS)
                } else {
                    secondFinished.countDown()
                }
            }

        queue.enqueue(arrayOf("pkg.one"))
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        queue.enqueue(arrayOf("pkg.two"))
        queue.enqueue(arrayOf("pkg.three"))
        queue.enqueue(arrayOf("pkg.two"))
        allowFirstFinish.countDown()
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))

        assertEquals(
            listOf(
                RefreshBatch(listOf("pkg.one"), reconcile = true),
                RefreshBatch(listOf("pkg.two", "pkg.three"), reconcile = true),
            ),
            batches.toList(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `failure on one pass still drains a queued trailing refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seen = mutableListOf<RefreshBatch>()
        lateinit var queue: RefreshQueue
        queue =
            RefreshQueue(scope = backgroundScope, dispatcher = dispatcher) { batch ->
                seen += batch
                if (seen.size == 1) {
                    queue.enqueue(arrayOf("pkg.after"))
                    error("transient binder failure")
                }
            }

        queue.enqueue(arrayOf("pkg.first"))
        runCurrent()

        assertEquals(
            listOf(
                RefreshBatch(listOf("pkg.first"), reconcile = true),
                RefreshBatch(listOf("pkg.after"), reconcile = true),
            ),
            seen,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `non-reconciling enqueue stays non-reconciling unless coalesced with a reconciling one`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val seen = mutableListOf<RefreshBatch>()
            val queue =
                RefreshQueue(scope = backgroundScope, dispatcher = dispatcher) { batch ->
                    seen += batch
                }

            queue.enqueue(arrayOf("pkg.sd"), reconcile = false)
            runCurrent()

            queue.enqueue(arrayOf("pkg.sd"), reconcile = false)
            queue.enqueue(arrayOf("pkg.other"), reconcile = true)
            runCurrent()

            assertEquals(
                listOf(
                    RefreshBatch(listOf("pkg.sd"), reconcile = false),
                    RefreshBatch(listOf("pkg.sd", "pkg.other"), reconcile = true),
                ),
                seen,
            )
        }
}
