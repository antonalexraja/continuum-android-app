/**
 * Google Routing Engine
 *
 * Implementation of [RoutingEngine] using Google Directions API.
 *
 * ## Setup
 *
 * 1. Enable Directions API in Google Cloud Console
 * 2. Add API key to local.properties or pass at construction
 *
 * ## API Reference
 *
 * https://developers.google.com/maps/documentation/directions/get-directions
 *
 * ## Response Parsing
 *
 * The engine parses Google's JSON response and converts it to the
 * common [Route] model with full maneuver details.
 */
package com.continuum.navigator.core.routing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Google Directions API routing engine.
 *
 * @param apiKey Google Directions API key
 */
class GoogleRoutingEngine(
    private val apiKey: String,
) : RoutingEngine {

    companion object {
        private const val TAG = "GoogleRoutingEngine"
        private const val BASE_URL = "https://maps.googleapis.com/maps/api/directions/json"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    override val provider: RoutingProvider = RoutingProvider.GOOGLE

    @Volatile
    private var currentConnection: HttpURLConnection? = null

    override suspend fun isAvailable(): Boolean {
        return apiKey.isNotBlank()
    }

    override suspend fun calculateRoute(request: RouteRequest): RouteResult {
        if (apiKey.isBlank()) {
            return RouteResult.Error(
                RouteErrorCode.INVALID_API_KEY,
                "Google Directions API key not configured"
            )
        }

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
        val params = mutableListOf<String>()

        // Origin and destination
        params.add("origin=${encodeLatLng(request.origin)}")
        params.add("destination=${encodeLatLng(request.destination)}")

        // Waypoints
        if (request.waypoints.isNotEmpty()) {
            val waypointsStr = request.waypoints.joinToString("|") { encodeLatLng(it) }
            params.add("waypoints=$waypointsStr")
        }

        // Travel mode
        val mode = when (request.mode) {
            TravelMode.DRIVING -> "driving"
            TravelMode.WALKING -> "walking"
            TravelMode.CYCLING -> "bicycling"
            TravelMode.TRANSIT -> "transit"
        }
        params.add("mode=$mode")

        // Alternatives
        if (request.alternatives) {
            params.add("alternatives=true")
        }

        // Avoidances
        if (request.avoid.isNotEmpty()) {
            val avoidStr = request.avoid.joinToString("|") { avoidance ->
                when (avoidance) {
                    RouteAvoidance.TOLLS -> "tolls"
                    RouteAvoidance.HIGHWAYS -> "highways"
                    RouteAvoidance.FERRIES -> "ferries"
                    RouteAvoidance.INDOOR -> "indoor"
                }
            }
            params.add("avoid=$avoidStr")
        }

        // Departure time for traffic
        request.departureTimeMillis?.let { millis ->
            params.add("departure_time=${millis / 1000}")
        }

        // API key
        params.add("key=$apiKey")

        return "$BASE_URL?${params.joinToString("&")}"
    }

    private fun encodeLatLng(point: RoutePoint): String {
        return "${point.latitude},${point.longitude}"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP Request
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun executeRequest(urlString: String): String = 
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            
            try {
                val url = URL(urlString)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                }
                
                currentConnection = connection
                
                continuation.invokeOnCancellation {
                    connection.disconnect()
                }

                val responseCode = connection.responseCode
                
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw Exception("HTTP $responseCode: $errorBody")
                }
                
                continuation.resume(response)
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume("")
                }
                throw e
            } finally {
                connection?.disconnect()
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
        val status = json.optString("status", "UNKNOWN")

        return when (status) {
            "OK" -> parseRoutes(json)
            "ZERO_RESULTS" -> RouteResult.Error(RouteErrorCode.NO_ROUTE, "No route found")
            "REQUEST_DENIED" -> RouteResult.Error(RouteErrorCode.INVALID_API_KEY, "API key invalid")
            "OVER_QUERY_LIMIT" -> RouteResult.Error(RouteErrorCode.RATE_LIMITED, "Rate limit exceeded")
            "INVALID_REQUEST" -> RouteResult.Error(RouteErrorCode.INVALID_REQUEST, json.optString("error_message", "Invalid request"))
            else -> RouteResult.Error(RouteErrorCode.PROVIDER_ERROR, "Status: $status")
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
            val legsArray = routeJson.optJSONArray("legs") ?: return null
            val overviewPolyline = routeJson.optJSONObject("overview_polyline")
                ?.optString("points", "") ?: ""
            val summary = routeJson.optString("summary", "")
            val boundsJson = routeJson.optJSONObject("bounds")

            // Decode polyline
            val polylinePoints = decodePolyline(overviewPolyline)

            // Parse legs
            val legs = mutableListOf<RouteLeg>()
            var totalDistance = 0.0
            var totalDuration = 0.0
            var polylineIndex = 0

            for (i in 0 until legsArray.length()) {
                val legJson = legsArray.getJSONObject(i)
                val (leg, newPolylineIndex) = parseLeg(legJson, polylinePoints, polylineIndex)
                legs.add(leg)
                totalDistance += leg.distanceMeters
                totalDuration += leg.durationSeconds
                polylineIndex = newPolylineIndex
            }

            // Parse bounds
            val bounds = boundsJson?.let { b ->
                val sw = b.optJSONObject("southwest")
                val ne = b.optJSONObject("northeast")
                if (sw != null && ne != null) {
                    RouteBounds(
                        southLatitude = sw.optDouble("lat"),
                        westLongitude = sw.optDouble("lng"),
                        northLatitude = ne.optDouble("lat"),
                        eastLongitude = ne.optDouble("lng")
                    )
                } else null
            }

            return Route(
                id = UUID.randomUUID().toString(),
                provider = RoutingProvider.GOOGLE,
                legs = legs,
                polyline = polylinePoints,
                totalDistanceMeters = totalDistance,
                totalDurationSeconds = totalDuration,
                summary = summary,
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
        val distanceValue = legJson.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
        val durationValue = legJson.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
        // JSONObject.optString returns an empty string when the key is missing; convert empty to null
        val startAddress = legJson.optString("start_address").ifEmpty { null }
        val endAddress = legJson.optString("end_address").ifEmpty { null }

        val stepsArray = legJson.optJSONArray("steps") ?: JSONArray()
        val maneuvers = mutableListOf<Maneuver>()
        var polylineIndex = startPolylineIndex

        for (i in 0 until stepsArray.length()) {
            val stepJson = stepsArray.getJSONObject(i)
            val (maneuver, newIndex) = parseStep(stepJson, polylineIndex)
            maneuvers.add(maneuver)
            polylineIndex = newIndex
        }

        val leg = RouteLeg(
            maneuvers = maneuvers,
            distanceMeters = distanceValue,
            durationSeconds = durationValue,
            startAddress = startAddress,
            endAddress = endAddress
        )

        return Pair(leg, polylineIndex)
    }

    private fun parseStep(stepJson: JSONObject, polylineIndex: Int): Pair<Maneuver, Int> {
        val startLocation = stepJson.optJSONObject("start_location")
        val lat = startLocation?.optDouble("lat", 0.0) ?: 0.0
        val lng = startLocation?.optDouble("lng", 0.0) ?: 0.0

        val distanceValue = stepJson.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
        val durationValue = stepJson.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0

        // Strip HTML from instructions
        val htmlInstruction = stepJson.optString("html_instructions", "")
        val instruction = htmlInstruction
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Parse maneuver type
        val maneuverStr = stepJson.optString("maneuver", "")
        val maneuverType = parseManeuverType(maneuverStr)

        // Step polyline
        val stepPolylineEncoded = stepJson.optJSONObject("polyline")?.optString("points", "") ?: ""
        val stepPolylinePoints = decodePolyline(stepPolylineEncoded)
        val newPolylineIndex = polylineIndex + stepPolylinePoints.size

        val maneuver = Maneuver(
            type = maneuverType,
            location = RoutePoint(lat, lng),
            instruction = instruction,
            distanceMeters = distanceValue,
            durationSeconds = durationValue,
            polylineStartIndex = polylineIndex
        )

        return Pair(maneuver, newPolylineIndex)
    }

    private fun parseManeuverType(maneuver: String): ManeuverType {
        return when (maneuver.lowercase()) {
            "" -> ManeuverType.STRAIGHT
            "turn-right" -> ManeuverType.TURN_RIGHT
            "turn-left" -> ManeuverType.TURN_LEFT
            "turn-slight-right" -> ManeuverType.SLIGHT_RIGHT
            "turn-slight-left" -> ManeuverType.SLIGHT_LEFT
            "turn-sharp-right" -> ManeuverType.SHARP_RIGHT
            "turn-sharp-left" -> ManeuverType.SHARP_LEFT
            "uturn-right" -> ManeuverType.UTURN_RIGHT
            "uturn-left" -> ManeuverType.UTURN_LEFT
            "merge" -> ManeuverType.MERGE
            "ramp-right", "ramp-left" -> ManeuverType.RAMP
            "fork-right", "fork-left" -> ManeuverType.FORK
            "roundabout-right", "roundabout-left" -> ManeuverType.ROUNDABOUT_ENTER
            "ferry" -> ManeuverType.FERRY
            else -> ManeuverType.UNKNOWN
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Polyline Decoding
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Decode Google's encoded polyline format.
     * Algorithm: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     */
    private fun decodePolyline(encoded: String): List<RoutePoint> {
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

            poly.add(RoutePoint(lat / 1E5, lng / 1E5))
        }

        return poly
    }
}
