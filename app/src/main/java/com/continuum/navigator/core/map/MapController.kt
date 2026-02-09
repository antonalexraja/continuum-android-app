/**
 * Map Controller Interface
 *
 * Provides a common abstraction for map rendering regardless of the underlying
 * map provider (Google Maps, OSMDroid, etc.).
 *
 * ## Design Principles
 *
 * 1. **Engine-Driven**: All position updates come from NavigationOutput only
 * 2. **No Location APIs**: Map layer never uses Android Location APIs directly
 * 3. **Smooth Updates**: Marker positions should animate smoothly
 * 4. **Lifecycle-Aware**: Properly handles pause/resume
 *
 * ## Usage
 *
 * ```kotlin
 * val controller: MapController = GoogleMapController(googleMap)
 * controller.setFollowMode(true)
 * controller.updatePosition(navigationOutput)
 * ```
 */
package com.continuum.navigator.core.map

import android.view.View
import com.continuum.navigator.core.native.NavigationOutput
import kotlin.math.sqrt

/**
 * Map provider types.
 */
enum class MapProvider {
    /** Google Maps (requires internet and API key) */
    GOOGLE_MAPS,
    /** OSMDroid offline maps */
    OFFLINE_OSM,
}

/**
 * Position data for map updates.
 *
 * Extracted from NavigationOutput to decouple map layer from native types.
 */
data class MapPosition(
    /** Latitude in degrees */
    val latitude: Double,
    /** Longitude in degrees */
    val longitude: Double,
    /** Heading in degrees (0 = North, 90 = East) */
    val headingDegrees: Float,
    /** Horizontal accuracy in meters (for accuracy circle) */
    val accuracyMeters: Float,
    /** Speed in m/s */
    val speedMps: Float,
    /** Whether the position is valid */
    val isValid: Boolean,
) {
    companion object {
        /**
         * Create from NavigationOutput.
         */
        fun fromNavigationOutput(output: NavigationOutput): MapPosition {
            // Convert yaw (radians, NED convention) to heading degrees (0=North, clockwise)
            val headingRad = output.yawRad
            var headingDeg = Math.toDegrees(headingRad).toFloat()
            // Normalize to 0-360
            while (headingDeg < 0) headingDeg += 360f
            while (headingDeg >= 360) headingDeg -= 360f

            // Calculate speed from NED velocity
            val speedMps = sqrt(
                output.velocityNorthMps * output.velocityNorthMps +
                output.velocityEastMps * output.velocityEastMps
            ).toFloat()

            // Check if position is valid (non-zero coordinates and valid status)
            val isValid = output.latitudeDeg != 0.0 || output.longitudeDeg != 0.0

            return MapPosition(
                latitude = output.latitudeDeg,
                longitude = output.longitudeDeg,
                headingDegrees = headingDeg,
                accuracyMeters = output.positionStdM.toFloat(),
                speedMps = speedMps,
                isValid = isValid,
            )
        }

        /** Invalid/empty position */
        val INVALID = MapPosition(
            latitude = 0.0,
            longitude = 0.0,
            headingDegrees = 0f,
            accuracyMeters = 0f,
            speedMps = 0f,
            isValid = false,
        )
    }
}

/**
 * Map controller callback interface.
 */
interface MapControllerCallback {
    /** Called when the map is ready for use */
    fun onMapReady()

    /** Called when a map error occurs */
    fun onMapError(message: String)

    /** Called when user manually moves the map (disables follow mode) */
    fun onUserInteraction()
}

/**
 * Common interface for map controllers.
 *
 * Implementations:
 * - [GoogleMapController] - Google Maps SDK
 * - [OfflineMapController] - OSMDroid for offline maps
 */
interface MapController {

    /**
     * Get the provider type.
     */
    val provider: MapProvider

    /**
     * Get the underlying map view.
     */
    val mapView: View

    /**
     * Check if the map is ready for updates.
     */
    val isReady: Boolean

