/**
 * Vulkan Overlay Renderer - Core Types
 *
 * Data structures for overlay rendering using Vulkan.
 *
 * ## Overlay Types
 *
 * 1. **Trajectory**: Dead-reckoned past path as polyline
 * 2. **Prediction**: Future path prediction based on velocity/heading
 * 3. **Uncertainty**: Confidence cone or ellipse
 * 4. **Heading**: Direction indicator
 *
 * ## Coordinate Systems
 *
 * - **LLA**: Latitude/Longitude/Altitude (WGS84)
 * - **Screen**: Pixel coordinates (0,0 = top-left)
 * - **NDC**: Normalized Device Coordinates (-1 to +1)
 */
package com.continuum.navigator.core.vulkan

import android.graphics.Color
import com.continuum.navigator.core.native.NavigationOutput

// ═══════════════════════════════════════════════════════════════════════════
// Overlay Configuration
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Configuration for the overlay renderer.
 */
data class OverlayConfig(
    /** Maximum trajectory history points */
    val maxTrajectoryPoints: Int = 100,

    /** Trajectory history duration in seconds */
    val trajectoryDurationSec: Float = 30f,

    /** Prediction horizon in seconds */
    val predictionHorizonSec: Float = 5f,

    /** Prediction sample interval in seconds */
    val predictionIntervalSec: Float = 0.5f,

    /** Trajectory line width in pixels */
    val trajectoryLineWidth: Float = 4f,

    /** Prediction line width in pixels */
    val predictionLineWidth: Float = 3f,

    /** Heading indicator size in pixels */
    val headingIndicatorSize: Float = 40f,

    /** Show uncertainty visualization */
    val showUncertainty: Boolean = true,

    /** Uncertainty cone angle multiplier (sigma) */
    val uncertaintySigmaMultiplier: Float = 2f,

    /** Target frame rate */
    val targetFps: Int = 60,

    /** 
     * Show position dot on overlay.
     * Set to false when using map SDK's position marker to avoid visual duplication.
     */
    val showPositionDot: Boolean = false,

    /**
     * Show heading indicator on overlay.
     * Set to false when map marker already shows heading.
     */
    val showHeadingIndicator: Boolean = true,

    /**
     * Show raw GNSS trajectory (yellow line) separately from ESKF filtered trajectory.
     */
    val showRawGnssTrajectory: Boolean = true,

    /** Raw GNSS trajectory line width in pixels */
    val rawGnssLineWidth: Float = 3f,
)

/**
 * Color scheme for overlays.
 */
data class OverlayColors(
    /** Trajectory color (past path) */
    val trajectory: Int = Color.argb(180, 66, 133, 244),

    /** Prediction color (future path) */
    val prediction: Int = Color.argb(140, 76, 175, 80),

    /** Uncertainty fill color */
    val uncertaintyFill: Int = Color.argb(40, 255, 193, 7),

    /** Uncertainty outline color */
    val uncertaintyOutline: Int = Color.argb(120, 255, 193, 7),

    /** Heading indicator color */
    val heading: Int = Color.argb(230, 244, 67, 54),

    /** Current position dot */
    val positionDot: Int = Color.argb(255, 66, 133, 244),

    /** Raw GNSS trajectory color (orange/yellow) */
    val rawGnssTrajectory: Int = Color.argb(180, 255, 152, 0),
)

// ═══════════════════════════════════════════════════════════════════════════
// Geometry Types
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 2D point in screen coordinates.
 */
data class ScreenPoint(
    val x: Float,
    val y: Float,
)

/**
 * Geographic point with timestamp.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampNs: Long,
    val heading: Float = 0f,
    val uncertainty: Float = 0f,
)

/**
 * Vertex for Vulkan rendering.
 */
data class OverlayVertex(
    /** X position in NDC (-1 to 1) */
    val x: Float,
    /** Y position in NDC (-1 to 1) */
    val y: Float,
    /** RGBA color packed as float components */
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
) {
    companion object {
        /** Size in bytes (6 floats) */
        const val SIZE_BYTES = 6 * 4

        /** Stride for vertex buffer */
        const val STRIDE = SIZE_BYTES

        fun fromColorInt(x: Float, y: Float, color: Int): OverlayVertex {
            return OverlayVertex(
                x = x,
                y = y,
                r = Color.red(color) / 255f,
                g = Color.green(color) / 255f,
                b = Color.blue(color) / 255f,
                a = Color.alpha(color) / 255f,
            )
        }
    }

    fun toFloatArray(): FloatArray = floatArrayOf(x, y, r, g, b, a)
}

// ═══════════════════════════════════════════════════════════════════════════
// Overlay Data
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Complete overlay state for one frame.
 */
