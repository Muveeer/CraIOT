package com.livecontrol.iotmod.mqtt

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test fun exponential_sequence_without_jitter() {
        val expected = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L)
        expected.forEachIndexed { i, e ->
            assertEquals(e, Backoff.delayMs(i + 1, jitter = 0.0))
        }
    }
    @Test fun jitter_stays_within_20_percent() {
        repeat(100) {
            val d = Backoff.delayMs(3, jitter = 0.2, rnd = Random(it))
            assertTrue(d in 3200..4800)
        }
    }
    @Test fun never_negative() {
        assertTrue(Backoff.delayMs(1, base = 10, jitter = 0.9, rnd = Random(0)) >= 0)
    }
}
