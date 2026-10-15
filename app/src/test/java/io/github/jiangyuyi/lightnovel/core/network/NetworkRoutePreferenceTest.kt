package io.github.jiangyuyi.lightnovel.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRoutePreferenceTest {
    @Test fun successfulFallbackIsTriedBeforePreviouslyFailingRoutesForTheSameHost() {
        val preference = NetworkRoutePreference(4)
        assertEquals(listOf(0, 1, 2, 3), preference.order("www.lightnovel.fun"))
        preference.succeeded("www.lightnovel.fun", 1)
        assertEquals(listOf(1, 0, 2, 3), preference.order("www.lightnovel.fun"))
        assertEquals(listOf(0, 1, 2, 3), preference.order("res.lightnovel.fun"))
        preference.failed("www.lightnovel.fun", 0)
        assertEquals(listOf(1, 0, 2, 3), preference.order("www.lightnovel.fun"))
        preference.failed("www.lightnovel.fun", 1)
        assertEquals(listOf(0, 1, 2, 3), preference.order("www.lightnovel.fun"))
        preference.succeeded("www.lightnovel.fun", 2)
        preference.succeeded("www.lightnovel.fun", 0)
        assertEquals(listOf(0, 1, 2, 3), preference.order("www.lightnovel.fun"))
    }
}
