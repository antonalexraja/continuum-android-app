/**
 * Vulkan Overlay Renderer
 *
 * Core Vulkan rendering abstraction for navigation overlays.
 *
 * ## Architecture
 *
 * - VulkanContext: Device, instance, queues
 * - VulkanSwapchain: Surface and presentation
 * - VulkanPipeline: Shaders and render pass
 * - VulkanOverlayRenderer: High-level rendering API
 *
 * ## Thread Safety
 *
 * Rendering happens on a dedicated render thread.
 * Frame data is passed via thread-safe queue.
 *
 * ## Fallback
 *
 * If Vulkan is unavailable, a software fallback using Canvas is used.
 */
package com.continuum.navigator.core.vulkan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ═══════════════════════════════════════════════════════════════════════════
// Renderer Interface
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Abstract overlay renderer interface.
 */
interface OverlayRenderer {
    /** Check if renderer is available */
    val isAvailable: Boolean

    /** Check if renderer is currently running */
    val isRunning: Boolean

    /** Initialize the renderer */
    fun initialize(surface: Surface, width: Int, height: Int): Boolean

    /** Start rendering */
    fun start()

    /** Stop rendering */
    fun stop()

    /** Release all resources */
    fun release()

    /** Submit a frame for rendering */
    fun submitFrame(frame: OverlayFrame)

    /** Update map view state for coordinate transforms */
    fun updateMapState(state: MapViewState)

    /** Get frame timing stats */
    fun getFrameStats(): FrameStats
}

/**
 * Frame timing statistics.
 */
data class FrameStats(
    val frameCount: Long = 0,
    val averageFps: Float = 0f,
    val averageFrameTimeMs: Float = 0f,
    val maxFrameTimeMs: Float = 0f,
    val lastFrameTimeMs: Float = 0f,
    val droppedFrames: Long = 0,
)

// ═══════════════════════════════════════════════════════════════════════════
// Vulkan Renderer Implementation
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Vulkan-based overlay renderer.
 *
 * Uses Vulkan via NDK for high-performance rendering.
 * Falls back to Canvas if Vulkan is unavailable.
 */