data class OverlayFrame(
    /** ESKF filtered trajectory points (past path - blue) */
    val trajectoryPoints: List<GeoPoint>,

    /** Raw GNSS trajectory points (unfiltered - orange) */
    val rawGnssPoints: List<GeoPoint> = emptyList(),

    /** Prediction points (future path) */
    val predictionPoints: List<GeoPoint>,

    /** Current position */
    val currentPosition: GeoPoint?,

    /** Position uncertainty in meters */
    val positionUncertaintyM: Float,

    /** Heading uncertainty in radians */
    val headingUncertaintyRad: Float,

    /** Heading in radians (0 = North) */
    val headingRad: Float,

    /** Speed in m/s */
    val speedMps: Float,

    /** Frame timestamp */
    val timestampNs: Long,
) {
    companion object {
        val EMPTY = OverlayFrame(
            trajectoryPoints = emptyList(),
            predictionPoints = emptyList(),
            currentPosition = null,
            positionUncertaintyM = 0f,
            headingUncertaintyRad = 0f,
            headingRad = 0f,
            speedMps = 0f,
            timestampNs = 0L,
        )
    }
}

/**
 * Rendered geometry ready for GPU.
 */
data class OverlayGeometry(
    /** Trajectory line vertices */
    val trajectoryVertices: FloatArray,
    val trajectoryVertexCount: Int,

    /** Prediction line vertices */
    val predictionVertices: FloatArray,
    val predictionVertexCount: Int,

    /** Uncertainty cone/ellipse vertices */
    val uncertaintyVertices: FloatArray,
    val uncertaintyVertexCount: Int,

    /** Heading indicator vertices */
    val headingVertices: FloatArray,
    val headingVertexCount: Int,

    /** Position dot vertices */
    val positionVertices: FloatArray,
    val positionVertexCount: Int,

    /** Raw GNSS trajectory vertices */
    val rawGnssVertices: FloatArray = FloatArray(0),
    val rawGnssVertexCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverlayGeometry) return false
        return trajectoryVertices.contentEquals(other.trajectoryVertices) &&
            predictionVertices.contentEquals(other.predictionVertices) &&
            uncertaintyVertices.contentEquals(other.uncertaintyVertices) &&
            headingVertices.contentEquals(other.headingVertices) &&
            positionVertices.contentEquals(other.positionVertices) &&
            rawGnssVertices.contentEquals(other.rawGnssVertices)
    }

    override fun hashCode(): Int {
        var result = trajectoryVertices.contentHashCode()
        result = 31 * result + predictionVertices.contentHashCode()
        result = 31 * result + uncertaintyVertices.contentHashCode()
        result = 31 * result + headingVertices.contentHashCode()
        result = 31 * result + positionVertices.contentHashCode()
        result = 31 * result + rawGnssVertices.contentHashCode()
        return result
    }

    companion object {
        val EMPTY = OverlayGeometry(
            trajectoryVertices = FloatArray(0),
            trajectoryVertexCount = 0,
            predictionVertices = FloatArray(0),
            predictionVertexCount = 0,
            uncertaintyVertices = FloatArray(0),
            uncertaintyVertexCount = 0,
            headingVertices = FloatArray(0),
            headingVertexCount = 0,
            positionVertices = FloatArray(0),
            positionVertexCount = 0,
            rawGnssVertices = FloatArray(0),
            rawGnssVertexCount = 0,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Map View State (for coordinate transforms)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Current map view state for coordinate transformation.
 */
data class MapViewState(
    /** Map center latitude */
    val centerLatitude: Double,

    /** Map center longitude */
    val centerLongitude: Double,

    /** Current zoom level */
    val zoomLevel: Float,

    /** Map rotation in degrees (0 = north up) */
    val rotation: Float,

    /** View width in pixels */
    val viewWidth: Int,

    /** View height in pixels */
    val viewHeight: Int,

    /** Meters per pixel at current zoom */
    val metersPerPixel: Double,
) {
    companion object {
        val DEFAULT = MapViewState(
            centerLatitude = 0.0,
            centerLongitude = 0.0,
            zoomLevel = 15f,
            rotation = 0f,
            viewWidth = 1080,
            viewHeight = 1920,
            metersPerPixel = 1.0,
        )

        /**
         * Calculate meters per pixel at given latitude and zoom.
         */
        fun calculateMetersPerPixel(latitude: Double, zoomLevel: Float): Double {
            val earthCircumference = 40075016.686  // meters at equator
            val latRadians = Math.toRadians(latitude)
            val metersPerPixelAtEquator = earthCircumference / (256 * Math.pow(2.0, zoomLevel.toDouble()))
            return metersPerPixelAtEquator * Math.cos(latRadians)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Trajectory History
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Ring buffer for trajectory history.
 */
class TrajectoryHistory(private val maxSize: Int) {

    private val points = ArrayDeque<GeoPoint>(maxSize)

    val size: Int get() = points.size

    fun add(point: GeoPoint) {
        if (points.size >= maxSize) {
            points.removeFirst()
        }
        points.addLast(point)
    }

    fun getPoints(): List<GeoPoint> = points.toList()

    fun clear() {
        points.clear()
    }

    /**
     * Prune points older than cutoffNs.
     */
    fun pruneOlderThan(cutoffNs: Long) {
        while (points.isNotEmpty() && points.first().timestampNs < cutoffNs) {
            points.removeFirst()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Extension Functions
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Convert NavigationOutput to GeoPoint.
 */
fun NavigationOutput.toGeoPoint(): GeoPoint? {
    if (!hasPositionLla) return null
    return GeoPoint(
        latitude = latitudeDeg,
        longitude = longitudeDeg,
        timestampNs = timestampNs,
        heading = yawRad.toFloat(),
        uncertainty = positionStdM.toFloat(),
    )
}
