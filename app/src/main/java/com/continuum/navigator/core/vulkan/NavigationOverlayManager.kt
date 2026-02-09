/**
 * Navigation Overlay Manager
 *
 * High-level manager for navigation overlays on map views.
 * Handles integration between NavigationOutput and overlay rendering.
 *
 * ## Features
 *
 * - Automatic map state synchronization
 * - Lifecycle-aware overlay management
 * - Performance monitoring and logging
 * - Configurable overlay appearance
 *
 * ## Integration
 *
 * ```kotlin
 * class MapFragment : Fragment() {
 *     private lateinit var overlayManager: NavigationOverlayManager
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         overlayManager = NavigationOverlayManager(requireContext())
 *         overlayManager.attachToContainer(mapContainer)
 *         overlayManager.setMapController(mapController)
 *     }
 *
 *     fun onNavigationUpdate(output: NavigationOutput) {
 *         overlayManager.updateNavigation(output)
 *     }
 * }
 * ```
 */
package com.continuum.navigator.core.vulkan

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.continuum.navigator.core.map.MapController
import com.continuum.navigator.core.native.NavigationOutput
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manager for navigation overlay rendering.
 */
class NavigationOverlayManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "NavOverlayManager"
        private const val STATS_LOG_INTERVAL_MS = 5000L  // Log stats every 5 seconds
    }

    // Configuration
    private var config = OverlayConfig()
    private var colors = OverlayColors()

    // Views
    private var overlayView: OverlaySurfaceView? = null
    private var container: ViewGroup? = null

    // Map integration
    private var mapController: MapController? = null

    // State
    private var isAttached = false
    private var isEnabled = true
    private var lastStatsLogTime = 0L

    // Listeners
    private val listeners = CopyOnWriteArrayList<OverlayManagerListener>()

    /**
     * Listener for overlay manager events.
     */
    interface OverlayManagerListener {
        fun onOverlayReady(usingVulkan: Boolean)
        fun onOverlayError(message: String)
        fun onFrameStats(fps: Float, frameTimeMs: Float, droppedFrames: Long)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Set overlay configuration before attaching.
     */
    fun setConfig(config: OverlayConfig) {
        this.config = config
        overlayView?.setConfig(config)
    }

    /**
     * Set overlay colors.
     */
    fun setColors(colors: OverlayColors) {
        this.colors = colors
        overlayView?.setColors(colors)
    }

    /**
     * Enable or disable overlay rendering.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        overlayView?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /**
     * Force Canvas fallback for testing.
     */
    fun forceCanvasFallback(force: Boolean) {
        overlayView?.forceCanvasFallback(force)
    }

    /**
     * Add listener.
     */
    fun addListener(listener: OverlayManagerListener) {
        listeners.add(listener)
    }

    /**
     * Remove listener.
     */
    fun removeListener(listener: OverlayManagerListener) {
        listeners.remove(listener)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Attachment
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Attach overlay to a container view.
     * The overlay will render on top of other views in the container.
     */
    fun attachToContainer(container: ViewGroup) {
        Log.d(TAG, "attachToContainer called, isAttached=$isAttached")
        if (isAttached) {
            Log.w(TAG, "Already attached, detaching first")
            detach()
        }

        this.container = container
        Log.d(TAG, "Container: w=${container.width}, h=${container.height}, childCount=${container.childCount}")

        // Create overlay view
        overlayView = OverlaySurfaceView(context).apply {
            setConfig(this@NavigationOverlayManager.config)
            setColors(this@NavigationOverlayManager.colors)
            setOverlayListener(overlayListener)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Add on top
        container.addView(overlayView)
        isAttached = true

        Log.d(TAG, "Attached overlay to container, overlayView=${overlayView != null}, visibility=${overlayView?.visibility}")
    }

    /**
     * Detach overlay from container.
     */
    fun detach() {
        overlayView?.let { view ->
            container?.removeView(view)
        }
        overlayView = null
        container = null
        isAttached = false

        Log.d(TAG, "Detached overlay")
    }

    /**
     * Check if overlay is attached.
     */
    fun isAttached(): Boolean = isAttached

    // ═══════════════════════════════════════════════════════════════════════
    // Map Integration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Set map controller for state synchronization.
     */
    fun setMapController(controller: MapController) {
        // Remove listener from previous controller
        mapController?.setOnCameraMoveListener(null)
        
        mapController = controller
        
        // Register camera move listener for real-time sync
        controller.setOnCameraMoveListener {
            syncMapState()
        }
        
        syncMapState()
    }

    /**
     * Sync map state from controller.
     * Call this when the map view changes (zoom, pan, rotation).
     */
    fun syncMapState() {
        val controller = mapController ?: return

        val mapState = buildMapState(controller)
        overlayView?.updateMapState(mapState)
    }

    private fun buildMapState(controller: MapController): MapViewState {
        val zoom = controller.getZoom()
        
        // Use actual camera center, NOT lastPosition
        // This is critical: camera may be animating or user may have panned
        val cameraCenter = controller.getCameraCenter()
        val centerLat = cameraCenter?.first ?: controller.lastPosition?.latitude ?: 0.0
        val centerLon = cameraCenter?.second ?: controller.lastPosition?.longitude ?: 0.0
        
        // Get camera rotation
        val rotation = controller.getCameraRotation()

        // Calculate meters per pixel using proper formula
        val metersPerPixel = MapViewState.calculateMetersPerPixel(centerLat, zoom)

        return MapViewState(
            centerLatitude = centerLat,
            centerLongitude = centerLon,
            zoomLevel = zoom,
            rotation = rotation,
            viewWidth = overlayView?.width ?: 0,
            viewHeight = overlayView?.height ?: 0,
            metersPerPixel = metersPerPixel,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Navigation Updates
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Update overlay with new navigation output (ESKF filtered).
     */
    fun updateNavigation(output: NavigationOutput) {
        if (!isEnabled || !isAttached) return

        overlayView?.updateNavigationOutput(output)

        // Log stats periodically
        val now = System.currentTimeMillis()
        if (now - lastStatsLogTime > STATS_LOG_INTERVAL_MS) {
            logFrameStats()
            lastStatsLogTime = now
        }
    }

    /**
     * Update overlay with raw GNSS position (unfiltered).
     * This builds a separate trajectory line for visualization.
     */
    fun updateRawGnss(latitude: Double, longitude: Double, timestampNs: Long, accuracyM: Float) {
        if (!isEnabled || !isAttached) return
        overlayView?.updateRawGnss(latitude, longitude, timestampNs, accuracyM)
    }

    /**
     * Clear trajectory history.
     */
    fun clearTrajectory() {
        overlayView?.clearTrajectory()
    }

    private fun logFrameStats() {
        val stats = overlayView?.getFrameStats() ?: return

        Log.d(
            TAG,
            "Frame stats: fps=${String.format("%.1f", stats.averageFps)}, " +
                "frameTime=${String.format("%.2f", stats.averageFrameTimeMs)}ms, " +
                "max=${String.format("%.2f", stats.maxFrameTimeMs)}ms, " +
                "dropped=${stats.droppedFrames}"
        )

        listeners.forEach { listener ->
            listener.onFrameStats(
                stats.averageFps,
                stats.averageFrameTimeMs,
                stats.droppedFrames,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resume rendering. Call from Activity/Fragment onResume.
     */
    fun onResume() {
        overlayView?.onResume()
    }

    /**
     * Pause rendering. Call from Activity/Fragment onPause.
     */
    fun onPause() {
        overlayView?.onPause()
    }

    /**
     * Release all resources. Call from Activity/Fragment onDestroy.
     */
    fun release() {
        detach()
        mapController = null
        listeners.clear()
    }

    /**
     * Get current frame statistics.
     */
    fun getFrameStats(): FrameStats {
        return overlayView?.getFrameStats() ?: FrameStats()
    }

    /**
     * Check if using Vulkan renderer.
     */
    fun isUsingVulkan(): Boolean {
        return overlayView?.isUsingVulkan() == true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Listener
    // ═══════════════════════════════════════════════════════════════════════

    private val overlayListener = object : OverlaySurfaceView.OverlayListener {
        override fun onRendererInitialized(usingVulkan: Boolean) {
            Log.i(TAG, "Renderer initialized: ${if (usingVulkan) "Vulkan" else "Canvas"}")
            listeners.forEach { it.onOverlayReady(usingVulkan) }
        }

        override fun onRenderError(error: String) {
            Log.e(TAG, "Render error: $error")
            listeners.forEach { it.onOverlayError(error) }
        }

        override fun onFrameStats(stats: FrameStats) {
            listeners.forEach { listener ->
                listener.onFrameStats(
                    stats.averageFps,
                    stats.averageFrameTimeMs,
                    stats.droppedFrames,
                )
            }
        }
    }
}
