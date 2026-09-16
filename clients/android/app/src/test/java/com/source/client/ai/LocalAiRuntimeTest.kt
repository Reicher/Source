package com.source.client.ai

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalAiRuntimeTest {
    @Test
    fun `request that times out in the mutex queue never starts inference`() = runBlocking {
        val mutex = Mutex(locked = true)
        val timedOut = AtomicBoolean(false)
        var timeoutEvents = 0
        var inferenceStarted = false
        val queued = launch(Dispatchers.Default) {
            runQueuedLocalInference(
                mutex,
                timedOut,
                onTimedOut = { timeoutEvents += 1 },
                generate = { inferenceStarted = true },
            )
        }
        yield()

        timedOut.set(true)
        mutex.unlock()
        queued.join()

        assertEquals(1, timeoutEvents)
        assertFalse(inferenceStarted)
    }
}
