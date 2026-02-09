/**
 * Route Data Models
 *
 * Provider-agnostic route representation for the Navigator app.
 * All routing engines (Google, OSRM, Offline) produce this common output.
 *
 * ## Design Principles
 *
 * 1. **Engine-Agnostic**: Same model regardless of routing provider
 * 2. **Rich Maneuvers**: Detailed turn-by-turn instructions
 * 3. **Polyline Support**: Full geometry for map rendering
 * 4. **Immutable**: All data classes are immutable for thread safety
 *
 * ## Usage
 *
 * ```kotlin
 * val route = routingEngine.calculateRoute(origin, destination)
 * route.maneuvers.forEach { maneuver ->
 *     println("${maneuver.instruction} in ${maneuver.distanceMeters}m")
 * }
 * ```
 */
package com.continuum.navigator.core.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geographic point with latitude and longitude.
 */
data class RoutePoint(
    /** Latitude in degrees */
    val latitude: Double,
    /** Longitude in degrees */
    val longitude: Double,
) {
    companion object {
        val EMPTY = RoutePoint(0.0, 0.0)
    }

    /**
     * Calculate distance to another point in meters using Haversine formula.
     */
    fun distanceTo(other: RoutePoint): Double {
        val earthRadius = 6371000.0 // meters

        val lat1Rad = Math.toRadians(latitude)
        val lat2Rad = Math.toRadians(other.latitude)
        val deltaLat = Math.toRadians(other.latitude - latitude)
        val deltaLon = Math.toRadians(other.longitude - longitude)

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2) * sin(deltaLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * Calculate bearing to another point in degrees (0 = North, clockwise).
     */
    fun bearingTo(other: RoutePoint): Double {
        val lat1Rad = Math.toRadians(latitude)
        val lat2Rad = Math.toRadians(other.latitude)
        val deltaLon = Math.toRadians(other.longitude - longitude)

        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360.0

        return bearing
    }
}

/**
 * Type of maneuver/turn.
 */
enum class ManeuverType {
    /** Start of the route */
    DEPART,
    /** End of the route */
    ARRIVE,
    /** Continue straight */
    STRAIGHT,
    /** Slight right turn */
    SLIGHT_RIGHT,
    /** Normal right turn */
    TURN_RIGHT,
    /** Sharp right turn */
    SHARP_RIGHT,
    /** U-turn to the right */
    UTURN_RIGHT,
    /** Slight left turn */
    SLIGHT_LEFT,
    /** Normal left turn */
    TURN_LEFT,
    /** Sharp left turn */
    SHARP_LEFT,
    /** U-turn to the left */
    UTURN_LEFT,
    /** Merge onto a road */
    MERGE,
    /** Take a ramp/exit */
    RAMP,
    /** Enter a roundabout */
    ROUNDABOUT_ENTER,
    /** Exit a roundabout */
    ROUNDABOUT_EXIT,
    /** Take a fork in the road */
    FORK,
    /** Ferry crossing */
    FERRY,
    /** Unknown maneuver type */
    UNKNOWN,
}

/**
 * A single maneuver (turn instruction) along the route.
 */
data class Maneuver(
    /** Type of maneuver */
    val type: ManeuverType,
    /** Location where maneuver occurs */
    val location: RoutePoint,
    /** Human-readable instruction (e.g., "Turn right onto Main Street") */
    val instruction: String,
    /** Distance from this maneuver to the next in meters */
    val distanceMeters: Double,
    /** Estimated duration from this maneuver to the next in seconds */
    val durationSeconds: Double,
    /** Road name after the maneuver (if available) */
    val roadName: String? = null,
    /** Exit number for roundabouts (1-based) */
    val roundaboutExitNumber: Int? = null,
    /** Bearing before the maneuver in degrees */
    val bearingBefore: Double? = null,
    /** Bearing after the maneuver in degrees */
    val bearingAfter: Double? = null,
    /** Index into the route polyline where this maneuver starts */
    val polylineStartIndex: Int = 0,
) {
    
    /**
     * Get a short version of the instruction for TTS.
     */
    val shortInstruction: String
        get() = when (type) {
            ManeuverType.DEPART -> "Start"
            ManeuverType.ARRIVE -> "Arrive at destination"
            ManeuverType.STRAIGHT -> "Continue straight"
            ManeuverType.SLIGHT_RIGHT -> "Slight right"
            ManeuverType.TURN_RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            ManeuverType.UTURN_RIGHT -> "U-turn"
            ManeuverType.SLIGHT_LEFT -> "Slight left"
            ManeuverType.TURN_LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.UTURN_LEFT -> "U-turn"
            ManeuverType.MERGE -> "Merge"
            ManeuverType.RAMP -> "Take the ramp"
            ManeuverType.ROUNDABOUT_ENTER -> "Enter roundabout"
            ManeuverType.ROUNDABOUT_EXIT -> roundaboutExitNumber?.let { "Take exit $it" } ?: "Exit roundabout"
            ManeuverType.FORK -> "Take the fork"
            ManeuverType.FERRY -> "Take the ferry"
            ManeuverType.UNKNOWN -> instruction.take(50)
        }
}

/**
 * A leg of a route (origin to waypoint, or waypoint to destination).
 */
data class RouteLeg(
    /** Maneuvers in this leg */
    val maneuvers: List<Maneuver>,
    /** Total distance of this leg in meters */
    val distanceMeters: Double,
    /** Estimated duration of this leg in seconds */
    val durationSeconds: Double,
    /** Starting address (if available) */
    val startAddress: String? = null,
    /** Ending address (if available) */
    val endAddress: String? = null,
)

/**
 * Complete route from origin to destination.
 */
data class Route(
    /** Unique identifier for this route */
    val id: String,
    /** Routing provider that generated this route */
    val provider: RoutingProvider,
    /** Route legs (one per segment between waypoints) */
    val legs: List<RouteLeg>,
    /** Complete polyline for the entire route */
    val polyline: List<RoutePoint>,
    /** Total distance in meters */
    val totalDistanceMeters: Double,
    /** Estimated total duration in seconds */
    val totalDurationSeconds: Double,
    /** Summary description (e.g., "via Highway 101") */
    val summary: String,
    /** Bounding box of the route [south, west, north, east] */
    val bounds: RouteBounds? = null,
    /** Timestamp when this route was calculated */
    val calculatedAtMillis: Long = System.currentTimeMillis(),
) {
    
    /**
     * Get all maneuvers across all legs.
     */
    val allManeuvers: List<Maneuver>
        get() = legs.flatMap { it.maneuvers }

    /**
     * Get human-readable duration string.
     */
    val durationText: String
        get() {
            val hours = (totalDurationSeconds / 3600).toInt()
            val minutes = ((totalDurationSeconds % 3600) / 60).toInt()
            return when {
                hours > 0 -> "${hours}h ${minutes}min"
                else -> "${minutes} min"
            }
        }

    /**
     * Get human-readable distance string.
     */
    val distanceText: String
        get() = when {
            totalDistanceMeters >= 1000 -> String.format("%.1f km", totalDistanceMeters / 1000)
            else -> String.format("%.0f m", totalDistanceMeters)
        }

    companion object {
        val EMPTY = Route(
            id = "",
            provider = RoutingProvider.NONE,
            legs = emptyList(),
            polyline = emptyList(),
            totalDistanceMeters = 0.0,
            totalDurationSeconds = 0.0,
            summary = "",
        )
    }
}

/**
 * Bounding box for a route.
 */
data class RouteBounds(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
)

/**
 * Routing provider types.
 */
enum class RoutingProvider {
    /** No provider (empty route) */
    NONE,
    /** Google Directions API */
    GOOGLE,
    /** OSRM (Open Source Routing Machine) */
    OSRM,
    /** Offline routing (e.g., GraphHopper with local data) */
    OFFLINE,
    /** Offline OSM routing */
    OFFLINE_OSM,
    /** Hybrid (automatic fallback) */
    HYBRID,
}

/**
 * Request parameters for route calculation.
 */
data class RouteRequest(
    /** Starting point */
    val origin: RoutePoint,
    /** Destination point */
    val destination: RoutePoint,
    /** Optional waypoints */
    val waypoints: List<RoutePoint> = emptyList(),
    /** Travel mode */
    val mode: TravelMode = TravelMode.DRIVING,
    /** Whether to request alternative routes */
    val alternatives: Boolean = false,
    /** Avoid options */
    val avoid: Set<RouteAvoidance> = emptySet(),
    /** Departure time (for traffic-aware routing) */
    val departureTimeMillis: Long? = null,
)

/**
 * Travel modes.
 */
enum class TravelMode {
    DRIVING,
    WALKING,
    CYCLING,
    TRANSIT,
}

/**
 * Things to avoid in routing.
 */
enum class RouteAvoidance {
    TOLLS,
    HIGHWAYS,
    FERRIES,
    INDOOR,
}

/**
 * Result of a route calculation.
 */
sealed class RouteResult {
    /** Successful route calculation */
    data class Success(
        val routes: List<Route>,
        val selectedIndex: Int = 0,
    ) : RouteResult() {
        val primaryRoute: Route
            get() = routes.getOrElse(selectedIndex) { routes.first() }
    }

    /** Route calculation failed */
    data class Error(
        val code: RouteErrorCode,
        val message: String,
    ) : RouteResult()
}

/**
 * Error codes for routing failures.
 */
enum class RouteErrorCode {
    /** No route found between points */
    NO_ROUTE,
    /** Network error */
    NETWORK_ERROR,
    /** API key invalid or missing */
    INVALID_API_KEY,
    /** Rate limit exceeded */
    RATE_LIMITED,
    /** Invalid request parameters */
    INVALID_REQUEST,
    /** Provider-specific error */
    PROVIDER_ERROR,
    /** Service not available */
    NOT_AVAILABLE,
    /** Unknown error */
    UNKNOWN,
}
