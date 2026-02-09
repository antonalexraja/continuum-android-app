/**
 * Offline Map Controller (OSMDroid)
 *
 * MapController implementation using OSMDroid for offline OpenStreetMap tiles.
 * Works without internet connectivity when offline map files are available.
 *
 * ## Offline Map Files
 *
 * Place .map or .mbtiles files in:
 * - Internal: /data/data/com.navigator.core/files/osmdroid/
 * - External: /sdcard/osmdroid/
 *
 * ## Features
 *
 * - Offline-first operation
 * - Online tile fallback when network available
 * - Smooth marker animation
 * - Heading-aware marker rotation
 * - Accuracy circle display
 */
package com.continuum.navigator.core.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/**
 * OSMDroid implementation of MapController for offline maps.
 */
class OfflineMapController(
    private val context: Context,
) : BaseMapController() {

    companion object {
        private const val TAG = "OfflineMapController"
        private const val ANIMATION_DURATION_MS = 300L
        private const val DEFAULT_ZOOM = 17.0
        private const val MARKER_SIZE_DP = 24

        // Common offline map directories
        private val OFFLINE_MAP_PATHS = listOf(
            "osmdroid",
            "maps",
            "offline_maps",
        )
    }

    override val provider: MapProvider = MapProvider.OFFLINE_OSM

    private var _mapView: MapView? = null
    override val mapView: View
        get() = _mapView ?: throw IllegalStateException("Map not initialized")

    private var positionMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    private var positionAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null

    private var animatedPosition: GeoPoint = GeoPoint(0.0, 0.0)
    private var animatedRotation: Float = 0f

    override val isReady: Boolean
        get() = _mapView != null

    /**
     * Get the underlying OSMDroid MapView for advanced operations.
     * Use with caution - prefer using MapController interface methods.
     */
    fun getMapView(): MapView? = _mapView

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun initialize() {
        Log.d(TAG, "Initializing OSMDroid")

        // Configure OSMDroid
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")

            // Create directories if needed
            osmdroidBasePath.mkdirs()
            osmdroidTileCache.mkdirs()
        }

        // Check for offline maps
        checkOfflineMaps()

        // Create map view
        _mapView = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)

            // Initial zoom and position
            controller.setZoom(currentZoom.toDouble())
            controller.setCenter(GeoPoint(0.0, 0.0))

            // Enable user gesture detection for follow mode
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) {
                    notifyUserInteraction()
                    cameraMoveListener?.invoke()
                }
                false // Don't consume event
            }

            // Add map listener for scroll/zoom events
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    cameraMoveListener?.invoke()
                    return false
                }
                override fun onZoom(event: ZoomEvent?): Boolean {
                    cameraMoveListener?.invoke()
                    return false
                }
            })
        }

        Log.i(TAG, "OSMDroid map initialized")
        callback?.onMapReady()
    }

    private fun checkOfflineMaps() {
        val offlinePaths = mutableListOf<File>()

        // Internal storage
        for (dir in OFFLINE_MAP_PATHS) {
            val path = File(context.filesDir, dir)
            if (path.exists() && path.isDirectory) {
                offlinePaths.add(path)
            }
        }

        // External storage (if available)
        context.getExternalFilesDir(null)?.let { externalDir ->
            for (dir in OFFLINE_MAP_PATHS) {
                val path = File(externalDir, dir)
                if (path.exists() && path.isDirectory) {
                    offlinePaths.add(path)
                }
            }
        }

        if (offlinePaths.isEmpty()) {
            Log.w(TAG, "No offline map directories found. Maps will use online tiles.")
        } else {
            val mapFiles = offlinePaths.flatMap { dir ->
                dir.listFiles { file ->
                    file.extension.lowercase() in listOf("map", "mbtiles", "sqlite")
                }?.toList() ?: emptyList()
            }

            if (mapFiles.isEmpty()) {
                Log.w(TAG, "No offline map files found in: ${offlinePaths.joinToString()}")
            } else {
                Log.i(TAG, "Found ${mapFiles.size} offline map files: ${mapFiles.joinToString { it.name }}")
            }
        }
    }

    override fun onResume() {
        _mapView?.onResume()
    }

    override fun onPause() {
        _mapView?.onPause()
        cancelAnimations()
    }

    override fun onDestroy() {
        cancelAnimations()
        _mapView?.onDetach()
        _mapView = null
        positionMarker = null
        accuracyCircle = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Position Updates
    // ═══════════════════════════════════════════════════════════════════════

    override fun updatePosition(position: MapPosition, animate: Boolean) {
        val map = _mapView ?: return
        if (!position.isValid) return

        val newGeoPoint = GeoPoint(position.latitude, position.longitude)
        _lastPosition = position

        if (animate && positionMarker != null) {
            animateMarkerPosition(newGeoPoint)
            animateMarkerRotation(position.headingDegrees)
        } else {
            // Direct update (first position or animation disabled)
            animatedPosition = newGeoPoint
            animatedRotation = position.headingDegrees
            updateMarkerDirect()
        }

        // Update accuracy circle
        updateAccuracyCircle(newGeoPoint, position.accuracyMeters)

        // Follow mode: center on position
        if (isFollowing) {
            if (animate) {
                map.controller.animateTo(newGeoPoint)
            } else {
                map.controller.setCenter(newGeoPoint)
            }
        }

        Log.v(TAG, "Position updated: ${position.latitude}, ${position.longitude}, heading=${position.headingDegrees}")
    }

    private fun animateMarkerPosition(targetPosition: GeoPoint) {
        positionAnimator?.cancel()

        val startPosition = animatedPosition
        positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val lat = startPosition.latitude + (targetPosition.latitude - startPosition.latitude) * fraction
                val lng = startPosition.longitude + (targetPosition.longitude - startPosition.longitude) * fraction
                animatedPosition = GeoPoint(lat, lng)
                updateMarkerDirect()
            }
            start()
        }
    }

    private fun animateMarkerRotation(targetRotation: Float) {
        rotationAnimator?.cancel()

        val startRotation = animatedRotation
        var endRotation = targetRotation

        // Find shortest rotation path
        var delta = endRotation - startRotation
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        endRotation = startRotation + delta

        rotationAnimator = ValueAnimator.ofFloat(startRotation, endRotation).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animatedRotation = animation.animatedValue as Float
                positionMarker?.rotation = animatedRotation
                _mapView?.invalidate()
            }
            start()
        }
    }

    private fun updateMarkerDirect() {
        val map = _mapView ?: return

        if (positionMarker == null) {
            // Create marker on first update
            positionMarker = Marker(map).apply {
                icon = createNavigationMarker()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                position = animatedPosition
                rotation = animatedRotation
            }
            map.overlays.add(positionMarker)
        } else {
            positionMarker?.position = animatedPosition
            positionMarker?.rotation = animatedRotation
        }

        map.invalidate()
    }

    private fun updateAccuracyCircle(center: GeoPoint, radiusMeters: Float) {
        val map = _mapView ?: return

        if (!showAccuracyCircle || radiusMeters <= 0) {
            accuracyCircle?.let {
                map.overlays.remove(it)
                accuracyCircle = null
            }
            return
        }

        // Create circle as polygon (OSMDroid doesn't have built-in circle)
        val circlePoints = createCirclePoints(center, radiusMeters.toDouble(), 36)

        if (accuracyCircle == null) {
            accuracyCircle = Polygon(map).apply {
                fillPaint.color = Color.argb(32, 66, 133, 244)
                outlinePaint.color = Color.argb(128, 66, 133, 244)
                outlinePaint.strokeWidth = 2f
                points = circlePoints
            }
            // Insert circle below marker
            val markerIndex = map.overlays.indexOf(positionMarker)
            if (markerIndex > 0) {
                map.overlays.add(markerIndex, accuracyCircle)
            } else {
                map.overlays.add(0, accuracyCircle)
            }
        } else {
            accuracyCircle?.points = circlePoints
        }

        map.invalidate()
    }

    private fun createCirclePoints(center: GeoPoint, radiusMeters: Double, numPoints: Int): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        val earthRadius = 6371000.0 // meters

        for (i in 0 until numPoints) {
            val angle = Math.toRadians((360.0 * i) / numPoints)
            val lat = Math.asin(
                Math.sin(Math.toRadians(center.latitude)) * Math.cos(radiusMeters / earthRadius) +
                Math.cos(Math.toRadians(center.latitude)) * Math.sin(radiusMeters / earthRadius) * Math.cos(angle)
            )
            val lng = Math.toRadians(center.longitude) + Math.atan2(
                Math.sin(angle) * Math.sin(radiusMeters / earthRadius) * Math.cos(Math.toRadians(center.latitude)),
                Math.cos(radiusMeters / earthRadius) - Math.sin(Math.toRadians(center.latitude)) * Math.sin(lat)
            )
            points.add(GeoPoint(Math.toDegrees(lat), Math.toDegrees(lng)))
        }

        // Close the polygon
        if (points.isNotEmpty()) {
            points.add(points.first())
        }

        return points
    }

    private fun cancelAnimations() {
        positionAnimator?.cancel()
        rotationAnimator?.cancel()
        positionAnimator = null
        rotationAnimator = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Camera Control
    // ═══════════════════════════════════════════════════════════════════════

    override fun centerOn(latitude: Double, longitude: Double, animate: Boolean) {
        val map = _mapView ?: return
        val geoPoint = GeoPoint(latitude, longitude)

        map.controller.setZoom(currentZoom.toDouble())
        if (animate) {
            map.controller.animateTo(geoPoint)
        } else {
            map.controller.setCenter(geoPoint)
        }
    }

    override fun setZoom(zoom: Float) {
        super.setZoom(zoom)
        _mapView?.controller?.setZoom(currentZoom.toDouble())
    }

    override fun getZoom(): Float {
        return _mapView?.zoomLevelDouble?.toFloat() ?: currentZoom
    }

    override fun setMapType(type: Int) {
        // OSMDroid has different tile sources rather than map types
        _mapView?.setTileSource(
            when (type) {
                0 -> TileSourceFactory.MAPNIK
                1 -> TileSourceFactory.OpenTopo
                else -> TileSourceFactory.MAPNIK
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Marker Icon
    // ═══════════════════════════════════════════════════════════════════════

    private fun createNavigationMarker(): BitmapDrawable {
        val sizePx = (MARKER_SIZE_DP * context.resources.displayMetrics.density).toInt()

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Outer circle (blue)
        paint.color = Color.rgb(66, 133, 244)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

        // Inner circle (white)
        paint.color = Color.WHITE
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 3f, paint)

        // Direction indicator (triangle pointing up)
        paint.color = Color.rgb(66, 133, 244)
        paint.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(sizePx / 2f, sizePx * 0.15f)
        path.lineTo(sizePx * 0.35f, sizePx * 0.4f)
        path.lineTo(sizePx * 0.65f, sizePx * 0.4f)
        path.close()
        canvas.drawPath(path, paint)

        return BitmapDrawable(context.resources, bitmap)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Camera State
    // ═══════════════════════════════════════════════════════════════════════

    override fun getCameraCenter(): Pair<Double, Double>? {
        val center = _mapView?.mapCenter ?: return null
        return Pair(center.latitude, center.longitude)
    }

    override fun getCameraRotation(): Float {
        return _mapView?.mapOrientation ?: 0f
    }
}
