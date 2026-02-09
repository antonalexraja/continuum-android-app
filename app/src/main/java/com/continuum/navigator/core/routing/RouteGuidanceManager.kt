/**
 * Route Guidance Manager
 *
 * Integrates route calculation, position tracking, and guidance state.
 * Acts as the primary interface for route-guided navigation.
 *
 * ## Responsibilities
 *
 * - Coordinate RoutingService (route calculation) with RoutePositionTracker (map matching)
 * - Provide unified guidance state via LiveData
 * - Handle rerouting when off-route
 * - Track arrival and route completion
 *
 * ## Thread Safety
 *
 * - Guidance state updates happen on main thread via LiveData
 * - Position updates can come from any thread
 *
 * ## Usage
 *
 * ```kotlin
 * val manager = RouteGuidanceManager(context)
 * manager.guidanceState.observe(this) { state ->
 *     updateUI(state)
 * }
 * 
 * manager.startNavigation(origin, destination)
 * 
 * // On each position update
 * manager.updatePosition(lat, lon, speed)
 * ```
 */
package com.continuum.navigator.core.routing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Current state of route guidance.
 */
sealed class GuidanceState {
    /** No active navigation */
    object Idle : GuidanceState()
    
    /** Calculating initial route */
    data class CalculatingRoute(val origin: RoutePoint, val destination: RoutePoint) : GuidanceState()
    
    /** Route calculation failed */
    data class RouteFailed(val error: String) : GuidanceState()
    
    /** Active navigation */
    data class Navigating(
        val route: Route,
        val positionState: RoutePositionState,
        val mode: RoutingMode
    ) : GuidanceState() {
        // Convenience accessors
        val distanceRemaining get() = positionState.distanceRemainingMeters
        val etaSeconds get() = positionState.etaSeconds
        val isOffRoute get() = positionState.isOffRoute
        val nextManeuver get() = positionState.nextManeuver
        val distanceToNextManeuver get() = positionState.distanceToNextManeuverMeters
        val progress get() = positionState.progress
    }
    
    /** Recalculating route (user went off-route) */
    data class Rerouting(val currentPosition: RoutePoint) : GuidanceState()
    
    /** Arrived at destination */
    data class Arrived(val route: Route, val totalTimeSeconds: Double) : GuidanceState()
}

/**
 * Configuration for route guidance.
 */
data class GuidanceConfig(
    /** Auto-reroute when off-route */
    val autoReroute: Boolean = true,
    
    /** Delay before auto-rerouting (ms) */
    val rerouteDelayMs: Long = 3000,
    
    /** Distance from destination to trigger arrival (meters) */
    val arrivalThresholdMeters: Double = 30.0,
    
    /** Position tracker configuration */
    val trackerConfig: RouteTrackerConfig = RouteTrackerConfig()
)

/**
 * Manages route guidance.
 */
