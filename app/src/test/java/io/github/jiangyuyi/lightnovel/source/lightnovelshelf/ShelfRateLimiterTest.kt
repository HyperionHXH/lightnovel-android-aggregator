package io.github.jiangyuyi.lightnovel.source.lightnovelshelf

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfRateLimiterTest {
    @Test
    fun `request beyond window capacity waits for oldest timestamp`() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val limiter = ShelfRateLimiter(
            maxRequests = 2,
            windowMillis = 1_000,
            nowMillis = { now },
            sleep = { wait ->
                waits += wait
                now += wait
            },
        )

        limiter.run { Unit }
        limiter.run { Unit }
        limiter.run { Unit }

        assertEquals(listOf(1_000L), waits)
    }
}

