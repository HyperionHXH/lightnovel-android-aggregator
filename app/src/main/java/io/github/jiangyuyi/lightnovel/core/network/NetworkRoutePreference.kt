package io.github.jiangyuyi.lightnovel.core.network

import java.util.concurrent.ConcurrentHashMap

/** Connection preference is per host and process; never persists a site's old CDN address. */
internal class NetworkRoutePreference(private val routeCount: Int) {
    private val successfulRoutes = ConcurrentHashMap<String, Int>()
    private val routeOrders = Array(routeCount) { preferred ->
        buildList(routeCount) {
            add(preferred)
            for (route in 0 until routeCount) if (route != preferred) add(route)
        }
    }

    fun order(host: String): List<Int> = routeOrders[successfulRoutes[host] ?: 0]

    fun succeeded(host: String, route: Int) {
        successfulRoutes[host] = route
    }

    fun failed(host: String, route: Int) {
        successfulRoutes.remove(host, route)
    }
}
