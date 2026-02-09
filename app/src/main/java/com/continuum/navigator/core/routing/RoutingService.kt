/**
 * Routing Service
 *
 * High-level service for route calculation with mode selection and caching.
 *
 * ## Features
 *
 * - Automatic engine selection based on mode
 * - Route caching to avoid redundant calculations
 * - Cancellation support
 * - LiveData for observing routing state
 *
 * ## Usage
 *
 * ```kotlin
 * val routingService = RoutingService(context)
 * routingService.setMode(RoutingMode.ONLINE_GOOGLE, apiKey = "...")
 * 
 * lifecycleScope.launch {
 *     val result = routingService.calculateRoute(origin, destination)
 *     when (result) {
 *         is RouteResult.Success -> showRoute(result.primaryRoute)
 *         is RouteResult.Error -> showError(result.message)
 *     }
 * }
 * ```
 */
package com.continuum.navigator.core.routing

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Routing state.
 */
sealed class RoutingState {
    /** Idle - no routing in progress */
    object Idle : RoutingState()
    
    /** Calculating a route */
    data class Calculating(val origin: RoutePoint, val destination: RoutePoint) : RoutingState()
    
    /** Route calculated successfully */
    data class Success(val result: RouteResult.Success) : RoutingState()
    
    /** Route calculation failed */
    data class Error(val error: RouteResult.Error) : RoutingState()
}

/**
 * Service for managing routing operations.
 */
