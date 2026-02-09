/**
 * Routing Engine Interface
 *
 * Common interface for all routing providers.
 * Implementations must produce the standard [Route] model.
 *
 * ## Design Principles
 *
 * 1. **Async**: All route calculations are suspend functions
 * 2. **Cancellable**: Operations respect coroutine cancellation
 * 3. **Provider-Agnostic Output**: All engines produce [Route] objects
 * 4. **Stateless**: Engines don't hold route state
 *
 * ## Implementations
 *
 * - [GoogleRoutingEngine] - Google Directions API
 * - [OsrmRoutingEngine] - OSRM public/private server
 * - [OfflineRoutingEngine] - Local offline routing (stub)
 * - [HybridRoutingEngine] - Automatic fallback between providers
 */
package com.continuum.navigator.core.routing

import android.content.Context

/**
 * Interface for routing engines.
 */
interface RoutingEngine {

    /**
     * The provider type for this engine.
     */
    val provider: RoutingProvider

    /**
     * Check if this engine is currently available.
     * May depend on network, API keys, offline data, etc.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Calculate a route from origin to destination.
     *
     * @param request Route request parameters
     * @return RouteResult with route(s) or error
     */
    suspend fun calculateRoute(request: RouteRequest): RouteResult

    /**
     * Cancel any ongoing route calculation.
     * Default implementation does nothing.
     */
    fun cancel() {}
}

/**
 * Factory for creating routing engines.
 */
object RoutingEngineFactory {

    /**
     * Create a routing engine for the specified mode.
     *
     * @param mode Routing mode
     * @param context Android context (for offline/hybrid engines)
     * @param googleApiKey Google Directions API key (for ONLINE_GOOGLE)
     * @param osrmBaseUrl OSRM server base URL (for ONLINE_OSRM)
     * @return RoutingEngine instance
     */
    fun create(
        mode: RoutingMode,
        context: Context? = null,
        googleApiKey: String = "",
        osrmBaseUrl: String = "https://router.project-osrm.org",
    ): RoutingEngine {
        return when (mode) {
            RoutingMode.ONLINE_GOOGLE -> GoogleRoutingEngine(googleApiKey)
            RoutingMode.ONLINE_OSRM -> OsrmRoutingEngine(osrmBaseUrl)
            RoutingMode.OFFLINE_OSM -> {
                requireNotNull(context) { "Context required for offline routing" }
                OfflineRoutingEngine(context)
            }
            RoutingMode.HYBRID_FALLBACK -> {
                requireNotNull(context) { "Context required for hybrid routing" }
                HybridRoutingEngine(context, googleApiKey, osrmBaseUrl)
            }
        }
    }
}
