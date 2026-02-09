/**
 * Route Renderer
 *
 * Renders route polylines and markers on the map.
 * Works with both Google Maps and OSMDroid.
 *
 * ## Features
 *
 * - Route polyline with configurable style
 * - Origin/destination markers
 * - Maneuver markers (optional)
 * - Active leg highlighting
 *
 * ## Usage
 *
 * ```kotlin
 * val renderer = RouteRenderer()
 * renderer.attachToMap(mapController)
 * renderer.showRoute(route)
 * ```
 */
package com.continuum.navigator.core.routing

import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.google.android.gms.maps.model.*
import com.continuum.navigator.core.map.GoogleMapController
import com.continuum.navigator.core.map.MapController
import com.continuum.navigator.core.map.OfflineMapController
import com.google.android.gms.maps.CameraUpdateFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline as OsmPolyline

/**
 * Configuration for route rendering.
 */
data class RouteRenderConfig(
    /** Primary route line color */
    val routeColor: Int = Color.parseColor("#4285F4"),  // Google blue
    /** Alternative route line color */
    val altRouteColor: Int = Color.parseColor("#9AA0A6"),  // Gray
    /** Traveled portion color */
    val traveledColor: Int = Color.parseColor("#A4C8F0"),  // Light blue
    /** Route line width in pixels */
    val routeWidth: Float = 12f,
    /** Whether to show origin marker */
    val showOriginMarker: Boolean = true,
    /** Whether to show destination marker */
    val showDestinationMarker: Boolean = true,
    /** Whether to show maneuver markers */
    val showManeuverMarkers: Boolean = false,
)

/**
 * Renders routes on the map.
 */
