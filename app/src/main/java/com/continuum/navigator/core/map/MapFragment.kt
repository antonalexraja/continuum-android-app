/**
 * Map Fragment
 *
 * Fragment that hosts the map view and allows runtime switching between
 * Google Maps (online) and OSMDroid (offline) providers.
 *
 * ## Usage
 *
 * ```kotlin
 * // In Activity
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.map_container, MapFragment.newInstance(MapProvider.GOOGLE_MAPS))
 *     .commit()
 * ```
 *
 * ## Provider Switching
 *
 * ```kotlin
 * fragment.switchProvider(MapProvider.OFFLINE_OSM)
 * ```
 */
package com.continuum.navigator.core.map

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.continuum.navigator.core.native.NavigationOutput
import com.continuum.navigator.core.vulkan.FrameStats
import com.continuum.navigator.core.vulkan.NavigationOverlayManager
import com.continuum.navigator.core.vulkan.OverlayColors
import com.continuum.navigator.core.vulkan.OverlayConfig

/**
 * Fragment hosting the map view with support for provider switching.
 */
class MapFragment : Fragment(), MapControllerCallback {

    companion object {
        private const val TAG = "MapFragment"
        private const val ARG_PROVIDER = "provider"
        private const val ARG_FOLLOW_MODE = "follow_mode"

        /**
         * Create a new MapFragment instance.
         *
         * @param provider Initial map provider
         * @param followMode Initial follow mode state
         */
        @JvmStatic
        fun newInstance(
            provider: MapProvider = MapProvider.GOOGLE_MAPS,
            followMode: Boolean = true,
        ): MapFragment {
            return MapFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROVIDER, provider.name)
                    putBoolean(ARG_FOLLOW_MODE, followMode)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════

    private var mapContainer: FrameLayout? = null
    private var currentController: MapController? = null
    private var currentProvider: MapProvider = MapProvider.GOOGLE_MAPS
    private var followMode: Boolean = true

    // Overlay rendering
    private var overlayManager: NavigationOverlayManager? = null
    private var overlayEnabled = true
    private var overlayConfig = OverlayConfig()
    private var overlayColors = OverlayColors()

    private var listener: MapFragmentListener? = null
    private var overlayListener: OverlayListener? = null

    /**
     * Listener for map fragment events.
     */
    interface MapFragmentListener {
        /** Called when map is ready for use */
        fun onMapReady(controller: MapController)

        /** Called when map provider changes */
        fun onProviderChanged(oldProvider: MapProvider, newProvider: MapProvider)

        /** Called when follow mode changes (e.g., user interacts with map) */
        fun onFollowModeChanged(enabled: Boolean)

        /** Called on map error */
        fun onMapError(error: String)
    }

    /**
     * Listener for overlay events.
     */
    interface OverlayListener {
        /** Called when overlay renderer is ready */
        fun onOverlayReady(usingVulkan: Boolean)

        /** Called when overlay rendering fails */
        fun onOverlayError(error: String)

        /** Called periodically with frame statistics */
        fun onOverlayFrameStats(fps: Float, frameTimeMs: Float, droppedFrames: Long)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            currentProvider = args.getString(ARG_PROVIDER)?.let {
                try { MapProvider.valueOf(it) } catch (e: Exception) { MapProvider.GOOGLE_MAPS }
            } ?: MapProvider.GOOGLE_MAPS

            followMode = args.getBoolean(ARG_FOLLOW_MODE, true)
        }

        Log.d(TAG, "onCreate: provider=$currentProvider, followMode=$followMode")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        mapContainer = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        return mapContainer!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: mapContainer=${mapContainer != null}, width=${mapContainer?.width}, height=${mapContainer?.height}")
        initializeMapController(currentProvider)
        initializeOverlay()
        
        // Post to get actual dimensions after layout
        mapContainer?.post {
            Log.d(TAG, "Post-layout: mapContainer w=${mapContainer?.width}, h=${mapContainer?.height}, childCount=${mapContainer?.childCount}")
        }
    }

    private fun initializeOverlay() {
        Log.d(TAG, "initializeOverlay: overlayEnabled=$overlayEnabled, mapContainer=${mapContainer != null}")
        if (!overlayEnabled) {
            Log.w(TAG, "Overlay disabled, skipping initialization")
            return
        }

        val container = mapContainer ?: run {
            Log.e(TAG, "initializeOverlay: mapContainer is null!")
            return
        }

        overlayManager = NavigationOverlayManager(requireContext()).apply {
            setConfig(overlayConfig)
            setColors(overlayColors)
            addListener(overlayManagerListener)
            attachToContainer(container)
        }

        Log.d(TAG, "Overlay initialized, overlayManager=${overlayManager != null}")
    }

    private val overlayManagerListener = object : NavigationOverlayManager.OverlayManagerListener {
        override fun onOverlayReady(usingVulkan: Boolean) {
            Log.i(TAG, "Overlay ready, Vulkan=${usingVulkan}")
            overlayListener?.onOverlayReady(usingVulkan)
        }

        override fun onOverlayError(message: String) {
            Log.e(TAG, "Overlay error: $message")
            overlayListener?.onOverlayError(message)
        }

        override fun onFrameStats(fps: Float, frameTimeMs: Float, droppedFrames: Long) {
            overlayListener?.onOverlayFrameStats(fps, frameTimeMs, droppedFrames)
        }
    }

    override fun onResume() {
        super.onResume()
        currentController?.onResume()
        overlayManager?.onResume()
    }

