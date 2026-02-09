/**
 * Google Maps Controller
 *
 * MapController implementation using Google Maps SDK.
 * Requires internet connectivity and a valid API key.
 *
 * ## Setup
 *
 * 1. Add API key to local.properties: MAPS_API_KEY=your_key_here
 * 2. Ensure internet permission in manifest
 *
 * ## Features
 *
 * - Smooth marker animation using ValueAnimator
 * - Heading-aware marker rotation
 * - Optional accuracy circle
 * - Follow mode with auto-centering
 */
package com.continuum.navigator.core.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*

/**
 * Google Maps implementation of MapController.
 */
class GoogleMapController(
    private val context: Context,
) : BaseMapController(), OnMapReadyCallback {

    companion object {
        private const val TAG = "GoogleMapController"
        private const val ANIMATION_DURATION_MS = 300L
        private const val DEFAULT_ZOOM = 17f
        private const val MARKER_SIZE_DP = 24
    }

    override val provider: MapProvider = MapProvider.GOOGLE_MAPS

    private var _mapView: MapView? = null
    override val mapView: View
        get() = _mapView ?: throw IllegalStateException("Map not initialized")

    private var googleMap: GoogleMap? = null
    private var positionMarker: Marker? = null
    private var accuracyCircle: Circle? = null

    private var positionAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null

    private var animatedPosition: LatLng = LatLng(0.0, 0.0)
    private var animatedRotation: Float = 0f

    override val isReady: Boolean
        get() = googleMap != null

    /**
     * Get the underlying GoogleMap instance for advanced operations.
     * Use with caution - prefer using MapController interface methods.
     */
    fun getGoogleMap(): GoogleMap? = googleMap

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun initialize() {
        Log.d(TAG, "Initializing Google Maps")
        _mapView = MapView(context).apply {
            onCreate(null)
            getMapAsync(this@GoogleMapController)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        Log.i(TAG, "Google Map ready")
        googleMap = map

        // Configure map settings
        map.apply {
            uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isMyLocationButtonEnabled = false // We manage position ourselves
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }

            // Disable built-in my location (we use NavigationOutput)
            isMyLocationEnabled = false

            // Map type
            mapType = GoogleMap.MAP_TYPE_NORMAL

            // Initial camera position
            moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), currentZoom))

            // Listen for user camera movements
            setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    notifyUserInteraction()
                }
            }

            // Notify overlay of camera changes for synchronization
            setOnCameraMoveListener {
                cameraMoveListener?.invoke()
            }
        }

        callback?.onMapReady()
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
        positionMarker?.remove()
        accuracyCircle?.remove()
        _mapView?.onDestroy()
        _mapView = null
        googleMap = null
        positionMarker = null
        accuracyCircle = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Position Updates
    // ═══════════════════════════════════════════════════════════════════════

    override fun updatePosition(position: MapPosition, animate: Boolean) {
        val map = googleMap ?: return
        if (!position.isValid) return

        val newLatLng = LatLng(position.latitude, position.longitude)
        _lastPosition = position

        if (animate && positionMarker != null) {
            animateMarkerPosition(newLatLng)
            animateMarkerRotation(position.headingDegrees)
        } else {
            // Direct update (first position or animation disabled)
            animatedPosition = newLatLng
            animatedRotation = position.headingDegrees
            updateMarkerDirect()
        }

        // Update accuracy circle
        updateAccuracyCircle(newLatLng, position.accuracyMeters)

        // Follow mode: center camera on position
        if (isFollowing) {
            val cameraUpdate = CameraUpdateFactory.newLatLng(newLatLng)
            if (animate) {
                map.animateCamera(cameraUpdate, ANIMATION_DURATION_MS.toInt(), null)
            } else {
                map.moveCamera(cameraUpdate)
            }
        }

        Log.v(TAG, "Position updated: ${position.latitude}, ${position.longitude}, heading=${position.headingDegrees}")
    }

    private fun animateMarkerPosition(targetPosition: LatLng) {
        positionAnimator?.cancel()

        val startPosition = animatedPosition
        positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val lat = startPosition.latitude + (targetPosition.latitude - startPosition.latitude) * fraction
                val lng = startPosition.longitude + (targetPosition.longitude - startPosition.longitude) * fraction
                animatedPosition = LatLng(lat, lng)
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
            }
            start()
        }
    }

    private fun updateMarkerDirect() {
        val map = googleMap ?: return

        if (positionMarker == null) {
            // Create marker on first update
            val markerIcon = createNavigationMarker()
            positionMarker = map.addMarker(
                MarkerOptions()
                    .position(animatedPosition)
                    .icon(markerIcon)
                    .anchor(0.5f, 0.5f)
                    .rotation(animatedRotation)
                    .flat(true)
            )
        } else {
            positionMarker?.position = animatedPosition
            positionMarker?.rotation = animatedRotation
        }
    }

    private fun updateAccuracyCircle(center: LatLng, radiusMeters: Float) {
        val map = googleMap ?: return

        if (!showAccuracyCircle || radiusMeters <= 0) {
            accuracyCircle?.remove()
            accuracyCircle = null
            return
        }

        if (accuracyCircle == null) {
            accuracyCircle = map.addCircle(
                CircleOptions()
                    .center(center)
                    .radius(radiusMeters.toDouble())
                    .strokeColor(Color.argb(128, 66, 133, 244))
                    .strokeWidth(2f)
                    .fillColor(Color.argb(32, 66, 133, 244))
            )
        } else {
            accuracyCircle?.center = center
            accuracyCircle?.radius = radiusMeters.toDouble()
        }
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
        val map = googleMap ?: return
        val latLng = LatLng(latitude, longitude)
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, currentZoom)

        if (animate) {
            map.animateCamera(cameraUpdate)
        } else {
            map.moveCamera(cameraUpdate)
        }
    }

    override fun setZoom(zoom: Float) {
        super.setZoom(zoom)
        googleMap?.let { map ->
            val cameraUpdate = CameraUpdateFactory.zoomTo(currentZoom)
            map.animateCamera(cameraUpdate)
        }
    }

    override fun setMapType(type: Int) {
        googleMap?.mapType = when (type) {
            0 -> GoogleMap.MAP_TYPE_NORMAL
            1 -> GoogleMap.MAP_TYPE_SATELLITE
            2 -> GoogleMap.MAP_TYPE_HYBRID
            3 -> GoogleMap.MAP_TYPE_TERRAIN
            else -> GoogleMap.MAP_TYPE_NORMAL
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Marker Icon
    // ═══════════════════════════════════════════════════════════════════════

    private fun createNavigationMarker(): BitmapDescriptor {
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

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Camera State
    // ═══════════════════════════════════════════════════════════════════════

    override fun getCameraCenter(): Pair<Double, Double>? {
        val pos = googleMap?.cameraPosition ?: return null
        return Pair(pos.target.latitude, pos.target.longitude)
    }

    override fun getCameraRotation(): Float {
        return googleMap?.cameraPosition?.bearing ?: 0f
    }
}