class RouteRenderer(
    private val config: RouteRenderConfig = RouteRenderConfig(),
) {
    companion object {
        private const val TAG = "RouteRenderer"
    }

    // Current state
    private var currentRoute: Route? = null
    private var mapController: MapController? = null

    // Google Maps overlays
    private var googlePolyline: Polyline? = null
    private var googleTraveledPolyline: Polyline? = null
    private var googleOriginMarker: com.google.android.gms.maps.model.Marker? = null
    private var googleDestMarker: com.google.android.gms.maps.model.Marker? = null
    private var googleManeuverMarkers: MutableList<com.google.android.gms.maps.model.Marker> = mutableListOf()

    // OSMDroid overlays
    private var osmPolyline: OsmPolyline? = null
    private var osmTraveledPolyline: OsmPolyline? = null
    private var osmOriginMarker: Marker? = null
    private var osmDestMarker: Marker? = null
    private var osmManeuverMarkers: MutableList<Marker> = mutableListOf()

    /**
     * Attach to a map controller.
     */
    fun attachToMap(controller: MapController) {
        clear()
        mapController = controller
        Log.d(TAG, "Attached to map: ${controller.provider}")
    }

    /**
     * Detach from the map.
     */
    fun detach() {
        clear()
        mapController = null
    }

    /**
     * Show a route on the map.
     */
    fun showRoute(route: Route) {
        clear()
        currentRoute = route

        val controller = mapController ?: run {
            Log.w(TAG, "No map controller attached")
            return
        }

        if (route.polyline.isEmpty()) {
            Log.w(TAG, "Route has no polyline points")
            return
        }

        Log.d(TAG, "Showing route: ${route.polyline.size} points, ${route.distanceText}")

        when (controller) {
            is GoogleMapController -> renderOnGoogleMaps(controller, route)
            is OfflineMapController -> renderOnOsmDroid(controller, route)
            else -> Log.w(TAG, "Unsupported map controller: ${controller::class.simpleName}")
        }
    }

    /**
     * Update the traveled portion of the route.
     *
     * @param traveledPoints Points that have been traveled
     */
    fun updateTraveledPortion(traveledPoints: List<RoutePoint>) {
        val controller = mapController ?: return

        when (controller) {
            is GoogleMapController -> updateGoogleTraveledPortion(traveledPoints)
            is OfflineMapController -> updateOsmTraveledPortion(controller, traveledPoints)
        }
    }

    /**
     * Clear all route overlays.
     */
    fun clear() {
        // Google Maps cleanup
        googlePolyline?.remove()
        googlePolyline = null
        googleTraveledPolyline?.remove()
        googleTraveledPolyline = null
        googleOriginMarker?.remove()
        googleOriginMarker = null
        googleDestMarker?.remove()
        googleDestMarker = null
        googleManeuverMarkers.forEach { it.remove() }
        googleManeuverMarkers.clear()

        // OSMDroid cleanup
        osmPolyline?.let { polyline ->
            (mapController as? OfflineMapController)?.let { controller ->
                removeOsmOverlay(controller, polyline)
            }
        }
        osmPolyline = null
        osmTraveledPolyline?.let { polyline ->
            (mapController as? OfflineMapController)?.let { controller ->
                removeOsmOverlay(controller, polyline)
            }
        }
        osmTraveledPolyline = null
        osmOriginMarker?.let { marker ->
            (mapController as? OfflineMapController)?.let { controller ->
                removeOsmOverlay(controller, marker)
            }
        }
        osmOriginMarker = null
        osmDestMarker?.let { marker ->
            (mapController as? OfflineMapController)?.let { controller ->
                removeOsmOverlay(controller, marker)
            }
        }
        osmDestMarker = null
        osmManeuverMarkers.forEach { marker ->
            (mapController as? OfflineMapController)?.let { controller ->
                removeOsmOverlay(controller, marker)
            }
        }
        osmManeuverMarkers.clear()

        currentRoute = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Google Maps Rendering
    // ═══════════════════════════════════════════════════════════════════════

    private fun renderOnGoogleMaps(controller: GoogleMapController, route: Route) {
        val googleMap = controller.getGoogleMap() ?: run {
            Log.w(TAG, "Google Map not ready")
            return
        }

        // Draw route polyline
        val latLngs = route.polyline.map { LatLng(it.latitude, it.longitude) }

        googlePolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .width(config.routeWidth)
                .color(config.routeColor)
                .geodesic(true)
                .jointType(JointType.ROUND)
                .startCap(RoundCap())
                .endCap(RoundCap())
        )

        // Origin marker
        if (config.showOriginMarker && route.polyline.isNotEmpty()) {
            val origin = route.polyline.first()
            googleOriginMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(origin.latitude, origin.longitude))
                    .title("Start")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
        }

        // Destination marker
        if (config.showDestinationMarker && route.polyline.isNotEmpty()) {
            val dest = route.polyline.last()
            googleDestMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(dest.latitude, dest.longitude))
                    .title("Destination")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
        }

        // Maneuver markers
        if (config.showManeuverMarkers) {
            route.allManeuvers.forEach { maneuver ->
                if (maneuver.type != ManeuverType.DEPART && maneuver.type != ManeuverType.ARRIVE) {
                    val marker = googleMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(maneuver.location.latitude, maneuver.location.longitude))
                            .title(maneuver.shortInstruction)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                            .anchor(0.5f, 0.5f)
                    )
                    marker?.let { googleManeuverMarkers.add(it) }
                }
            }
        }

        // Fit bounds to show entire route
        route.bounds?.let { bounds ->
            val latLngBounds = LatLngBounds(
                LatLng(bounds.southLatitude, bounds.westLongitude),
                LatLng(bounds.northLatitude, bounds.eastLongitude)
            )
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngBounds(latLngBounds, 100)
            )
        }

        Log.d(TAG, "Route rendered on Google Maps")
    }

    private fun updateGoogleTraveledPortion(traveledPoints: List<RoutePoint>) {
        val googleMap = (mapController as? GoogleMapController)?.getGoogleMap() ?: return

        // Remove old traveled polyline
        googleTraveledPolyline?.remove()

        if (traveledPoints.size < 2) return

        val latLngs = traveledPoints.map { LatLng(it.latitude, it.longitude) }

        googleTraveledPolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .width(config.routeWidth)
                .color(config.traveledColor)
                .geodesic(true)
                .jointType(JointType.ROUND)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OSMDroid Rendering
    // ═══════════════════════════════════════════════════════════════════════

    private fun renderOnOsmDroid(controller: OfflineMapController, route: Route) {
        val mapView = controller.getMapView() ?: run {
            Log.w(TAG, "OSMDroid MapView not ready")
            return
        }

        // Create polyline
        val geoPoints = route.polyline.map { GeoPoint(it.latitude, it.longitude) }

        osmPolyline = OsmPolyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = config.routeColor
            outlinePaint.strokeWidth = config.routeWidth
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        mapView.overlays.add(osmPolyline)

        // Origin marker
        if (config.showOriginMarker && route.polyline.isNotEmpty()) {
            val origin = route.polyline.first()
            osmOriginMarker = Marker(mapView).apply {
                position = GeoPoint(origin.latitude, origin.longitude)
                title = "Start"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(osmOriginMarker)
        }

        // Destination marker
        if (config.showDestinationMarker && route.polyline.isNotEmpty()) {
            val dest = route.polyline.last()
            osmDestMarker = Marker(mapView).apply {
                position = GeoPoint(dest.latitude, dest.longitude)
                title = "Destination"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(osmDestMarker)
        }

        // Zoom to show route
        route.bounds?.let { bounds ->
            val boundingBox = BoundingBox(
                bounds.northLatitude,
                bounds.eastLongitude,
                bounds.southLatitude,
                bounds.westLongitude
            )
            mapView.zoomToBoundingBox(boundingBox, true, 100)
        }

        mapView.invalidate()
        Log.d(TAG, "Route rendered on OSMDroid")
    }

    private fun updateOsmTraveledPortion(controller: OfflineMapController, traveledPoints: List<RoutePoint>) {
        val mapView = controller.getMapView() ?: return

        // Remove old traveled polyline
        osmTraveledPolyline?.let { mapView.overlays.remove(it) }

        if (traveledPoints.size < 2) return

        val geoPoints = traveledPoints.map { GeoPoint(it.latitude, it.longitude) }

        osmTraveledPolyline = OsmPolyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = config.traveledColor
            outlinePaint.strokeWidth = config.routeWidth
        }

        // Add at index 0 so it's below the main route
        mapView.overlays.add(0, osmTraveledPolyline)
        mapView.invalidate()
    }

    private fun removeOsmOverlay(controller: OfflineMapController, overlay: Any) {
        controller.getMapView()?.overlays?.remove(overlay)
    }

    /**
     * Get the current route being rendered.
     */
    fun getCurrentRoute(): Route? = currentRoute
}
