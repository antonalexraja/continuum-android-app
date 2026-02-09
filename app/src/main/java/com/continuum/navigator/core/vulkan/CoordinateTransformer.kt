/**
 * Coordinate Transformations for Map Overlay
 *
 * Converts between geographic coordinates (LLA) and screen/NDC coordinates
 * for proper overlay alignment with map views.
 *
 * ## Coordinate Systems
 *
 * 1. **Geographic (LLA)**: WGS84 latitude/longitude
 * 2. **Screen**: Pixel coordinates (0,0 = top-left)
 * 3. **NDC**: Normalized Device Coordinates (-1 to +1, Y up)
 *
 * ## Map Projection
 *
 * Uses Web Mercator (EPSG:3857) consistent with Google Maps and OSM.
 *
 * ## Accuracy
 *
 * For small areas (< 10km), the linear approximation is sufficient.
 * For larger areas, full Mercator math is used.
 */
package com.continuum.navigator.core.vulkan

import kotlin.math.*

/**
 * Coordinate transformer for map overlays.
 */
class CoordinateTransformer {

    companion object {
        private const val TAG = "CoordinateTransformer"

        // WGS84 constants
        private const val EARTH_RADIUS_M = 6378137.0
        private const val EARTH_CIRCUMFERENCE_M = 2 * PI * EARTH_RADIUS_M

        // Web Mercator limits
        private const val MAX_LATITUDE = 85.05112878

        /**
         * Convert latitude to Web Mercator Y.
         */
        fun latitudeToMercatorY(latitude: Double): Double {
            val clampedLat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
            val latRad = Math.toRadians(clampedLat)
            return EARTH_RADIUS_M * ln(tan(PI / 4 + latRad / 2))
        }

        /**
         * Convert longitude to Web Mercator X.
         */
        fun longitudeToMercatorX(longitude: Double): Double {
            return EARTH_RADIUS_M * Math.toRadians(longitude)
        }

        /**
         * Convert Web Mercator Y to latitude.
         */
        fun mercatorYToLatitude(y: Double): Double {
            return Math.toDegrees(2 * atan(exp(y / EARTH_RADIUS_M)) - PI / 2)
        }

        /**
         * Convert Web Mercator X to longitude.
         */
        fun mercatorXToLongitude(x: Double): Double {
            return Math.toDegrees(x / EARTH_RADIUS_M)
        }

        /**
         * Calculate meters per pixel at given latitude and zoom.
         *
         * Based on Web Mercator tile system:
         * - At zoom 0, world is 256 pixels wide
         * - Each zoom doubles resolution
         */
        fun metersPerPixel(latitude: Double, zoomLevel: Float): Double {
            val latRad = Math.toRadians(latitude)
            return EARTH_CIRCUMFERENCE_M * cos(latRad) / (256 * 2.0.pow(zoomLevel.toDouble()))
        }

        /**
         * Calculate ground distance in meters between two points.
         * Uses Haversine formula.
         */
        fun haversineDistance(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double,
        ): Double {
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))

