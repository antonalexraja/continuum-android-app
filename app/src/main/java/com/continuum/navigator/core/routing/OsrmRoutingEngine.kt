/**
 * OSRM Routing Engine
 *
 * Implementation of [RoutingEngine] using OSRM (Open Source Routing Machine).
 * Can use public OSRM server or self-hosted instance.
 *
 * ## Public Server
 *
 * Default: https://router.project-osrm.org
 * - Free to use
 * - No API key required
 * - Subject to rate limits
 * - No traffic data
 *
 * ## Self-Hosted
 *
 * Deploy your own OSRM instance for production use.
 *
 * ## API Reference
 *
 * http://project-osrm.org/docs/v5.24.0/api/
 *
 * ## Differences from Google
 *
 * - No traffic awareness
 * - Simpler maneuver types
 * - Different polyline encoding (polyline6 vs polyline5)
 */
package com.continuum.navigator.core.routing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * OSRM routing engine.
 *
 * @param baseUrl OSRM server base URL (default: public server)
 */
class OsrmRoutingEngine(
    private val baseUrl: String = "https://router.project-osrm.org",
) : RoutingEngine {

    companion object {
        private const val TAG = "OsrmRoutingEngine"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    override val provider: RoutingProvider = RoutingProvider.OSRM

    @Volatile
    private var currentConnection: HttpURLConnection? = null

    override suspend fun isAvailable(): Boolean {
        // Check if server is reachable with a quick HEAD request
        return try {
            withContext(Dispatchers.IO) {
                val url = URL(baseUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.responseCode in 200..299
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSRM server not reachable: ${e.message}")
            false
        }
    }

    override suspend fun calculateRoute(request: RouteRequest): RouteResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = buildRequestUrl(request)
                Log.d(TAG, "Requesting route: $url")

                val response = executeRequest(url)
                parseResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "Route calculation failed", e)
                RouteResult.Error(
                    RouteErrorCode.NETWORK_ERROR,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    override fun cancel() {
        currentConnection?.disconnect()
        currentConnection = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Request Building
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildRequestUrl(request: RouteRequest): String {
        // OSRM route service: /route/v1/{profile}/{coordinates}
        
        val profile = when (request.mode) {
            TravelMode.DRIVING -> "driving"
            TravelMode.WALKING -> "foot"
            TravelMode.CYCLING -> "bike"
            TravelMode.TRANSIT -> "driving" // OSRM doesn't support transit
        }

        // Build coordinates list: origin, [waypoints], destination
        val coords = buildList {
            add(request.origin)
            addAll(request.waypoints)
            add(request.destination)
        }.joinToString(";") { "${it.longitude},${it.latitude}" }

        val params = mutableListOf<String>()
        
        // Request full geometry (polyline6 encoding)
        params.add("overview=full")
        params.add("geometries=polyline6")
        
        // Request detailed steps
        params.add("steps=true")
        
        // Request alternatives if needed
        if (request.alternatives) {
            params.add("alternatives=true")
        }

        return "$baseUrl/route/v1/$profile/$coords?${params.joinToString("&")}"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP Request
    // ═══════════════════════════════════════════════════════════════════════

    private fun executeRequest(urlString: String): String {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        currentConnection = connection

        try {
            val responseCode = connection.responseCode

            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP $responseCode: $errorBody")
            }

            return response
        } finally {
            connection.disconnect()
            currentConnection = null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Response Parsing
    // ═══════════════════════════════════════════════════════════════════════

    private fun parseResponse(responseBody: String): RouteResult {
        if (responseBody.isBlank()) {
            return RouteResult.Error(RouteErrorCode.NETWORK_ERROR, "Empty response")
        }

        val json = JSONObject(responseBody)
        val code = json.optString("code", "Unknown")

        return when (code) {
            "Ok" -> parseRoutes(json)
            "NoRoute" -> RouteResult.Error(RouteErrorCode.NO_ROUTE, "No route found")
            "InvalidOptions" -> RouteResult.Error(RouteErrorCode.INVALID_REQUEST, json.optString("message", "Invalid options"))
            "InvalidInput" -> RouteResult.Error(RouteErrorCode.INVALID_REQUEST, json.optString("message", "Invalid input"))
            else -> RouteResult.Error(RouteErrorCode.PROVIDER_ERROR, "Code: $code")
        }
    }

    private fun parseRoutes(json: JSONObject): RouteResult {
        val routesArray = json.optJSONArray("routes") ?: return RouteResult.Error(
            RouteErrorCode.NO_ROUTE, "No routes in response"
        )

        val routes = mutableListOf<Route>()

        for (i in 0 until routesArray.length()) {
            val routeJson = routesArray.getJSONObject(i)
            parseRoute(routeJson)?.let { routes.add(it) }
        }

        return if (routes.isNotEmpty()) {
            RouteResult.Success(routes)
        } else {
            RouteResult.Error(RouteErrorCode.NO_ROUTE, "Failed to parse routes")
        }
    }

    private fun parseRoute(routeJson: JSONObject): Route? {
        try {
            val geometry = routeJson.optString("geometry", "")
            val distance = routeJson.optDouble("distance", 0.0)
            val duration = routeJson.optDouble("duration", 0.0)
            val legsArray = routeJson.optJSONArray("legs") ?: JSONArray()

            // Decode polyline (OSRM uses polyline6 by default)
            val polylinePoints = decodePolyline6(geometry)

            // Parse legs
            val legs = mutableListOf<RouteLeg>()
            var polylineIndex = 0

            for (i in 0 until legsArray.length()) {
                val legJson = legsArray.getJSONObject(i)
                val (leg, newIndex) = parseLeg(legJson, polylinePoints, polylineIndex)
                legs.add(leg)
                polylineIndex = newIndex
            }

            // Calculate bounds from polyline
            val bounds = calculateBounds(polylinePoints)

            return Route(
                id = UUID.randomUUID().toString(),
                provider = RoutingProvider.OSRM,
                legs = legs,
                polyline = polylinePoints,
                totalDistanceMeters = distance,
                totalDurationSeconds = duration,
                summary = "OSRM Route",
                bounds = bounds
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse route", e)
            return null
        }
    }

    private fun parseLeg(
        legJson: JSONObject,
        fullPolyline: List<RoutePoint>,
        startPolylineIndex: Int
    ): Pair<RouteLeg, Int> {
        val distance = legJson.optDouble("distance", 0.0)
        val duration = legJson.optDouble("duration", 0.0)
        val summary = legJson.optString("summary", "")

        val stepsArray = legJson.optJSONArray("steps") ?: JSONArray()
        val maneuvers = mutableListOf<Maneuver>()
        var polylineIndex = startPolylineIndex

        for (i in 0 until stepsArray.length()) {
            val stepJson = stepsArray.getJSONObject(i)
            val (maneuver, newIndex) = parseStep(stepJson, polylineIndex)
            maneuvers.add(maneuver)
            polylineIndex = newIndex
        }

        // Add arrival maneuver if it's the last leg
        if (maneuvers.isNotEmpty()) {
            val lastManeuver = maneuvers.last()
            if (lastManeuver.type != ManeuverType.ARRIVE) {
                val arriveLocation = if (fullPolyline.isNotEmpty()) {
                    fullPolyline.last()
                } else {
                    lastManeuver.location
                }
                
                maneuvers.add(
                    Maneuver(
                        type = ManeuverType.ARRIVE,
                        location = arriveLocation,
                        instruction = "Arrive at destination",
                        distanceMeters = 0.0,
                        durationSeconds = 0.0,
                        polylineStartIndex = polylineIndex
                    )
                )
            }
        }

        val leg = RouteLeg(
            maneuvers = maneuvers,
            distanceMeters = distance,
            durationSeconds = duration,
            startAddress = summary.takeIf { it.isNotBlank() },
            endAddress = null
        )

        return Pair(leg, polylineIndex)
    }

    private fun parseStep(stepJson: JSONObject, polylineIndex: Int): Pair<Maneuver, Int> {
        val maneuverJson = stepJson.optJSONObject("maneuver")
        val distance = stepJson.optDouble("distance", 0.0)
        val duration = stepJson.optDouble("duration", 0.0)
        val name = stepJson.optString("name", "")
        val mode = stepJson.optString("mode", "")

        // Parse location
        val location = maneuverJson?.optJSONArray("location")?.let { loc ->
            RoutePoint(
                latitude = loc.optDouble(1, 0.0),
                longitude = loc.optDouble(0, 0.0)
            )
        } ?: RoutePoint.EMPTY

        // Parse maneuver type
        val maneuverType = maneuverJson?.optString("type", "") ?: ""
        val modifier = maneuverJson?.optString("modifier", "") ?: ""
        val type = parseManeuverType(maneuverType, modifier)

        // Build instruction
        val instruction = buildInstruction(type, name, modifier)

        // Parse bearings
        val bearingBefore = maneuverJson?.optDouble("bearing_before")
        val bearingAfter = maneuverJson?.optDouble("bearing_after")

        // Decode step geometry
        val stepGeometry = stepJson.optString("geometry", "")
        val stepPoints = if (stepGeometry.isNotBlank()) {
            decodePolyline6(stepGeometry)
        } else {
            emptyList()
        }
        val newPolylineIndex = polylineIndex + stepPoints.size

        val maneuver = Maneuver(
            type = type,
            location = location,
            instruction = instruction,
            distanceMeters = distance,
            durationSeconds = duration,
            roadName = name.takeIf { it.isNotBlank() },
            bearingBefore = bearingBefore,
            bearingAfter = bearingAfter,
            polylineStartIndex = polylineIndex
        )

        return Pair(maneuver, newPolylineIndex)
    }

    private fun parseManeuverType(type: String, modifier: String): ManeuverType {
        return when (type) {
            "depart" -> ManeuverType.DEPART
            "arrive" -> ManeuverType.ARRIVE
            "turn" -> when (modifier) {
                "straight" -> ManeuverType.STRAIGHT
                "slight right" -> ManeuverType.SLIGHT_RIGHT
                "right" -> ManeuverType.TURN_RIGHT
                "sharp right" -> ManeuverType.SHARP_RIGHT
                "slight left" -> ManeuverType.SLIGHT_LEFT
                "left" -> ManeuverType.TURN_LEFT
                "sharp left" -> ManeuverType.SHARP_LEFT
                "uturn" -> ManeuverType.UTURN_LEFT
                else -> ManeuverType.UNKNOWN
            }
            "merge" -> ManeuverType.MERGE
            "ramp", "on ramp", "off ramp" -> ManeuverType.RAMP
            "fork" -> ManeuverType.FORK
            "roundabout", "rotary" -> ManeuverType.ROUNDABOUT_ENTER
            "exit roundabout", "exit rotary" -> ManeuverType.ROUNDABOUT_EXIT
            "continue" -> ManeuverType.STRAIGHT
            else -> ManeuverType.UNKNOWN
        }
    }

    private fun buildInstruction(type: ManeuverType, roadName: String, modifier: String): String {
        val action = when (type) {
            ManeuverType.DEPART -> "Start"
            ManeuverType.ARRIVE -> "Arrive"
            ManeuverType.STRAIGHT -> "Continue"
            ManeuverType.SLIGHT_RIGHT -> "Slight right"
            ManeuverType.TURN_RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            ManeuverType.UTURN_RIGHT, ManeuverType.UTURN_LEFT -> "Make a U-turn"
            ManeuverType.SLIGHT_LEFT -> "Slight left"
            ManeuverType.TURN_LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.MERGE -> "Merge"
            ManeuverType.RAMP -> "Take the ramp"
            ManeuverType.ROUNDABOUT_ENTER -> "Enter roundabout"
            ManeuverType.ROUNDABOUT_EXIT -> "Exit roundabout"
            ManeuverType.FORK -> "At the fork"
            ManeuverType.FERRY -> "Take the ferry"
            ManeuverType.UNKNOWN -> "Continue"
        }

        return if (roadName.isNotBlank() && roadName != "unknown") {
            "$action onto $roadName"
        } else {
            action
        }
    }

    private fun calculateBounds(points: List<RoutePoint>): RouteBounds? {
        if (points.isEmpty()) return null

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        points.forEach { point ->
            minLat = minOf(minLat, point.latitude)
            maxLat = maxOf(maxLat, point.latitude)
            minLon = minOf(minLon, point.longitude)
            maxLon = maxOf(maxLon, point.longitude)
        }

        return RouteBounds(
            southLatitude = minLat,
            westLongitude = minLon,
            northLatitude = maxLat,
            eastLongitude = maxLon
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Polyline Decoding (Polyline6)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Decode OSRM polyline6 format (precision 6).
     * Similar to Google's polyline5 but with 1E6 instead of 1E5.
     */
    private fun decodePolyline6(encoded: String): List<RoutePoint> {
        val poly = mutableListOf<RoutePoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            // Decode latitude
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            // Decode longitude
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(RoutePoint(lat / 1E6, lng / 1E6))
        }

        return poly
    }
}
