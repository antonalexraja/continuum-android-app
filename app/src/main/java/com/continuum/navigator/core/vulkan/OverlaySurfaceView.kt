/**
 * Overlay Surface View
 *
 * Custom SurfaceView for rendering navigation overlays.
 *
 * ## Features
 *
 * - Transparent surface for overlay on top of map
 * - Vulkan rendering with Canvas fallback
 * - Automatic lifecycle management
 * - Performance monitoring
 *
 * ## Usage
 *
 * ```kotlin
 * val overlayView = OverlaySurfaceView(context)
 * frameLayout.addView(overlayView)
 *
 * overlayView.updateNavigationOutput(navigationOutput)
 * overlayView.updateMapState(mapViewState)
 * ```
 */
package com.continuum.navigator.core.vulkan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.continuum.navigator.core.native.NavigationOutput
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos

/**
 * SurfaceView for rendering navigation overlays.
 */
class OverlaySurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "OverlaySurfaceView"
        private const val NS_PER_SEC = 1_000_000_000L
    }

    // Configuration
    private var config = OverlayConfig()
    private var colors = OverlayColors()

    // Renderers
    private var vulkanRenderer: VulkanOverlayRenderer? = null
    private var canvasRenderer: CanvasFallbackRenderer? = null
    private var activeRenderer: OverlayRenderer? = null

    // State
    private val isInitialized = AtomicBoolean(false)
    private val useVulkan = AtomicBoolean(true)

    // Overlay data
    private val trajectoryHistory = TrajectoryHistory(config.maxTrajectoryPoints)
    private val rawGnssHistory = TrajectoryHistory(config.maxTrajectoryPoints) // Raw GNSS trajectory
    private var lastNavigationOutput: NavigationOutput? = null
    private var mapState: MapViewState = MapViewState.DEFAULT

    // Listener
    private var listener: OverlayListener? = null

    /**
     * Listener for overlay events.
     */
    interface OverlayListener {
        fun onRendererInitialized(usingVulkan: Boolean)
        fun onRenderError(error: String)
        fun onFrameStats(stats: FrameStats)
    }

    init {
        // Make surface transparent
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)

        // CRITICAL: Pass all touch events through to the map underneath
        // Without this, the overlay blocks gestures like pinch-zoom
        isClickable = false
        isFocusable = false

        Log.d(TAG, "OverlaySurfaceView created")
    }

    /**
     * Pass all touch events through to underlying views.
     * The overlay is display-only and should not consume touch events.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Always return false to pass touch events to the map
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Set overlay configuration.
     */
    fun setConfig(config: OverlayConfig) {
        this.config = config
    }

    /**
     * Set overlay colors.
     */
    fun setColors(colors: OverlayColors) {
        this.colors = colors
    }

    /**
     * Set overlay listener.
     */
    fun setOverlayListener(listener: OverlayListener?) {
        this.listener = listener
    }

    /**
     * Force use of Canvas fallback (for testing or compatibility).
     */
    fun forceCanvasFallback(force: Boolean) {
        useVulkan.set(!force)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Surface Callbacks
    // ═══════════════════════════════════════════════════════════════════════

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        // Initialization happens in surfaceChanged
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}, format=$format")

        if (width == 0 || height == 0) return

        // Initialize or reinitialize renderer
        initializeRenderer(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")

        activeRenderer?.stop()
        activeRenderer?.release()
        activeRenderer = null
        vulkanRenderer = null
        canvasRenderer = null
        isInitialized.set(false)
    }

    private fun initializeRenderer(holder: SurfaceHolder, width: Int, height: Int) {
        // Clean up existing renderer
        activeRenderer?.stop()
        activeRenderer?.release()

        val usingVulkan: Boolean

        if (useVulkan.get() && VulkanOverlayRenderer.isVulkanAvailable()) {
            // Try Vulkan first
            Log.d(TAG, "Attempting Vulkan initialization")

            vulkanRenderer = VulkanOverlayRenderer(context, config, colors)
            val success = vulkanRenderer?.initialize(holder.surface, width, height) == true

            if (success) {
                Log.i(TAG, "Vulkan renderer initialized successfully")
                activeRenderer = vulkanRenderer
                usingVulkan = true
            } else {
                Log.w(TAG, "Vulkan initialization failed, falling back to Canvas")
                vulkanRenderer?.release()
                vulkanRenderer = null
                usingVulkan = false
            }
        } else {
            Log.d(TAG, "Vulkan not available or disabled, using Canvas")
            usingVulkan = false
        }

        if (!usingVulkan) {
            // Use Canvas fallback
            canvasRenderer = CanvasFallbackRenderer(config, colors)
            canvasRenderer?.initialize(holder.surface, width, height)
            activeRenderer = canvasRenderer

            // For Canvas, we render in draw() instead of a separate thread
            setWillNotDraw(false)
        } else {
            setWillNotDraw(true)
        }

        // Update map state with new dimensions
        mapState = mapState.copy(viewWidth = width, viewHeight = height)
        activeRenderer?.updateMapState(mapState)

        // Start rendering
        activeRenderer?.start()
        isInitialized.set(true)

        listener?.onRendererInitialized(usingVulkan)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Drawing (Canvas fallback only)
    // ═══════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Only used for Canvas fallback
        canvasRenderer?.renderToCanvas(canvas)

        // Request next frame
        if (canvasRenderer?.isRunning == true) {
            invalidate()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Navigation Data Updates
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Update with new navigation output.
     * Call this whenever NavigationOutput is received from the native engine.
     */
    fun updateNavigationOutput(output: NavigationOutput) {
        Log.v(TAG, "updateNavigationOutput: initialized=${isInitialized.get()}, lat=${output.latitudeDeg}")
        if (!isInitialized.get()) return

        lastNavigationOutput = output

        // Add to trajectory history
        output.toGeoPoint()?.let { geoPoint ->
            trajectoryHistory.add(geoPoint)

            // Prune old points
            val cutoffNs = output.timestampNs - (config.trajectoryDurationSec * NS_PER_SEC).toLong()
            trajectoryHistory.pruneOlderThan(cutoffNs)
        }

        // Build frame
        val frame = buildOverlayFrame(output)

        // Submit to renderer
        activeRenderer?.submitFrame(frame)
        
        // For Canvas fallback, render directly to surface
        if (canvasRenderer != null) {
            renderCanvasFrame()
        }
    }
    
    /**
     * Render a frame using Canvas fallback directly to the Surface.
     */
    private fun renderCanvasFrame() {
        Log.v(TAG, "renderCanvasFrame: locking canvas")
        val canvas = try {
            holder.lockCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to lock canvas: ${e.message}")
            return
        } ?: return
        
        try {
            // Clear with transparent
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvasRenderer?.renderToCanvas(canvas)
            Log.v(TAG, "renderCanvasFrame: rendered")
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unlock canvas: ${e.message}")
            }
        }
    }

    /**
     * Update map view state for coordinate alignment.
     */
    fun updateMapState(state: MapViewState) {
        mapState = state.copy(
            viewWidth = width.takeIf { it > 0 } ?: mapState.viewWidth,
            viewHeight = height.takeIf { it > 0 } ?: mapState.viewHeight,
        )
        activeRenderer?.updateMapState(mapState)
    }

    /**
     * Clear trajectory history.
     */
    fun clearTrajectory() {
        trajectoryHistory.clear()
        rawGnssHistory.clear()
    }

    /**
     * Update with raw GNSS position (unfiltered).
     * Builds a separate trajectory for visualization comparison.
     */
    fun updateRawGnss(latitude: Double, longitude: Double, timestampNs: Long, accuracyM: Float) {
        if (!isInitialized.get()) return

        val geoPoint = GeoPoint(
            latitude = latitude,
            longitude = longitude,
            timestampNs = timestampNs,
            heading = 0f,
            uncertainty = accuracyM,
        )
        rawGnssHistory.add(geoPoint)

        // Prune old points
        val cutoffNs = timestampNs - (config.trajectoryDurationSec * NS_PER_SEC).toLong()
        rawGnssHistory.pruneOlderThan(cutoffNs)
    }

    /**
     * Get current frame statistics.
     */
    fun getFrameStats(): FrameStats {
        return activeRenderer?.getFrameStats() ?: FrameStats()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Frame Building
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildOverlayFrame(output: NavigationOutput): OverlayFrame {
        val currentPosition = output.toGeoPoint()

        // Build prediction path
        val predictionPoints = buildPredictionPath(output)

        return OverlayFrame(
            trajectoryPoints = trajectoryHistory.getPoints(),
            rawGnssPoints = rawGnssHistory.getPoints(),
            predictionPoints = predictionPoints,
            currentPosition = currentPosition,
            positionUncertaintyM = output.positionStdM.toFloat(),
            headingUncertaintyRad = output.attitudeStdRad.toFloat(),
            headingRad = output.yawRad.toFloat(),
            speedMps = output.speed.toFloat(),
            timestampNs = output.timestampNs,
        )
    }

    /**
     * Build predicted future path based on current velocity and heading.
     *
     * Uses simple dead reckoning: position + velocity * time
     * No smoothing - pure physics-based prediction.
     */
    private fun buildPredictionPath(output: NavigationOutput): List<GeoPoint> {
        if (!output.hasVelocity || output.speed < 0.5) {
            // Not moving - no prediction
            return emptyList()
        }

        val points = mutableListOf<GeoPoint>()

        // Current position
        val startLat = output.latitudeDeg
        val startLon = output.longitudeDeg

        // Velocity in m/s
        val vNorth = output.velocityNorthMps
        val vEast = output.velocityEastMps

        // Generate prediction points
        var t = 0f
        while (t <= config.predictionHorizonSec) {
            t += config.predictionIntervalSec

            // Dead reckoning position
            val distNorth = vNorth * t
            val distEast = vEast * t

            // Convert meters to degrees (approximate)
            val latOffset = distNorth / 111320.0  // meters per degree latitude
            val lonOffset = distEast / (111320.0 * cos(Math.toRadians(startLat)))

            val predLat = startLat + latOffset
            val predLon = startLon + lonOffset

            points.add(
                GeoPoint(
                    latitude = predLat,
                    longitude = predLon,
                    timestampNs = output.timestampNs + (t * NS_PER_SEC).toLong(),
                    heading = output.yawRad.toFloat(),
                    uncertainty = (output.positionStdM + output.velocityStdMps * t).toFloat(),
                )
            )
        }

        return points
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resume rendering. Call from Activity/Fragment onResume.
     */
    fun onResume() {
        if (isInitialized.get()) {
            activeRenderer?.start()
        }
    }

    /**
     * Pause rendering. Call from Activity/Fragment onPause.
     */
    fun onPause() {
        activeRenderer?.stop()
    }

    /**
     * Check if using Vulkan renderer.
     */
    fun isUsingVulkan(): Boolean {
        return vulkanRenderer != null && vulkanRenderer === activeRenderer
    }
}