    /**
     * Set the callback for map events.
     */
    fun setCallback(callback: MapControllerCallback?)

    /**
     * Initialize the map.
     * Call this in onCreate/onViewCreated.
     */
    fun initialize()

    /**
     * Resume the map (call in onResume).
     */
    fun onResume()

    /**
     * Pause the map (call in onPause).
     */
    fun onPause()

    /**
     * Destroy the map (call in onDestroy).
     */
    fun onDestroy()

    /**
     * Update the current position marker.
     *
     * @param position The new position from NavigationOutput
     * @param animate Whether to animate the marker movement
     */
    fun updatePosition(position: MapPosition, animate: Boolean = true)

    /**
     * Set whether the map should follow the current position.
     *
     * When enabled, the map automatically centers on the position marker.
     *
     * @param follow True to enable follow mode
     */
    fun setFollowMode(follow: Boolean)

    /**
     * Check if follow mode is enabled.
     */
    fun isFollowModeEnabled(): Boolean

    /**
     * Set the map zoom level.
     *
     * @param zoom Zoom level (typically 1-20, higher = more zoomed in)
     */
    fun setZoom(zoom: Float)

    /**
     * Get the current zoom level.
     */
    fun getZoom(): Float

    /**
     * Get the last known position.
     */
    val lastPosition: MapPosition?

    /**
     * Center the map on a specific location.
     *
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @param animate Whether to animate the camera movement
     */
    fun centerOn(latitude: Double, longitude: Double, animate: Boolean = true)

    /**
     * Show or hide the accuracy circle.
     */
    fun setAccuracyCircleVisible(visible: Boolean)

    /**
     * Set the map type/style.
     * The interpretation depends on the implementation.
     *
     * @param type Map type identifier (implementation-specific)
     */
    fun setMapType(type: Int)

    /**
     * Get the current camera center position.
     * Returns the actual camera center, which may differ from lastPosition during animations.
     *
     * @return Pair of (latitude, longitude) or null if map not ready
     */
    fun getCameraCenter(): Pair<Double, Double>?

    /**
     * Get the current map rotation in degrees.
     * @return Rotation in degrees (0 = north up) or 0 if not supported
     */
    fun getCameraRotation(): Float = 0f

    /**
     * Set a listener for camera movement events.
     * Used by overlay to sync state whenever the camera moves.
     */
    fun setOnCameraMoveListener(listener: (() -> Unit)?)
}

/**
 * Base class for map controllers with common functionality.
 */
abstract class BaseMapController : MapController {

    protected var cameraMoveListener: (() -> Unit)? = null

    override fun setOnCameraMoveListener(listener: (() -> Unit)?) {
        cameraMoveListener = listener
    }

    private var _callback: MapControllerCallback? = null
    private var _isFollowing: Boolean = true
    protected var currentZoom: Float = 16f
    protected var showAccuracyCircle: Boolean = true
    protected var _lastPosition: MapPosition = MapPosition.INVALID

    override val lastPosition: MapPosition?
        get() = if (_lastPosition.isValid) _lastPosition else null

    override fun setCallback(callback: MapControllerCallback?) {
        this._callback = callback
    }

    protected val callback: MapControllerCallback?
        get() = _callback

    override fun setFollowMode(follow: Boolean) {
        _isFollowing = follow
    }

    protected val isFollowing: Boolean
        get() = _isFollowing

    override fun isFollowModeEnabled(): Boolean = _isFollowing

    override fun setZoom(zoom: Float) {
        currentZoom = zoom.coerceIn(1f, 21f)
    }

    override fun getZoom(): Float = currentZoom

    override fun setAccuracyCircleVisible(visible: Boolean) {
        showAccuracyCircle = visible
    }

    /**
     * Notify callback that user interacted with map.
     * Subclasses should call this when user pans/zooms manually.
     */
    protected fun notifyUserInteraction() {
        _isFollowing = false
        callback?.onUserInteraction()
    }
}