    override fun onPause() {
        super.onPause()
        currentController?.onPause()
        overlayManager?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        overlayManager?.release()
        overlayManager = null
        currentController?.onDestroy()
        currentController = null
        mapContainer = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Map Controller Management
    // ═══════════════════════════════════════════════════════════════════════

    private fun initializeMapController(provider: MapProvider) {
        val context = requireContext()

        Log.d(TAG, "Initializing map controller: $provider")

        currentController = when (provider) {
            MapProvider.GOOGLE_MAPS -> GoogleMapController(context)
            MapProvider.OFFLINE_OSM -> OfflineMapController(context)
        }

        currentController?.apply {
            setCallback(this@MapFragment)
            setFollowMode(this@MapFragment.followMode)
            initialize()
        }

        // Add map view to container
        mapContainer?.removeAllViews()
        currentController?.mapView?.let { mapView ->
            mapContainer?.addView(mapView)
        }
    }

    /**
     * Switch to a different map provider at runtime.
     *
     * @param provider New map provider to use
     */
    fun switchProvider(provider: MapProvider) {
        if (provider == currentProvider && currentController != null) {
            Log.d(TAG, "Already using provider: $provider")
            return
        }

        Log.i(TAG, "Switching map provider: $currentProvider -> $provider")

        val oldProvider = currentProvider
        currentProvider = provider

        // Save current state
        val savedZoom = currentController?.getZoom() ?: 17f
        val savedPosition = currentController?.lastPosition

        // Clean up old controller
        currentController?.onDestroy()
        currentController = null

        // Initialize new controller
        initializeMapController(provider)

        // Restore state after initialization
        currentController?.setZoom(savedZoom)
        savedPosition?.let { pos ->
            currentController?.centerOn(pos.latitude, pos.longitude, animate = false)
        }

        listener?.onProviderChanged(oldProvider, provider)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get the current map controller.
     */
    fun getController(): MapController? = currentController

    /**
     * Get the current map provider.
     */
    fun getProvider(): MapProvider = currentProvider

    /**
     * Update the position on the map.
     *
     * @param position New position to display
     * @param animate Whether to animate the update
     */
    fun updatePosition(position: MapPosition, animate: Boolean = true) {
        currentController?.updatePosition(position, animate)
    }

    /**
     * Set follow mode (auto-center on position updates).
     */
    fun setFollowMode(enabled: Boolean) {
        followMode = enabled
        currentController?.setFollowMode(enabled)
    }

    /**
     * Get current follow mode state.
     */
    fun getFollowMode(): Boolean = followMode

    /**
     * Center the map on a specific location.
     */
    fun centerOn(latitude: Double, longitude: Double, animate: Boolean = true) {
        currentController?.centerOn(latitude, longitude, animate)
    }

    /**
     * Set the zoom level.
     */
    fun setZoom(zoom: Float) {
        currentController?.setZoom(zoom)
    }

    /**
     * Set the map fragment listener.
     */
    fun setListener(listener: MapFragmentListener) {
        this.listener = listener
    }

    /**
     * Set the overlay listener.
     */
    fun setOverlayListener(listener: OverlayListener) {
        this.overlayListener = listener
    }

    /**
     * Check if the map is ready.
     */
    fun isMapReady(): Boolean = currentController?.isReady == true

    // ═══════════════════════════════════════════════════════════════════════
    // Overlay API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Update navigation overlay with new NavigationOutput.
     * Call this whenever new navigation data is available.
     */
    fun updateNavigationOverlay(output: NavigationOutput) {
        // Sync map state first
        currentController?.let { controller ->
            overlayManager?.setMapController(controller)
            overlayManager?.syncMapState()
        }

        overlayManager?.updateNavigation(output)
    }

    /**
     * Update raw GNSS position on overlay (unfiltered trajectory).
     */
    fun updateRawGnss(latitude: Double, longitude: Double, timestampNs: Long, accuracyM: Float) {
        overlayManager?.updateRawGnss(latitude, longitude, timestampNs, accuracyM)
    }

    /**
     * Configure overlay before view creation.
     */
    fun setOverlayConfig(config: OverlayConfig) {
        overlayConfig = config
        overlayManager?.setConfig(config)
    }

    /**
     * Set overlay colors.
     */
    fun setOverlayColors(colors: OverlayColors) {
        overlayColors = colors
        overlayManager?.setColors(colors)
    }

    /**
     * Enable or disable overlay rendering.
     */
    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        overlayManager?.setEnabled(enabled)
    }

    /**
     * Clear trajectory history.
     */
    fun clearOverlayTrajectory() {
        overlayManager?.clearTrajectory()
    }

    /**
     * Get overlay frame statistics.
     */
    fun getOverlayFrameStats(): FrameStats {
        return overlayManager?.getFrameStats() ?: FrameStats()
    }

    /**
     * Check if overlay is using Vulkan.
     */
    fun isOverlayUsingVulkan(): Boolean {
        return overlayManager?.isUsingVulkan() == true
    }

    /**
     * Force Canvas fallback (for testing).
     */
    fun forceOverlayCanvasFallback(force: Boolean) {
        overlayManager?.forceCanvasFallback(force)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MapControllerCallback Implementation
    // ═══════════════════════════════════════════════════════════════════════

    override fun onMapReady() {
        Log.i(TAG, "Map ready: $currentProvider")
        currentController?.let { controller ->
            listener?.onMapReady(controller)
        }
    }

    override fun onMapError(error: String) {
        Log.e(TAG, "Map error: $error")
        listener?.onMapError(error)
    }

    override fun onUserInteraction() {
        // User interacted with map - disable follow mode
        if (followMode) {
            followMode = false
            currentController?.setFollowMode(false)
            listener?.onFollowModeChanged(false)
            Log.d(TAG, "Follow mode disabled due to user interaction")
        }
    }
}
