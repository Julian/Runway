package com.grayvines.runway.system.badges

import com.grayvines.runway.model.PackageKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BadgePollerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initial connect refresh is delivered even before worker coroutine starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val expected = mapOf(PackageKey("com.example.chat", 0L) to 3)
        val poller =
            BadgePoller<String>(
                scope = backgroundScope,
                dispatcher = dispatcher,
                readCounts = { expected },
            )

        // Worker coroutine has not run yet on StandardTestDispatcher; connect must buffer its poll.
        poller.connect("listener-1")
        runCurrent()

        assertEquals(expected, poller.counts.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `burst of refresh calls coalesces into a single poll`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reads = AtomicInteger(0)
        val poller =
            BadgePoller<String>(
                scope = backgroundScope,
                dispatcher = dispatcher,
                readCounts = {
                    mapOf(PackageKey("com.example.mail", 0L) to reads.incrementAndGet())
                },
            )

        poller.connect("listener-1")
        repeat(10) { poller.refresh() }
        runCurrent()

        assertEquals(1, reads.get())
        assertEquals(mapOf(PackageKey("com.example.mail", 0L) to 1), poller.counts.value)
    }

    @Test
    fun `disconnect racing an in-flight read clears counts and rejects stale result`() = runTest {
        val readStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        var disconnected = 0
        val poller =
            BadgePoller<String>(
                scope = backgroundScope,
                dispatcher = Dispatchers.IO,
                onDisconnect = { disconnected++ },
                readCounts = { listener ->
                    if (listener == "listener-1") {
                        readStarted.countDown()
                        allowFinish.await(5, TimeUnit.SECONDS)
                        try {
                            mapOf(PackageKey("com.example.chat", 0L) to 7)
                        } finally {
                            readFinished.countDown()
                        }
                    } else {
                        mapOf(PackageKey("com.example.sms", 0L) to 2)
                    }
                },
            )

        poller.connect("listener-1")
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        poller.disconnect("listener-1")
        allowFinish.countDown()
        assertTrue(readFinished.await(5, TimeUnit.SECONDS))

        assertEquals(1, disconnected)
        assertEquals(emptyMap<PackageKey, Int>(), poller.counts.value)

        // Reconnecting on the same poller instance must still publish live counts.
        poller.connect("listener-2")
        assertEquals(
            mapOf(PackageKey("com.example.sms", 0L) to 2),
            poller.counts.first { it.isNotEmpty() },
        )
    }
}