class RouteGuidanceManager(
    context: Context,
    private val config: GuidanceConfig = GuidanceConfig()
) : RoutePositionTracker.RoutePositionListener {

    companion object {
        private const val TAG = "RouteGuidanceManager"
    }

    // Services
    private val routingService = RoutingService(context)
    private val positionTracker = RoutePositionTracker(config.trackerConfig)
    
    // State
    private val _guidanceState = MutableLiveData<GuidanceState>(GuidanceState.Idle)
    val guidanceState: LiveData<GuidanceState> = _guidanceState
    
    // Current navigation state
    private var currentRoute: Route? = null
    private var destination: RoutePoint? = null
    private var origin: RoutePoint? = null
    private var navigationStartTime: Long = 0
    
    // Rerouting
    private var pendingReroute = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Listener for external guidance events
    private var listener: GuidanceListener? = null

    /**
     * Listener for guidance events.
     */
    interface GuidanceListener {
        /** Called when navigation starts */
        fun onNavigationStarted(route: Route)
        
        /** Called when approaching a maneuver */
        fun onManeuverApproaching(maneuver: Maneuver, distanceMeters: Double)
        
        /** Called when a maneuver is passed */
        fun onManeuverPassed(maneuver: Maneuver)
        
        /** Called when rerouting starts */
        fun onRerouting()
        
        /** Called when reroute completes */
        fun onRerouteComplete(newRoute: Route)
        
        /** Called when arrived at destination */
        fun onArrived()
    }

    init {
        positionTracker.setListener(this)
        
        // Observe routing state for route updates
        routingService.routingState.observeForever { state ->
            handleRoutingState(state)
        }
    }

    /**
     * Set guidance listener.
     */
    fun setListener(listener: GuidanceListener?) {
        this.listener = listener
    }

    /**
     * Get current routing mode.
     */
    fun getRoutingMode(): RoutingMode = routingService.getMode()

    /**
     * Set routing mode.
     */
    fun setRoutingMode(mode: RoutingMode) {
        routingService.setMode(mode)
    }

    /**
     * Start navigation from origin to destination.
     */
    fun startNavigation(origin: RoutePoint, destination: RoutePoint) {
        this.origin = origin
        this.destination = destination
        
        Log.i(TAG, "Starting navigation: $origin -> $destination")
        
        _guidanceState.postValue(GuidanceState.CalculatingRoute(origin, destination))
        
        navigationStartTime = System.currentTimeMillis()
        scope.launch {
            routingService.calculateRoute(origin, destination)
        }
    }

    /**
     * Start navigation with waypoints.
     */
    fun startNavigation(
        origin: RoutePoint,
        destination: RoutePoint,
        waypoints: List<RoutePoint>
    ) {
        this.origin = origin
        this.destination = destination
        
        Log.i(TAG, "Starting navigation with ${waypoints.size} waypoints")
        
        _guidanceState.postValue(GuidanceState.CalculatingRoute(origin, destination))
        
        navigationStartTime = System.currentTimeMillis()
        scope.launch {
            routingService.calculateRoute(origin, destination, waypoints)
        }
    }

    /**
     * Stop navigation.
     */
    fun stopNavigation() {
        Log.i(TAG, "Stopping navigation")
        
        pendingReroute = false
        
        currentRoute = null
        destination = null
        origin = null
        
        positionTracker.clearRoute()
        _guidanceState.postValue(GuidanceState.Idle)
    }

    /**
     * Update current position.
     * Call this with each NavigationOutput update.
     */
    fun updatePosition(latitude: Double, longitude: Double, speedMps: Double) {
        val route = currentRoute ?: return
        
        val positionState = positionTracker.updatePosition(latitude, longitude, speedMps)
        
        // Check for arrival
        if (positionState.distanceRemainingMeters < config.arrivalThresholdMeters && 
            !positionState.isOffRoute) {
            handleArrival(route)
            return
        }
        
        // Update guidance state
        val currentState = _guidanceState.value
        if (currentState is GuidanceState.Navigating || currentState is GuidanceState.Rerouting) {
            _guidanceState.postValue(
                GuidanceState.Navigating(
                    route = route,
                    positionState = positionState,
                    mode = routingService.getMode()
                )
            )
        }
    }

    /**
     * Force a reroute from current position.
     */
    fun reroute(currentPosition: RoutePoint) {
        val dest = destination ?: return
        
        Log.i(TAG, "Manual reroute requested")
        
        _guidanceState.postValue(GuidanceState.Rerouting(currentPosition))
        listener?.onRerouting()
        
        scope.launch {
            routingService.calculateRoute(currentPosition, dest)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RoutePositionListener Implementation
    // ═══════════════════════════════════════════════════════════════════════

    override fun onOffRoute(distanceFromRoute: Double) {
        Log.w(TAG, "Off route: ${distanceFromRoute}m")
        
        if (config.autoReroute && !pendingReroute) {
            pendingReroute = true
            
            // Delay reroute to allow user to return to route
            mainHandler.postDelayed({
                if (pendingReroute && positionTracker.isCurrentlyOffRoute()) {
                    performAutoReroute()
                }
                pendingReroute = false
            }, config.rerouteDelayMs)
        }
    }

    override fun onBackOnRoute() {
        Log.i(TAG, "Back on route")
        pendingReroute = false
    }

    override fun onApproachingManeuver(maneuver: Maneuver, distanceMeters: Double) {
        Log.d(TAG, "Approaching maneuver: ${maneuver.shortInstruction} in ${distanceMeters}m")
        listener?.onManeuverApproaching(maneuver, distanceMeters)
    }

    override fun onManeuverCompleted(maneuver: Maneuver) {
        Log.d(TAG, "Maneuver completed: ${maneuver.shortInstruction}")
        listener?.onManeuverPassed(maneuver)
    }

    override fun onDestinationReached() {
        currentRoute?.let { handleArrival(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private Methods
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleRoutingState(state: RoutingState) {
        when (state) {
            is RoutingState.Idle -> { /* No action */ }
            
            is RoutingState.Calculating -> {
                // Already showing CalculatingRoute or Rerouting
            }
            
            is RoutingState.Success -> {
                handleRouteReceived(state.result.primaryRoute)
            }
            
            is RoutingState.Error -> {
                Log.e(TAG, "Route calculation failed: ${state.error.message}")
                _guidanceState.postValue(GuidanceState.RouteFailed(state.error.message))
            }
        }
    }

    private fun handleRouteReceived(route: Route) {
        val wasRerouting = _guidanceState.value is GuidanceState.Rerouting
        
        currentRoute = route
        positionTracker.setRoute(route)
        
        Log.i(TAG, "Route received: ${route.totalDistanceMeters}m, ${route.totalDurationSeconds}s")
        
        if (wasRerouting) {
            listener?.onRerouteComplete(route)
        } else {
            listener?.onNavigationStarted(route)
        }
        
        // Initial position state (will be updated with actual position)
        val initialState = RoutePositionState(
            isTracking = true,
            projectedPosition = route.polyline.firstOrNull(),
            currentSegmentIndex = 0,
            distanceAlongSegmentMeters = 0.0,
            distanceTraveledMeters = 0.0,
            distanceRemainingMeters = route.totalDistanceMeters,
            crossTrackErrorMeters = 0.0,
            isOffRoute = false,
            currentManeuver = route.allManeuvers.firstOrNull(),
            currentManeuverIndex = 0,
            nextManeuver = route.allManeuvers.getOrNull(1),
            distanceToNextManeuverMeters = route.allManeuvers.getOrNull(1)?.distanceMeters ?: route.totalDistanceMeters,
            etaSeconds = route.totalDurationSeconds,
            progress = 0.0,
            routeBearingDegrees = 0.0
        )
        
        _guidanceState.postValue(
            GuidanceState.Navigating(
                route = route,
                positionState = initialState,
                mode = routingService.getMode()
            )
        )
    }

    private fun handleArrival(route: Route) {
        Log.i(TAG, "Destination reached!")
        
        val totalTime = (System.currentTimeMillis() - navigationStartTime) / 1000.0
        
        pendingReroute = false
        
        _guidanceState.postValue(GuidanceState.Arrived(route, totalTime))
        listener?.onArrived()
        
        // Don't clear state immediately - let UI handle
    }

    private fun performAutoReroute() {
        val dest = destination ?: return
        val current = _guidanceState.value
        
        // Get current position from state
        val currentPos = when (current) {
            is GuidanceState.Navigating -> {
                current.positionState.projectedPosition ?: return
            }
            else -> return
        }
        
        Log.i(TAG, "Auto-rerouting from $currentPos")
        
        reroute(RoutePoint(currentPos.latitude, currentPos.longitude))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Check if navigation is active.
     */
    fun isNavigating(): Boolean {
        return when (_guidanceState.value) {
            is GuidanceState.Navigating, is GuidanceState.Rerouting -> true
            else -> false
        }
    }

    /**
     * Get current route (if any).
     */
    fun getCurrentRoute(): Route? = currentRoute

    /**
     * Get the route renderer for drawing on map.
     */
    fun getRouteRenderer(): RouteRenderer = RouteRenderer()

    /**
     * Get position tracker for advanced use.
     */
    fun getPositionTracker(): RoutePositionTracker = positionTracker
}