class RoutingService(
    private val context: Context,
) {
    companion object {
        private const val TAG = "RoutingService"
        private const val PREFS_NAME = "navigator_routing"
        private const val PREF_ROUTING_MODE = "routing_mode"
        private const val PREF_GOOGLE_API_KEY = "google_api_key"
        private const val PREF_OSRM_BASE_URL = "osrm_base_url"
        private const val PREF_AUTO_CACHE_ROUTES = "auto_cache_routes"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var currentMode: RoutingMode = loadSavedMode()
    private var currentEngine: RoutingEngine? = null
    private var googleApiKey: String = prefs.getString(PREF_GOOGLE_API_KEY, "") ?: ""
    private var osrmBaseUrl: String = prefs.getString(PREF_OSRM_BASE_URL, "https://router.project-osrm.org") ?: "https://router.project-osrm.org"

    private val _routingState = MutableLiveData<RoutingState>(RoutingState.Idle)
    val routingState: LiveData<RoutingState> = _routingState

    private var cachedRoute: Route? = null
    private var cachedRequest: RouteRequest? = null

    // Offline routing engine for caching routes
    private val offlineRoutingEngine = OfflineRoutingEngine(context)
    
    // Auto-cache routes for offline use (default: enabled)
    private var autoCacheRoutes: Boolean = prefs.getBoolean(PREF_AUTO_CACHE_ROUTES, true)

    init {
        initializeEngine()
    }
    
    /**
     * Enable or disable automatic route caching for offline use.
     */
    fun setAutoCacheRoutes(enabled: Boolean) {
        autoCacheRoutes = enabled
        prefs.edit().putBoolean(PREF_AUTO_CACHE_ROUTES, enabled).apply()
        Log.i(TAG, "Auto-cache routes: $enabled")
    }
    
    /**
     * Check if auto-caching is enabled.
     */
    fun isAutoCacheEnabled(): Boolean = autoCacheRoutes
    
    /**
     * Get the offline routing engine for managing cached routes.
     */
    fun getOfflineRoutingEngine(): OfflineRoutingEngine = offlineRoutingEngine

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Set the routing mode and configuration.
     *
     * @param mode Routing mode to use
     * @param apiKey Google API key (for ONLINE_GOOGLE mode)
     * @param osrmUrl OSRM server URL (for ONLINE_OSRM mode)
     * @param save Whether to save this configuration for next launch
     */
    fun setMode(
        mode: RoutingMode,
        apiKey: String = googleApiKey,
        osrmUrl: String = osrmBaseUrl,
        save: Boolean = true
    ) {
        currentMode = mode
        googleApiKey = apiKey
        osrmBaseUrl = osrmUrl

        if (save) {
            prefs.edit().apply {
                putString(PREF_ROUTING_MODE, mode.name)
                putString(PREF_GOOGLE_API_KEY, apiKey)
                putString(PREF_OSRM_BASE_URL, osrmUrl)
                apply()
            }
        }

        initializeEngine()
        Log.i(TAG, "Routing mode set to: $mode")
    }

    /**
     * Get the current routing mode.
     */
    fun getMode(): RoutingMode = currentMode

    /**
     * Check if the current engine is available.
     */
    suspend fun isEngineAvailable(): Boolean {
        return currentEngine?.isAvailable() ?: false
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Route Calculation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Calculate a route from origin to destination.
     *
     * @param origin Starting point
     * @param destination End point
     * @param waypoints Optional intermediate waypoints
     * @param mode Travel mode
     * @param alternatives Request alternative routes
     * @param avoid Things to avoid
     * @return RouteResult with route(s) or error
     */
    suspend fun calculateRoute(
        origin: RoutePoint,
        destination: RoutePoint,
        waypoints: List<RoutePoint> = emptyList(),
        mode: TravelMode = TravelMode.DRIVING,
        alternatives: Boolean = false,
        avoid: Set<RouteAvoidance> = emptySet(),
    ): RouteResult {
        val request = RouteRequest(
            origin = origin,
            destination = destination,
            waypoints = waypoints,
            mode = mode,
            alternatives = alternatives,
            avoid = avoid
        )

        // Check cache
        if (request == cachedRequest && cachedRoute != null) {
            Log.d(TAG, "Returning cached route")
            return RouteResult.Success(listOf(cachedRoute!!))
        }

        _routingState.postValue(RoutingState.Calculating(origin, destination))

        val engine = currentEngine
        if (engine == null) {
            val error = RouteResult.Error(
                RouteErrorCode.PROVIDER_ERROR,
                "No routing engine configured"
            )
            _routingState.postValue(RoutingState.Error(error))
            return error
        }

        Log.d(TAG, "Calculating route: ${origin.latitude},${origin.longitude} → ${destination.latitude},${destination.longitude}")

        val result = engine.calculateRoute(request)

        when (result) {
            is RouteResult.Success -> {
                cachedRoute = result.primaryRoute
                cachedRequest = request
                _routingState.postValue(RoutingState.Success(result))
                Log.i(TAG, "Route calculated: ${result.primaryRoute.distanceText}, ${result.primaryRoute.durationText}")
                
                // Auto-cache route for offline use
                if (autoCacheRoutes && currentMode.isOnline) {
                    val originStr = "%.4f,%.4f".format(origin.latitude, origin.longitude)
                    val destStr = "%.4f,%.4f".format(destination.latitude, destination.longitude)
                    val routeName = "$originStr → $destStr"
                    val cached = offlineRoutingEngine.cacheRoute(result.primaryRoute, routeName)
                    if (cached) {
                        Log.i(TAG, "Route auto-cached for offline use")
                    }
                }
            }
            is RouteResult.Error -> {
                _routingState.postValue(RoutingState.Error(result))
                Log.e(TAG, "Route calculation failed: ${result.code} - ${result.message}")
            }
        }

        return result
    }

    /**
     * Cancel any ongoing route calculation.
     */
    fun cancelCalculation() {
        currentEngine?.cancel()
        _routingState.postValue(RoutingState.Idle)
        Log.d(TAG, "Route calculation cancelled")
    }

    /**
     * Clear cached route.
     */
    fun clearCache() {
        cachedRoute = null
        cachedRequest = null
        Log.d(TAG, "Route cache cleared")
    }

    /**
     * Get the cached route if available.
     */
    fun getCachedRoute(): Route? = cachedRoute

    // ═══════════════════════════════════════════════════════════════════════
    // Private
    // ═══════════════════════════════════════════════════════════════════════

    private fun initializeEngine() {
        try {
            currentEngine = RoutingEngineFactory.create(
                mode = currentMode,
                context = context,
                googleApiKey = googleApiKey,
                osrmBaseUrl = osrmBaseUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize routing engine: $currentMode - ${e.message}")
            currentEngine = null
        }
    }

    private fun loadSavedMode(): RoutingMode {
        val modeName = prefs.getString(PREF_ROUTING_MODE, null)
        return if (modeName != null) {
            try {
                RoutingMode.valueOf(modeName)
            } catch (e: IllegalArgumentException) {
                RoutingMode.ONLINE_OSRM // Default to free OSRM
            }
        } else {
            RoutingMode.ONLINE_OSRM
        }
    }
}

/**
 * Extension function to create RouteRequest from two RoutePoints.
 */
fun RoutePoint.routeTo(
    destination: RoutePoint,
    mode: TravelMode = TravelMode.DRIVING
): RouteRequest {
    return RouteRequest(
        origin = this,
        destination = destination,
        mode = mode
    )
}
