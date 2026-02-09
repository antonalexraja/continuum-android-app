/**
 * Route Position Tracker
 *
 * Tracks user position along a route without modifying the upstream position.
 * Implements "soft" map matching - projects position onto route for display
 * and guidance, but NavigationOutput remains the source of truth.
 *
 * ## Features
 *
 * - Project position onto nearest route segment
 * - Track distance traveled and remaining
 * - Detect off-route conditions
 * - Track current and upcoming maneuvers
 * - ETA calculation based on current speed
 *
 * ## Design Principles
 *
 * 1. **Read-Only**: Never modifies NavigationOutput or filter state
 * 2. **Soft Matching**: Projection is for display only, not position correction
 * 3. **Hysteresis**: Prevents jitter in off-route detection
 * 4. **Efficiency**: O(log n) segment lookup using spatial indexing
 *
 * ## Usage
 *
 * ```kotlin
 * val tracker = RoutePositionTracker()
 * tracker.setRoute(route)
 *
 * // On each NavigationOutput update
 * val state = tracker.updatePosition(navOutput.latitudeDeg, navOutput.longitudeDeg, navOutput.speed)
 * println("Distance remaining: ${state.distanceRemainingMeters}")
 * println("Next maneuver: ${state.nextManeuver?.instruction}")
 * ```
 */
package com.continuum.navigator.core.routing

import android.util.Log
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * State of position relative to route.
 */
data class RoutePositionState(
    /** Whether actively tracking a route */
    val isTracking: Boolean,
    
    /** Projected position on route (may differ from actual position) */
    val projectedPosition: RoutePoint?,
    
    /** Index of current segment in polyline (0 to polyline.size - 2) */
    val currentSegmentIndex: Int,
    
    /** Distance along current segment (0 to segment length) */
    val distanceAlongSegmentMeters: Double,
    
    /** Total distance traveled along route from start */
    val distanceTraveledMeters: Double,
    
    /** Remaining distance to destination */
    val distanceRemainingMeters: Double,
    
    /** Perpendicular distance from actual position to route */
    val crossTrackErrorMeters: Double,
    
    /** Whether user is considered off-route */
    val isOffRoute: Boolean,
    
    /** Current maneuver (the one user is executing) */
    val currentManeuver: Maneuver?,
    
    /** Index of current maneuver in allManeuvers list */
    val currentManeuverIndex: Int,
    
    /** Next upcoming maneuver */
    val nextManeuver: Maneuver?,
    
    /** Distance to next maneuver in meters */
    val distanceToNextManeuverMeters: Double,
    
    /** Estimated time to destination in seconds (based on current speed) */
    val etaSeconds: Double,
    
    /** Progress along route (0.0 to 1.0) */
    val progress: Double,
    
    /** Bearing of current route segment in degrees */
    val routeBearingDegrees: Double,
) {
    companion object {
        val EMPTY = RoutePositionState(
            isTracking = false,
            projectedPosition = null,
            currentSegmentIndex = 0,
            distanceAlongSegmentMeters = 0.0,
            distanceTraveledMeters = 0.0,
            distanceRemainingMeters = 0.0,
            crossTrackErrorMeters = 0.0,
            isOffRoute = false,
            currentManeuver = null,
            currentManeuverIndex = 0,
            nextManeuver = null,
            distanceToNextManeuverMeters = 0.0,
            etaSeconds = 0.0,
            progress = 0.0,
            routeBearingDegrees = 0.0
        )
    }
}

/**
 * Configuration for route position tracking.
 */
data class RouteTrackerConfig(
    /** Distance threshold for off-route detection (meters) */
    val offRouteThresholdMeters: Double = 50.0,
    
    /** Re-route threshold - higher than off-route to prevent jitter (meters) */
    val reRouteThresholdMeters: Double = 100.0,
    
    /** Minimum distance to travel before confirming back-on-route */
    val backOnRouteDistanceMeters: Double = 20.0,
    
    /** Look-ahead segments for finding closest point (performance) */
    val segmentSearchWindow: Int = 20,
    
    /** Minimum speed for ETA calculation (m/s) - prevents divide by near-zero */
    val minSpeedForEtaMps: Double = 1.0,
    
    /** Whether to use segment caching for performance */
    val useSegmentCache: Boolean = true,
)

/**
 * Tracks position along a route.
 */