class VulkanOverlayRenderer(
    private val context: Context,
    private val config: OverlayConfig = OverlayConfig(),
    private val colors: OverlayColors = OverlayColors(),
) : OverlayRenderer {

    companion object {
        private const val TAG = "VulkanOverlayRenderer"

        /**
         * Check if Vulkan is available on this device.
         */
        fun isVulkanAvailable(): Boolean {
            return try {
                // Check for Vulkan support via native code
                nativeIsVulkanAvailable()
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native Vulkan library not available")
                false
            }
        }

        init {
            try {
                System.loadLibrary("navigator_vulkan")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load navigator_vulkan library: ${e.message}")
            }
        }

        @JvmStatic
        private external fun nativeIsVulkanAvailable(): Boolean

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeInitialize(
            handle: Long,
            surface: Surface,
            width: Int,
            height: Int,
        ): Boolean

        @JvmStatic
        private external fun nativeRenderFrame(
            handle: Long,
            vertices: FloatArray,
            vertexCount: Int,
        ): Boolean

        @JvmStatic
        private external fun nativeResize(handle: Long, width: Int, height: Int)

        @JvmStatic
        private external fun nativeRelease(handle: Long)
    }

    // State
    private var nativeHandle: Long = 0
    private var surface: Surface? = null
    private var width: Int = 0
    private var height: Int = 0

    private val _isRunning = AtomicBoolean(false)
    override val isRunning: Boolean get() = _isRunning.get()

    private val _isAvailable = AtomicBoolean(false)
    override val isAvailable: Boolean get() = _isAvailable.get()

    // Render thread
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    // Frame data
    private val currentFrame = AtomicReference<OverlayFrame>(OverlayFrame.EMPTY)
    private val mapState = AtomicReference<MapViewState>(MapViewState.DEFAULT)

    // Geometry builder
    private lateinit var transformer: CoordinateTransformer
    private lateinit var geometryBuilder: OverlayGeometryBuilder

    // Frame timing
    private val statsLock = ReentrantLock()
    private var frameCount: Long = 0
    private var totalFrameTimeNs: Long = 0
    private var maxFrameTimeMs: Float = 0f
    private var lastFrameTimeMs: Float = 0f
    private var droppedFrames: Long = 0
    private var lastRenderTimeNs: Long = 0

    // Target frame interval
    private val targetFrameIntervalNs = 1_000_000_000L / config.targetFps

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun initialize(surface: Surface, width: Int, height: Int): Boolean {
        Log.d(TAG, "Initializing Vulkan renderer: ${width}x${height}")

        this.surface = surface
        this.width = width
        this.height = height

        // Initialize coordinate transformer
        transformer = CoordinateTransformer()
        geometryBuilder = OverlayGeometryBuilder(config, colors, transformer)

        // Update initial map state
        val initialMapState = MapViewState.DEFAULT.copy(
            viewWidth = width,
            viewHeight = height,
        )
        mapState.set(initialMapState)
        transformer.updateMapState(initialMapState)

        // Try to initialize Vulkan
        return try {
            nativeHandle = nativeCreate()
            if (nativeHandle == 0L) {
                Log.w(TAG, "Failed to create Vulkan context")
                _isAvailable.set(false)
                return false
            }

            val success = nativeInitialize(nativeHandle, surface, width, height)
            if (!success) {
                Log.w(TAG, "Failed to initialize Vulkan surface")
                nativeDestroy(nativeHandle)
                nativeHandle = 0
                _isAvailable.set(false)
                return false
            }

            _isAvailable.set(true)
            Log.i(TAG, "Vulkan renderer initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Vulkan initialization", e)
            _isAvailable.set(false)
            false
        }
    }

    override fun start() {
        if (_isRunning.getAndSet(true)) {
            Log.w(TAG, "Renderer already running")
            return
        }

        Log.d(TAG, "Starting render thread")

        renderThread = HandlerThread("VulkanRenderThread").apply {
            start()
        }

        renderHandler = Handler(renderThread!!.looper)

        // Start render loop
        scheduleNextFrame()
    }

    override fun stop() {
        if (!_isRunning.getAndSet(false)) {
            return
        }

        Log.d(TAG, "Stopping render thread")

        renderHandler?.removeCallbacksAndMessages(null)
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
    }

    override fun release() {
        stop()

        if (nativeHandle != 0L) {
            try {
                nativeRelease(nativeHandle)
                nativeDestroy(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Vulkan resources", e)
            }
            nativeHandle = 0
        }

        surface = null
        _isAvailable.set(false)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Frame Submission
    // ═══════════════════════════════════════════════════════════════════════

    override fun submitFrame(frame: OverlayFrame) {
        currentFrame.set(frame)
    }

    override fun updateMapState(state: MapViewState) {
        val updated = state.copy(
            viewWidth = width,
            viewHeight = height,
        )
        mapState.set(updated)
    }

    override fun getFrameStats(): FrameStats {
        return statsLock.withLock {
            val avgFps = if (totalFrameTimeNs > 0 && frameCount > 0) {
                frameCount.toFloat() / (totalFrameTimeNs / 1_000_000_000f)
            } else {
                0f
            }

            val avgFrameTime = if (frameCount > 0) {
                (totalFrameTimeNs / frameCount / 1_000_000f)
            } else {
                0f
            }

            FrameStats(
                frameCount = frameCount,
                averageFps = avgFps,
                averageFrameTimeMs = avgFrameTime,
                maxFrameTimeMs = maxFrameTimeMs,
                lastFrameTimeMs = lastFrameTimeMs,
                droppedFrames = droppedFrames,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Render Loop
    // ═══════════════════════════════════════════════════════════════════════

    private fun scheduleNextFrame() {
        if (!_isRunning.get()) return

        renderHandler?.post(::renderFrame)
    }

    private fun renderFrame() {
        if (!_isRunning.get()) return

        val frameStartNs = System.nanoTime()

        try {
            // Update transformer with current map state
            transformer.updateMapState(mapState.get())

            // Get current frame data
            val frame = currentFrame.get()

            // Build geometry
            val geometry = geometryBuilder.buildGeometry(frame)

            // Combine all vertices
            val allVertices = combineVertices(geometry)

            // Render via Vulkan
            if (nativeHandle != 0L && allVertices.isNotEmpty()) {
                val success = nativeRenderFrame(
                    nativeHandle,
                    allVertices,
                    allVertices.size / 6,  // 6 floats per vertex
                )

                if (!success) {
                    Log.w(TAG, "Frame render failed")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during frame render", e)
        }

        // Update stats
        val frameEndNs = System.nanoTime()
        val frameTimeNs = frameEndNs - frameStartNs

        statsLock.withLock {
            frameCount++
            totalFrameTimeNs += frameTimeNs
            lastFrameTimeMs = frameTimeNs / 1_000_000f

            if (lastFrameTimeMs > maxFrameTimeMs) {
                maxFrameTimeMs = lastFrameTimeMs
            }

            // Check for dropped frame
            if (lastRenderTimeNs > 0) {
                val elapsed = frameStartNs - lastRenderTimeNs
                if (elapsed > targetFrameIntervalNs * 1.5) {
                    droppedFrames++
                }
            }
            lastRenderTimeNs = frameEndNs
        }

        // Schedule next frame with VSync timing
        val nextFrameDelay = targetFrameIntervalNs - frameTimeNs
        if (nextFrameDelay > 0) {
            renderHandler?.postDelayed(::renderFrame, nextFrameDelay / 1_000_000)
        } else {
            // Behind schedule - render immediately
            renderHandler?.post(::renderFrame)
        }
    }

    private fun combineVertices(geometry: OverlayGeometry): FloatArray {
        val totalSize = geometry.uncertaintyVertices.size +
            geometry.trajectoryVertices.size +
            geometry.predictionVertices.size +
            geometry.headingVertices.size +
            geometry.positionVertices.size

        if (totalSize == 0) return FloatArray(0)

        val combined = FloatArray(totalSize)
        var offset = 0

        // Render order: uncertainty (back), trajectory, prediction, heading, position (front)
        geometry.uncertaintyVertices.copyInto(combined, offset)
        offset += geometry.uncertaintyVertices.size

        geometry.trajectoryVertices.copyInto(combined, offset)
        offset += geometry.trajectoryVertices.size

        geometry.predictionVertices.copyInto(combined, offset)
        offset += geometry.predictionVertices.size

        geometry.headingVertices.copyInto(combined, offset)
        offset += geometry.headingVertices.size

        geometry.positionVertices.copyInto(combined, offset)

        return combined
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Canvas Fallback Renderer
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Software fallback renderer using Canvas.
 *
 * Used when Vulkan is not available.
 */
class CanvasFallbackRenderer(
    private val config: OverlayConfig = OverlayConfig(),
    private val colors: OverlayColors = OverlayColors(),
) : OverlayRenderer {

    companion object {
        private const val TAG = "CanvasFallbackRenderer"
    }

    override val isAvailable: Boolean = true
    override val isRunning: Boolean get() = _isRunning

    private var _isRunning = false
    private var surface: Surface? = null
    private var width: Int = 0
    private var height: Int = 0

    private lateinit var transformer: CoordinateTransformer
    private val currentFrame = AtomicReference<OverlayFrame>(OverlayFrame.EMPTY)
    private val mapState = AtomicReference<MapViewState>(MapViewState.DEFAULT)

    // Paints
    private val trajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = config.trajectoryLineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = config.predictionLineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val uncertaintyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colors.uncertaintyFill
    }

    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colors.heading
    }

    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colors.positionDot
    }

    private val rawGnssPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = config.rawGnssLineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colors.rawGnssTrajectory
    }

    // Frame stats (simplified for fallback)
    private var frameCount: Long = 0

    override fun initialize(surface: Surface, width: Int, height: Int): Boolean {
        this.surface = surface
        this.width = width
        this.height = height

        transformer = CoordinateTransformer()
        val initialMapState = MapViewState.DEFAULT.copy(viewWidth = width, viewHeight = height)
        mapState.set(initialMapState)
        transformer.updateMapState(initialMapState)

        Log.i(TAG, "Canvas fallback renderer initialized: ${width}x${height}")
        return true
    }

    override fun start() {
        _isRunning = true
    }

    override fun stop() {
        _isRunning = false
    }

    override fun release() {
        stop()
        surface = null
    }

    override fun submitFrame(frame: OverlayFrame) {
        currentFrame.set(frame)
    }

    override fun updateMapState(state: MapViewState) {
        mapState.set(state.copy(viewWidth = width, viewHeight = height))
    }

    override fun getFrameStats(): FrameStats {
        return FrameStats(frameCount = frameCount)
    }

    /**
     * Render current frame to canvas.
     * Called by OverlaySurfaceView.
     */
    fun renderToCanvas(canvas: Canvas) {
        if (!_isRunning) return

        transformer.updateMapState(mapState.get())
        val frame = currentFrame.get()

        // Draw in order: uncertainty, raw GNSS (below), ESKF trajectory (above), prediction, heading, position
        drawUncertainty(canvas, frame)
        
        // Draw raw GNSS trajectory first (orange, underneath)
        if (config.showRawGnssTrajectory && frame.rawGnssPoints.isNotEmpty()) {
            drawRawGnssTrajectory(canvas, frame.rawGnssPoints)
        }
        
        // Draw ESKF filtered trajectory (blue, on top)
        drawTrajectory(canvas, frame.trajectoryPoints)
        drawPrediction(canvas, frame.predictionPoints)
        
        // Only draw heading if configured (may conflict with map marker)
        if (config.showHeadingIndicator) {
            drawHeading(canvas, frame)
        }
        
        // Only draw position dot if configured (usually disabled when map has marker)
        if (config.showPositionDot) {
            drawPosition(canvas, frame.currentPosition)
        }

        frameCount++
    }

    private fun drawRawGnssTrajectory(canvas: Canvas, points: List<GeoPoint>) {
        if (points.size < 2) return

        val path = Path()
        var first = true

        for (point in points) {
            val screen = transformer.geoToScreen(point.latitude, point.longitude)
            if (first) {
                path.moveTo(screen.x, screen.y)
                first = false
            } else {
                path.lineTo(screen.x, screen.y)
            }
        }

        canvas.drawPath(path, rawGnssPaint)
    }

    private fun drawTrajectory(canvas: Canvas, points: List<GeoPoint>) {
        if (points.size < 2) return

        val path = Path()
        var first = true

        for (point in points) {
            val screen = transformer.geoToScreen(point.latitude, point.longitude)
            if (first) {
                path.moveTo(screen.x, screen.y)
                first = false
            } else {
                path.lineTo(screen.x, screen.y)
            }
        }

        trajectoryPaint.color = colors.trajectory
        canvas.drawPath(path, trajectoryPaint)
    }

    private fun drawPrediction(canvas: Canvas, points: List<GeoPoint>) {
        if (points.size < 2) return

        val path = Path()
        var first = true

        for (point in points) {
            val screen = transformer.geoToScreen(point.latitude, point.longitude)
            if (first) {
                path.moveTo(screen.x, screen.y)
                first = false
            } else {
                path.lineTo(screen.x, screen.y)
            }
        }

        predictionPaint.color = colors.prediction
        canvas.drawPath(path, predictionPaint)
    }

    private fun drawUncertainty(canvas: Canvas, frame: OverlayFrame) {
        val position = frame.currentPosition ?: return
        if (!config.showUncertainty) return

        val screen = transformer.geoToScreen(position.latitude, position.longitude)
        val radiusPixels = transformer.metersToPixels(
            (frame.positionUncertaintyM * config.uncertaintySigmaMultiplier).toDouble()
        )

        canvas.drawCircle(screen.x, screen.y, radiusPixels, uncertaintyFillPaint)
    }

    private fun drawHeading(canvas: Canvas, frame: OverlayFrame) {
        val position = frame.currentPosition ?: return

        val screen = transformer.geoToScreen(position.latitude, position.longitude)
        val adjustedHeading = transformer.adjustHeadingForMapRotation(frame.headingRad)
        val arrowSize = config.headingIndicatorSize

        // Arrow pointing in heading direction
        canvas.save()
        canvas.translate(screen.x, screen.y)
        canvas.rotate(Math.toDegrees(adjustedHeading.toDouble()).toFloat() - 90f)

        val path = Path()
        path.moveTo(arrowSize, 0f)  // Tip
        path.lineTo(-arrowSize * 0.4f, -arrowSize * 0.3f)
        path.lineTo(-arrowSize * 0.4f, arrowSize * 0.3f)
        path.close()

        canvas.drawPath(path, headingPaint)
        canvas.restore()
    }

    private fun drawPosition(canvas: Canvas, position: GeoPoint?) {
        position ?: return

        val screen = transformer.geoToScreen(position.latitude, position.longitude)

        // White border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(screen.x, screen.y, 12f, borderPaint)

        // Blue dot
        canvas.drawCircle(screen.x, screen.y, 8f, positionPaint)
    }
}
