/**
 * Overlay Geometry Builder
 *
 * Builds GPU-ready geometry from overlay data.
 *
 * ## Geometry Types
 *
 * 1. **Trajectory**: Thick polyline using triangle strip
 * 2. **Prediction**: Dashed line or fading polyline
 * 3. **Uncertainty Cone**: Triangle fan with gradient
 * 4. **Heading Arrow**: Triangle with outline
 * 5. **Position Dot**: Filled circle
 *
 * ## Line Rendering
 *
 * Lines are rendered as triangle strips with width to allow for
 * thick, anti-aliased rendering in Vulkan.
 */
package com.continuum.navigator.core.vulkan

import android.graphics.Color
import kotlin.math.*

/**
 * Builds overlay geometry for Vulkan rendering.
 */
class OverlayGeometryBuilder(
    private val config: OverlayConfig,
    private val colors: OverlayColors,
    private val transformer: CoordinateTransformer,
) {

    companion object {
        private const val TAG = "OverlayGeometryBuilder"

        // Circle approximation segments
        private const val CIRCLE_SEGMENTS = 32
        private const val CONE_SEGMENTS = 24
    }

    /**
     * Build complete overlay geometry from frame data.
     */
    fun buildGeometry(frame: OverlayFrame): OverlayGeometry {
        val trajectoryVertices = buildTrajectoryGeometry(frame.trajectoryPoints)
        val predictionVertices = buildPredictionGeometry(frame.predictionPoints)
        val uncertaintyVertices = buildUncertaintyGeometry(
            frame.currentPosition,
            frame.positionUncertaintyM,
            frame.headingRad,
            frame.headingUncertaintyRad,
        )
        val headingVertices = buildHeadingGeometry(
            frame.currentPosition,
            frame.headingRad,
        )
        val positionVertices = buildPositionDotGeometry(frame.currentPosition)

        return OverlayGeometry(
            trajectoryVertices = trajectoryVertices,
            trajectoryVertexCount = trajectoryVertices.size / OverlayVertex.SIZE_BYTES * 4,
            predictionVertices = predictionVertices,
            predictionVertexCount = predictionVertices.size / OverlayVertex.SIZE_BYTES * 4,
            uncertaintyVertices = uncertaintyVertices,
            uncertaintyVertexCount = uncertaintyVertices.size / OverlayVertex.SIZE_BYTES * 4,
            headingVertices = headingVertices,
            headingVertexCount = headingVertices.size / OverlayVertex.SIZE_BYTES * 4,
            positionVertices = positionVertices,
            positionVertexCount = positionVertices.size / OverlayVertex.SIZE_BYTES * 4,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Trajectory Geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build trajectory polyline as triangle strip for thick lines.
     */
    private fun buildTrajectoryGeometry(points: List<GeoPoint>): FloatArray {
        if (points.size < 2) return FloatArray(0)

        val vertices = mutableListOf<Float>()
        val lineWidth = config.trajectoryLineWidth
        val halfWidth = lineWidth / 2f

        // Convert to NDC
        val ndcPoints = points.map { transformer.geoPointToNdc(it) }

        // Build triangle strip for thick line
        for (i in 0 until ndcPoints.size - 1) {
            val (x1, y1) = ndcPoints[i]
            val (x2, y2) = ndcPoints[i + 1]

            // Calculate perpendicular direction
            val dx = x2 - x1
            val dy = y2 - y1
            val len = sqrt(dx * dx + dy * dy)

            if (len < 1e-6f) continue

            // Normal vector (perpendicular to line direction)
            val nx = -dy / len
            val ny = dx / len

            // Line width in NDC
            val mapState = transformer.getMapState()
            val widthNdc = halfWidth * 2f / mapState.viewWidth

            // Fade color based on age
            val ageRatio = i.toFloat() / (ndcPoints.size - 1).coerceAtLeast(1)
            val alpha = 0.3f + 0.7f * (1f - ageRatio)  // Fade older points

            val color = adjustAlpha(colors.trajectory, alpha)

            // Add quad vertices (2 triangles)
            val ox = nx * widthNdc
            val oy = ny * widthNdc

            // First triangle
            addVertex(vertices, x1 - ox, y1 - oy, color)
            addVertex(vertices, x1 + ox, y1 + oy, color)
            addVertex(vertices, x2 - ox, y2 - oy, color)

            // Second triangle
            addVertex(vertices, x2 - ox, y2 - oy, color)
            addVertex(vertices, x1 + ox, y1 + oy, color)
            addVertex(vertices, x2 + ox, y2 + oy, color)
        }

        return vertices.toFloatArray()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Prediction Geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build prediction path as fading polyline.
     */
    private fun buildPredictionGeometry(points: List<GeoPoint>): FloatArray {
        if (points.size < 2) return FloatArray(0)

        val vertices = mutableListOf<Float>()
        val lineWidth = config.predictionLineWidth
        val halfWidth = lineWidth / 2f

        // Convert to NDC
        val ndcPoints = points.map { transformer.geoPointToNdc(it) }

        // Build triangle strip with fading
        for (i in 0 until ndcPoints.size - 1) {
            val (x1, y1) = ndcPoints[i]
            val (x2, y2) = ndcPoints[i + 1]

            val dx = x2 - x1
            val dy = y2 - y1
            val len = sqrt(dx * dx + dy * dy)

            if (len < 1e-6f) continue

            val nx = -dy / len
            val ny = dx / len

            val mapState = transformer.getMapState()
            val widthNdc = halfWidth * 2f / mapState.viewWidth

            // Fade based on distance into future
            val fadeRatio1 = i.toFloat() / ndcPoints.size
            val fadeRatio2 = (i + 1).toFloat() / ndcPoints.size
            val alpha1 = 1f - fadeRatio1 * 0.8f
            val alpha2 = 1f - fadeRatio2 * 0.8f

            val color1 = adjustAlpha(colors.prediction, alpha1)
            val color2 = adjustAlpha(colors.prediction, alpha2)

            val ox = nx * widthNdc
            val oy = ny * widthNdc

            // First triangle
            addVertex(vertices, x1 - ox, y1 - oy, color1)
            addVertex(vertices, x1 + ox, y1 + oy, color1)
            addVertex(vertices, x2 - ox, y2 - oy, color2)

            // Second triangle
            addVertex(vertices, x2 - ox, y2 - oy, color2)
            addVertex(vertices, x1 + ox, y1 + oy, color1)
            addVertex(vertices, x2 + ox, y2 + oy, color2)
        }

        return vertices.toFloatArray()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Uncertainty Cone Geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build uncertainty cone/ellipse as triangle fan.
     */
    private fun buildUncertaintyGeometry(
        position: GeoPoint?,
        positionUncertaintyM: Float,
        headingRad: Float,
        headingUncertaintyRad: Float,
    ): FloatArray {
        if (position == null || !config.showUncertainty) return FloatArray(0)

        val vertices = mutableListOf<Float>()

        // Convert position to NDC
        val (cx, cy) = transformer.geoPointToNdc(position)

        // Calculate uncertainty radius in NDC
        val radiusM = positionUncertaintyM * config.uncertaintySigmaMultiplier
        val radiusNdc = transformer.metersToNdc(radiusM.toDouble())

        // Adjust heading for map rotation
        val adjustedHeading = transformer.adjustHeadingForMapRotation(headingRad)

        // Calculate cone half-angle
        val coneHalfAngle = headingUncertaintyRad * config.uncertaintySigmaMultiplier

        // Draw uncertainty cone (triangle fan)
        val startAngle = adjustedHeading - coneHalfAngle - PI.toFloat() / 2f
        val endAngle = adjustedHeading + coneHalfAngle - PI.toFloat() / 2f

        // Create cone wedge
        val coneLength = radiusNdc * 3f  // Extend cone forward

        for (i in 0 until CONE_SEGMENTS) {
            val angle1 = startAngle + (endAngle - startAngle) * i / CONE_SEGMENTS
            val angle2 = startAngle + (endAngle - startAngle) * (i + 1) / CONE_SEGMENTS

            val x1 = cx + cos(angle1) * coneLength
            val y1 = cy + sin(angle1) * coneLength
            val x2 = cx + cos(angle2) * coneLength
            val y2 = cy + sin(angle2) * coneLength

            // Triangle from center to arc
            addVertex(vertices, cx, cy, colors.uncertaintyFill)
            addVertex(vertices, x1, y1, adjustAlpha(colors.uncertaintyFill, 0.1f))
            addVertex(vertices, x2, y2, adjustAlpha(colors.uncertaintyFill, 0.1f))
        }

        // Draw position uncertainty circle
        for (i in 0 until CIRCLE_SEGMENTS) {
            val angle1 = 2f * PI.toFloat() * i / CIRCLE_SEGMENTS
            val angle2 = 2f * PI.toFloat() * (i + 1) / CIRCLE_SEGMENTS

            val x1 = cx + cos(angle1) * radiusNdc
            val y1 = cy + sin(angle1) * radiusNdc
            val x2 = cx + cos(angle2) * radiusNdc
            val y2 = cy + sin(angle2) * radiusNdc

            addVertex(vertices, cx, cy, colors.uncertaintyFill)
            addVertex(vertices, x1, y1, adjustAlpha(colors.uncertaintyOutline, 0.3f))
            addVertex(vertices, x2, y2, adjustAlpha(colors.uncertaintyOutline, 0.3f))
        }

        return vertices.toFloatArray()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Heading Indicator Geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build heading arrow as triangles.
     */
    private fun buildHeadingGeometry(position: GeoPoint?, headingRad: Float): FloatArray {
        if (position == null) return FloatArray(0)

        val vertices = mutableListOf<Float>()

        val (cx, cy) = transformer.geoPointToNdc(position)

        // Arrow size in NDC
        val mapState = transformer.getMapState()
        val arrowSizeNdc = config.headingIndicatorSize * 2f / mapState.viewWidth

        // Adjust heading for map rotation
        val adjustedHeading = transformer.adjustHeadingForMapRotation(headingRad)

        // Arrow points up (in the direction of heading)
        // Heading: 0 = North (up on screen), π/2 = East (right)
        val angle = adjustedHeading - PI.toFloat() / 2f  // Adjust for screen coordinates

        // Arrow vertices (triangle pointing in heading direction)
        val tipX = cx + cos(angle) * arrowSizeNdc
        val tipY = cy + sin(angle) * arrowSizeNdc

        // Base of arrow (perpendicular to heading)
        val baseAngle1 = angle + PI.toFloat() * 0.75f
        val baseAngle2 = angle - PI.toFloat() * 0.75f
        val baseRadius = arrowSizeNdc * 0.4f

        val base1X = cx + cos(baseAngle1) * baseRadius
        val base1Y = cy + sin(baseAngle1) * baseRadius
        val base2X = cx + cos(baseAngle2) * baseRadius
        val base2Y = cy + sin(baseAngle2) * baseRadius

        // Main triangle
        addVertex(vertices, tipX, tipY, colors.heading)
        addVertex(vertices, base1X, base1Y, colors.heading)
        addVertex(vertices, base2X, base2Y, colors.heading)

        return vertices.toFloatArray()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Position Dot Geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build position dot as filled circle.
     */
    private fun buildPositionDotGeometry(position: GeoPoint?): FloatArray {
        if (position == null) return FloatArray(0)

        val vertices = mutableListOf<Float>()

        val (cx, cy) = transformer.geoPointToNdc(position)

        // Dot size in NDC
        val mapState = transformer.getMapState()
        val dotRadius = 8f * 2f / mapState.viewWidth  // 8 pixel radius

        // Filled circle as triangle fan
        for (i in 0 until CIRCLE_SEGMENTS) {
            val angle1 = 2f * PI.toFloat() * i / CIRCLE_SEGMENTS
            val angle2 = 2f * PI.toFloat() * (i + 1) / CIRCLE_SEGMENTS

            val x1 = cx + cos(angle1) * dotRadius
            val y1 = cy + sin(angle1) * dotRadius
            val x2 = cx + cos(angle2) * dotRadius
            val y2 = cy + sin(angle2) * dotRadius

            addVertex(vertices, cx, cy, colors.positionDot)
            addVertex(vertices, x1, y1, colors.positionDot)
            addVertex(vertices, x2, y2, colors.positionDot)
        }

        // White border
        val borderRadius = dotRadius * 1.3f
        val borderColor = Color.WHITE

        for (i in 0 until CIRCLE_SEGMENTS) {
            val angle1 = 2f * PI.toFloat() * i / CIRCLE_SEGMENTS
            val angle2 = 2f * PI.toFloat() * (i + 1) / CIRCLE_SEGMENTS

            val innerX1 = cx + cos(angle1) * dotRadius
            val innerY1 = cy + sin(angle1) * dotRadius
            val innerX2 = cx + cos(angle2) * dotRadius
            val innerY2 = cy + sin(angle2) * dotRadius

            val outerX1 = cx + cos(angle1) * borderRadius
            val outerY1 = cy + sin(angle1) * borderRadius
            val outerX2 = cx + cos(angle2) * borderRadius
            val outerY2 = cy + sin(angle2) * borderRadius

            // Ring segment (2 triangles)
            addVertex(vertices, innerX1, innerY1, borderColor)
            addVertex(vertices, outerX1, outerY1, borderColor)
            addVertex(vertices, innerX2, innerY2, borderColor)

            addVertex(vertices, innerX2, innerY2, borderColor)
            addVertex(vertices, outerX1, outerY1, borderColor)
            addVertex(vertices, outerX2, outerY2, borderColor)
        }

        return vertices.toFloatArray()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun addVertex(vertices: MutableList<Float>, x: Float, y: Float, color: Int) {
        vertices.add(x)
        vertices.add(y)
        vertices.add(Color.red(color) / 255f)
        vertices.add(Color.green(color) / 255f)
        vertices.add(Color.blue(color) / 255f)
        vertices.add(Color.alpha(color) / 255f)
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val baseAlpha = Color.alpha(color) / 255f
        val newAlpha = (baseAlpha * alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(
            newAlpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }
}