            return EARTH_RADIUS_M * c
        }

        /**
         * Calculate bearing from point 1 to point 2.
         * Returns bearing in radians (0 = North, π/2 = East).
         */
        fun bearing(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double,
        ): Double {
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val dLon = Math.toRadians(lon2 - lon1)

            val y = sin(dLon) * cos(lat2Rad)
            val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

            return atan2(y, x)
        }
    }

    // Current map view state
    private var mapState: MapViewState = MapViewState.DEFAULT

    // Cached values for optimization
    private var cachedCenterMercatorX: Double = 0.0
    private var cachedCenterMercatorY: Double = 0.0
    private var cachedPixelsPerMeter: Double = 1.0
    private var cachedRotationRad: Double = 0.0
    private var cachedCosRotation: Double = 1.0
    private var cachedSinRotation: Double = 0.0

    /**
     * Update the map view state.
     * Call this whenever the map camera changes.
     */
    fun updateMapState(state: MapViewState) {
        mapState = state

        // Cache expensive calculations
        cachedCenterMercatorX = longitudeToMercatorX(state.centerLongitude)
        cachedCenterMercatorY = latitudeToMercatorY(state.centerLatitude)
        cachedPixelsPerMeter = 1.0 / metersPerPixel(state.centerLatitude, state.zoomLevel)
        cachedRotationRad = Math.toRadians(state.rotation.toDouble())
        cachedCosRotation = cos(cachedRotationRad)
        cachedSinRotation = sin(cachedRotationRad)
    }

    /**
     * Convert geographic coordinates to screen coordinates.
     *
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @return Screen coordinates (pixels from top-left)
     */
    fun geoToScreen(latitude: Double, longitude: Double): ScreenPoint {
        // Convert to Mercator
        val mercatorX = longitudeToMercatorX(longitude)
        val mercatorY = latitudeToMercatorY(latitude)

        // Offset from center
        val dxMeters = mercatorX - cachedCenterMercatorX
        val dyMeters = mercatorY - cachedCenterMercatorY

        // Convert to pixels (note: Y is inverted for screen coords)
        var dxPixels = dxMeters * cachedPixelsPerMeter
        var dyPixels = -dyMeters * cachedPixelsPerMeter  // Negate Y

        // Apply rotation
        if (mapState.rotation != 0f) {
            val rotatedX = dxPixels * cachedCosRotation - dyPixels * cachedSinRotation
            val rotatedY = dxPixels * cachedSinRotation + dyPixels * cachedCosRotation
            dxPixels = rotatedX
            dyPixels = rotatedY
        }

        // Offset to screen center
        val screenX = mapState.viewWidth / 2f + dxPixels.toFloat()
        val screenY = mapState.viewHeight / 2f + dyPixels.toFloat()

        return ScreenPoint(screenX, screenY)
    }

    /**
     * Convert screen coordinates to NDC (Normalized Device Coordinates).
     *
     * @param screenX X in pixels (0 = left)
     * @param screenY Y in pixels (0 = top)
     * @return NDC coordinates (-1 to +1, Y up)
     */
    fun screenToNdc(screenX: Float, screenY: Float): Pair<Float, Float> {
        val ndcX = (screenX / mapState.viewWidth) * 2f - 1f
        val ndcY = 1f - (screenY / mapState.viewHeight) * 2f  // Flip Y
        return Pair(ndcX, ndcY)
    }

    /**
     * Convert geographic coordinates directly to NDC.
     *
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @return NDC coordinates (-1 to +1, Y up)
     */
    fun geoToNdc(latitude: Double, longitude: Double): Pair<Float, Float> {
        val screen = geoToScreen(latitude, longitude)
        return screenToNdc(screen.x, screen.y)
    }

    /**
     * Convert a GeoPoint to NDC.
     */
    fun geoPointToNdc(point: GeoPoint): Pair<Float, Float> {
        return geoToNdc(point.latitude, point.longitude)
    }

    /**
     * Convert distance in meters to pixels at current zoom.
     */
    fun metersToPixels(meters: Double): Float {
        return (meters * cachedPixelsPerMeter).toFloat()
    }

    /**
     * Convert distance in meters to NDC units.
     */
    fun metersToNdc(meters: Double): Float {
        val pixels = metersToPixels(meters)
        return pixels * 2f / mapState.viewWidth
    }

    /**
     * Calculate pixel distance between two geo points.
     */
    fun geoDistancePixels(point1: GeoPoint, point2: GeoPoint): Float {
        val meters = haversineDistance(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
        )
        return metersToPixels(meters)
    }

    /**
     * Check if a geo point is visible in current view.
     */
    fun isVisible(latitude: Double, longitude: Double, marginPixels: Float = 0f): Boolean {
        val screen = geoToScreen(latitude, longitude)
        return screen.x >= -marginPixels &&
            screen.x <= mapState.viewWidth + marginPixels &&
            screen.y >= -marginPixels &&
            screen.y <= mapState.viewHeight + marginPixels
    }

    /**
     * Get current map state.
     */
    fun getMapState(): MapViewState = mapState

    /**
     * Get heading angle adjusted for map rotation.
     * Returns angle in radians where 0 = screen up.
     */
    fun adjustHeadingForMapRotation(headingRad: Float): Float {
        return headingRad - cachedRotationRad.toFloat()
    }
}

/**
 * Extension function to convert list of GeoPoints to NDC vertices.
 */
fun List<GeoPoint>.toNdcCoordinates(transformer: CoordinateTransformer): List<Pair<Float, Float>> {
    return map { transformer.geoPointToNdc(it) }
}
