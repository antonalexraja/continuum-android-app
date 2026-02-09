/**
 * Routing Mode
 *
 * Defines the available routing strategies.
 */
package com.continuum.navigator.core.routing

/**
 * Available routing modes.
 */
enum class RoutingMode {
    /**
     * Google Directions API (online).
     * - Pros: Accurate, traffic-aware, widely tested
     * - Cons: Requires API key, costs money at scale
     */
    ONLINE_GOOGLE,

    /**
     * OSRM (Open Source Routing Machine) online.
     * - Pros: Free, open data, no API key needed
     * - Cons: No traffic, public server may be slow
     */
    ONLINE_OSRM,

    /**
     * Offline OSM-based routing.
     * - Pros: Works without internet
     * - Cons: Requires pre-downloaded map data, larger app size
     */
    OFFLINE_OSM,

    /**
     * Hybrid fallback mode.
     * Tries providers in order: Google → OSRM → Offline
     */
    HYBRID_FALLBACK;
    
    /**
     * Whether this mode requires internet connectivity.
     */
    val isOnline: Boolean
        get() = this == ONLINE_GOOGLE || this == ONLINE_OSRM
    
    /**
     * Whether this mode can work without internet.
     */
    val isOffline: Boolean
        get() = this == OFFLINE_OSM
    
    /**
     * Whether this mode uses multiple providers.
     */
    val isHybrid: Boolean
        get() = this == HYBRID_FALLBACK
}