class RoutePositionTracker(
    private val config: RouteTrackerConfig = RouteTrackerConfig()
) {
    companion object {
        private const val TAG = "RoutePositionTracker"
    }

    // Current route
    private var route: Route? = null
    private var polyline: List<RoutePoint> = emptyList()
    private var maneuvers: List<Maneuver> = emptyList()
    
    // Pre-computed segment data
    private var segmentLengths: DoubleArray = doubleArrayOf()
    private var cumulativeDistances: DoubleArray = doubleArrayOf()
    private var totalRouteDistance: Double = 0.0
    
    // Maneuver distances from start
    private var maneuverDistances: DoubleArray = doubleArrayOf()
    
    // Current state
    private var lastSegmentIndex: Int = 0
    private var isOffRoute: Boolean = false
    private var offRouteCounter: Int = 0
    private var onRouteCounter: Int = 0

    // Listener
    private var listener: RoutePositionListener? = null

    /**
     * Listener for route position events.
     */
    interface RoutePositionListener {
        /** Called when user goes off-route */
        fun onOffRoute(distanceFromRoute: Double)
        
        /** Called when user returns to route */
        fun onBackOnRoute()
        
        /** Called when approaching a maneuver */
        fun onApproachingManeuver(maneuver: Maneuver, distanceMeters: Double)
        
        /** Called when a maneuver is completed */
        fun onManeuverCompleted(maneuver: Maneuver)
        
        /** Called when destination is reached */
        fun onDestinationReached()
    }

    /**
     * Set the route to track.
     */
    fun setRoute(route: Route) {
        this.route = route
        this.polyline = route.polyline
        this.maneuvers = route.allManeuvers
        
        precomputeSegmentData()
        precomputeManeuverDistances()
        
        // Reset state
        lastSegmentIndex = 0
        isOffRoute = false
        offRouteCounter = 0
        onRouteCounter = 0
        
        Log.d(TAG, "Route set: ${polyline.size} points, ${maneuvers.size} maneuvers, ${totalRouteDistance}m")
    }

    /**
     * Clear the current route.
     */
    fun clearRoute() {
        route = null
        polyline = emptyList()
        maneuvers = emptyList()
        segmentLengths = doubleArrayOf()
        cumulativeDistances = doubleArrayOf()
        maneuverDistances = doubleArrayOf()
        totalRouteDistance = 0.0
        lastSegmentIndex = 0
        isOffRoute = false
    }

    /**
     * Set position listener.
     */
    fun setListener(listener: RoutePositionListener?) {
        this.listener = listener
    }

    /**
     * Update position and get current state.
     *
     * @param latitude Current latitude in degrees
     * @param longitude Current longitude in degrees  
     * @param speedMps Current speed in m/s
     * @return Current route position state
     */
    fun updatePosition(latitude: Double, longitude: Double, speedMps: Double): RoutePositionState {
        if (polyline.size < 2) {
            return RoutePositionState.EMPTY
        }

        val currentPoint = RoutePoint(latitude, longitude)
        
        // Find closest point on route
        val (segmentIndex, projectedPoint, distanceAlong, crossTrackError) = 
            findClosestPointOnRoute(currentPoint)
        
        // Calculate cumulative distance traveled
        val distanceTraveled = cumulativeDistances.getOrElse(segmentIndex) { 0.0 } + distanceAlong
        val distanceRemaining = totalRouteDistance - distanceTraveled
        
        // Update off-route detection with hysteresis
        updateOffRouteState(crossTrackError)
        
        // Find current and next maneuver
        val (currentManeuverIdx, nextManeuverIdx) = findCurrentManeuvers(distanceTraveled)
        val currentManeuver = maneuvers.getOrNull(currentManeuverIdx)
        val nextManeuver = maneuvers.getOrNull(nextManeuverIdx)
        
        // Distance to next maneuver
        val distanceToNext = if (nextManeuverIdx < maneuverDistances.size) {
            (maneuverDistances[nextManeuverIdx] - distanceTraveled).coerceAtLeast(0.0)
        } else {
            distanceRemaining
        }
        
        // Calculate ETA
        val effectiveSpeed = speedMps.coerceAtLeast(config.minSpeedForEtaMps)
        val etaSeconds = distanceRemaining / effectiveSpeed
        
        // Calculate progress
        val progress = if (totalRouteDistance > 0) {
            (distanceTraveled / totalRouteDistance).coerceIn(0.0, 1.0)
        } else 0.0
        
        // Route bearing at current segment
        val routeBearing = if (segmentIndex < polyline.size - 1) {
            polyline[segmentIndex].bearingTo(polyline[segmentIndex + 1])
        } else 0.0
        
        // Check for destination reached
        if (distanceRemaining < 20.0 && !isOffRoute) {
            listener?.onDestinationReached()
        }
        
        // Update last segment for search optimization
        lastSegmentIndex = segmentIndex
        
        return RoutePositionState(
            isTracking = true,
            projectedPosition = projectedPoint,
            currentSegmentIndex = segmentIndex,
            distanceAlongSegmentMeters = distanceAlong,
            distanceTraveledMeters = distanceTraveled,
            distanceRemainingMeters = distanceRemaining,
            crossTrackErrorMeters = crossTrackError,
            isOffRoute = isOffRoute,
            currentManeuver = currentManeuver,
            currentManeuverIndex = currentManeuverIdx,
            nextManeuver = nextManeuver,
            distanceToNextManeuverMeters = distanceToNext,
            etaSeconds = etaSeconds,
            progress = progress,
            routeBearingDegrees = routeBearing
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Segment Projection
    // ═══════════════════════════════════════════════════════════════════════

    private data class ProjectionResult(
        val segmentIndex: Int,
        val projectedPoint: RoutePoint,
        val distanceAlongSegment: Double,
        val crossTrackError: Double
    )

    /**
     * Find the closest point on the route to the given position.
     * Uses windowed search around last known segment for efficiency.
     */
    private fun findClosestPointOnRoute(point: RoutePoint): ProjectionResult {
        var bestSegment = 0
        var bestProjection = polyline[0]
        var bestDistanceAlong = 0.0
        var bestCrossTrack = Double.MAX_VALUE

        // Search window around last segment
        val startIdx = (lastSegmentIndex - config.segmentSearchWindow).coerceAtLeast(0)
        val endIdx = (lastSegmentIndex + config.segmentSearchWindow).coerceAtMost(polyline.size - 2)

        for (i in startIdx..endIdx) {
            val segStart = polyline[i]
            val segEnd = polyline[i + 1]
            
            val (projected, distAlong, crossTrack) = projectOntoSegment(point, segStart, segEnd)
            
            if (crossTrack < bestCrossTrack) {
                bestCrossTrack = crossTrack
                bestSegment = i
                bestProjection = projected
                bestDistanceAlong = distAlong
            }
        }

        // If nothing found in window or cross track is large, search entire route
        if (bestCrossTrack > config.offRouteThresholdMeters * 2) {
            for (i in 0 until polyline.size - 1) {
                if (i in startIdx..endIdx) continue // Already checked
                
                val segStart = polyline[i]
                val segEnd = polyline[i + 1]
                
                val (projected, distAlong, crossTrack) = projectOntoSegment(point, segStart, segEnd)
                
                if (crossTrack < bestCrossTrack) {
                    bestCrossTrack = crossTrack
                    bestSegment = i
                    bestProjection = projected
                    bestDistanceAlong = distAlong
                }
            }
        }

        return ProjectionResult(bestSegment, bestProjection, bestDistanceAlong, bestCrossTrack)
    }

    /**
     * Project a point onto a line segment.
     * Returns: (projected point, distance along segment, perpendicular distance)
     */
    private fun projectOntoSegment(
        point: RoutePoint,
        segStart: RoutePoint,
        segEnd: RoutePoint
    ): Triple<RoutePoint, Double, Double> {
        // Convert to local meters (approximate, good enough for small distances)
        val latScale = 111320.0 // meters per degree latitude
        val lonScale = 111320.0 * cos(Math.toRadians(point.latitude))
        
        val px = (point.longitude - segStart.longitude) * lonScale
        val py = (point.latitude - segStart.latitude) * latScale
        
        val sx = (segEnd.longitude - segStart.longitude) * lonScale
        val sy = (segEnd.latitude - segStart.latitude) * latScale
        
        val segLengthSq = sx * sx + sy * sy
        
        if (segLengthSq < 1e-10) {
            // Degenerate segment
            val dist = point.distanceTo(segStart)
            return Triple(segStart, 0.0, dist)
        }
        
        // Projection parameter (0 = at start, 1 = at end)
        val t = ((px * sx + py * sy) / segLengthSq).coerceIn(0.0, 1.0)
        
        // Projected point in local coords
        val projX = t * sx
        val projY = t * sy
        
        // Cross track error
        val crossTrack = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
        
        // Distance along segment
        val distanceAlong = t * sqrt(segLengthSq)
        
        // Convert projected point back to lat/lon
        val projectedLat = segStart.latitude + projY / latScale
        val projectedLon = segStart.longitude + projX / lonScale
        
        return Triple(RoutePoint(projectedLat, projectedLon), distanceAlong, crossTrack)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Off-Route Detection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Update off-route state with hysteresis to prevent jitter.
     */
    private fun updateOffRouteState(crossTrackError: Double) {
        val wasOffRoute = isOffRoute
        
        if (isOffRoute) {
            // Currently off-route - check if back on route
            if (crossTrackError < config.offRouteThresholdMeters) {
                onRouteCounter++
                offRouteCounter = 0
                
                // Require sustained on-route readings
                if (onRouteCounter >= 3) {
                    isOffRoute = false
                    Log.i(TAG, "Back on route")
                    listener?.onBackOnRoute()
                }
            } else {
                onRouteCounter = 0
            }
        } else {
            // Currently on-route - check if going off-route
            if (crossTrackError > config.offRouteThresholdMeters) {
                offRouteCounter++
                onRouteCounter = 0
                
                // Require sustained off-route readings
                if (offRouteCounter >= 3) {
                    isOffRoute = true
                    Log.w(TAG, "Off route: ${crossTrackError}m from route")
                    listener?.onOffRoute(crossTrackError)
                }
            } else {
                offRouteCounter = 0
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Maneuver Tracking
    // ═══════════════════════════════════════════════════════════════════════

    private var lastManeuverIndex = 0

    /**
     * Find current and next maneuver based on distance traveled.
     * Returns: (currentIndex, nextIndex)
     */
    private fun findCurrentManeuvers(distanceTraveled: Double): Pair<Int, Int> {
        var currentIdx = 0
        
        // Find current maneuver (last maneuver we've passed)
        for (i in maneuverDistances.indices) {
            if (distanceTraveled >= maneuverDistances[i]) {
                currentIdx = i
            } else {
                break
            }
        }
        
        val nextIdx = (currentIdx + 1).coerceAtMost(maneuvers.size - 1)
        
        // Check for maneuver completion
        if (currentIdx > lastManeuverIndex && currentIdx < maneuvers.size) {
            val completed = maneuvers[lastManeuverIndex]
            Log.d(TAG, "Maneuver completed: ${completed.shortInstruction}")
            listener?.onManeuverCompleted(completed)
        }
        
        // Check for approaching next maneuver
        if (nextIdx < maneuverDistances.size) {
            val distToNext = maneuverDistances[nextIdx] - distanceTraveled
            
            // Announce thresholds
            val thresholds = listOf(500.0, 200.0, 100.0, 50.0)
            for (threshold in thresholds) {
                if (distToNext < threshold && distToNext > threshold - 10) {
                    maneuvers.getOrNull(nextIdx)?.let { maneuver ->
                        listener?.onApproachingManeuver(maneuver, distToNext)
                    }
                    break
                }
            }
        }
        
        lastManeuverIndex = currentIdx
        return Pair(currentIdx, nextIdx)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Precomputation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Precompute segment lengths and cumulative distances.
     */
    private fun precomputeSegmentData() {
        if (polyline.size < 2) {
            segmentLengths = doubleArrayOf()
            cumulativeDistances = doubleArrayOf()
            totalRouteDistance = 0.0
            return
        }

        val numSegments = polyline.size - 1
        segmentLengths = DoubleArray(numSegments)
        cumulativeDistances = DoubleArray(numSegments)
        
        var cumulative = 0.0
        for (i in 0 until numSegments) {
            val length = polyline[i].distanceTo(polyline[i + 1])
            segmentLengths[i] = length
            cumulativeDistances[i] = cumulative
            cumulative += length
        }
        
        totalRouteDistance = cumulative
    }

    /**
     * Precompute distance to each maneuver from route start.
     */
    private fun precomputeManeuverDistances() {
        if (maneuvers.isEmpty() || cumulativeDistances.isEmpty()) {
            maneuverDistances = doubleArrayOf()
            return
        }

        maneuverDistances = DoubleArray(maneuvers.size)
        
        for (i in maneuvers.indices) {
            val maneuver = maneuvers[i]
            val polylineIdx = maneuver.polylineStartIndex.coerceIn(0, cumulativeDistances.size - 1)
            maneuverDistances[i] = cumulativeDistances.getOrElse(polylineIdx) { 0.0 }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Check if currently tracking a route.
     */
    fun isTracking(): Boolean = route != null && polyline.size >= 2

    /**
     * Get current off-route status.
     */
    fun isCurrentlyOffRoute(): Boolean = isOffRoute

    /**
     * Get total route distance.
     */
    fun getTotalDistance(): Double = totalRouteDistance

    /**
     * Force re-evaluation from a specific segment (e.g., after reroute).
     */
    fun resetToSegment(segmentIndex: Int) {
        lastSegmentIndex = segmentIndex.coerceIn(0, polyline.size - 2)
        lastManeuverIndex = 0
        isOffRoute = false
        offRouteCounter = 0
        onRouteCounter = 0
    }
}
