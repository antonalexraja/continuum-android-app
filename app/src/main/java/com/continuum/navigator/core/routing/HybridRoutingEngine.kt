/**
 * Hybrid Routing Engine
 *
 * Automatically falls back between online and offline routing providers.
 * Provides seamless routing regardless of network availability.
 *
 * ## Fallback Strategy
 *
 * 1. Try primary engine (default: Google)
 * 2. If fails, try secondary online engine (OSRM)
 * 3. If no network, try offline engine
 * 4. Return best available result or error
 *
 * ## Usage
 *
 * ```kotlin
 * val engine = HybridRoutingEngine(context)
 * val result = engine.calculateRoute(request)
 * // Automatically handles fallback
 * ```
 */
package com.continuum.navigator.core.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Hybrid routing engine with automatic fallback.
 */
class HybridRoutingEngine(
    private val context: Context,
    private val googleApiKey: String = "",
    private val osrmBaseUrl: String = "https://router.project-osrm.org"
) : RoutingEngine {

    companion object {
        private const val TAG = "HybridRoutingEngine"
    }

    override val provider: RoutingProvider = RoutingProvider.HYBRID

    // Engines in priority order
    private val googleEngine: GoogleRoutingEngine? = if (googleApiKey.isNotEmpty()) {
        GoogleRoutingEngine(googleApiKey)
    } else null
    
    private val osrmEngine = OsrmRoutingEngine(osrmBaseUrl)
    private val offlineEngine = OfflineRoutingEngine(context)

    // Track which engine was last used successfully
    private var lastSuccessfulProvider: RoutingProvider? = null

    /**
     * Check if any routing engine is available.
     */
    override suspend fun isAvailable(): Boolean {
        // Check online engines if network is available
        if (isNetworkAvailable()) {
            if (googleEngine?.isAvailable() == true) return true
            if (osrmEngine.isAvailable()) return true
        }
        
        // Check offline engine
        if (offlineEngine.isAvailable()) return true
        
        return false
    }

    /**
     * Calculate route with automatic fallback.
     */
    override suspend fun calculateRoute(request: RouteRequest): RouteResult {
        val hasNetwork = isNetworkAvailable()
        
        Log.d(TAG, "Calculating route (network: $hasNetwork)")
        
        // Try online engines if network is available
        if (hasNetwork) {
            // Try Google first (best quality, but needs API key)
            googleEngine?.let { engine ->
                Log.d(TAG, "Trying Google routing...")
                val result = engine.calculateRoute(request)
                if (result is RouteResult.Success) {
                    lastSuccessfulProvider = RoutingProvider.GOOGLE
                    Log.i(TAG, "Google routing succeeded")
                    return result
                }
                Log.w(TAG, "Google routing failed: ${(result as? RouteResult.Error)?.message}")
            }
            
            // Try OSRM (free, no API key needed)
            Log.d(TAG, "Trying OSRM routing...")
            val osrmResult = osrmEngine.calculateRoute(request)
            if (osrmResult is RouteResult.Success) {
                lastSuccessfulProvider = RoutingProvider.OSRM
                Log.i(TAG, "OSRM routing succeeded")
                return osrmResult
            }
            Log.w(TAG, "OSRM routing failed: ${(osrmResult as? RouteResult.Error)?.message}")
        }
        
        // Try offline engine
        Log.d(TAG, "Trying offline routing...")
        val offlineResult = offlineEngine.calculateRoute(request)
        if (offlineResult is RouteResult.Success) {
            lastSuccessfulProvider = RoutingProvider.OFFLINE_OSM
            Log.i(TAG, "Offline routing succeeded")
            return offlineResult
        }
        
        // All engines failed
        val errorMessage = if (!hasNetwork) {
            "No network connection and offline routing is not available"
        } else {
            "All routing providers failed. Please check your internet connection."
        }
        
        Log.e(TAG, "All routing engines failed")
        return RouteResult.Error(
            code = RouteErrorCode.NETWORK_ERROR,
            message = errorMessage
        )
    }

    /**
     * Get the provider that was last used successfully.
     */
    fun getLastSuccessfulProvider(): RoutingProvider? = lastSuccessfulProvider

    /**
     * Check if network is available.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Configure Google API key.
     */
    fun setGoogleApiKey(apiKey: String): HybridRoutingEngine {
        return HybridRoutingEngine(context, apiKey, osrmBaseUrl)
    }

    /**
     * Configure OSRM server URL.
     */
    fun setOsrmBaseUrl(url: String): HybridRoutingEngine {
        return HybridRoutingEngine(context, googleApiKey, url)
    }
}
