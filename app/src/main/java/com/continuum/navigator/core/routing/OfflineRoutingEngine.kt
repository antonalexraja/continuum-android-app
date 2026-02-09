/**
 * Offline Routing Engine
 *
 * Provides offline routing capabilities using cached routes.
 * 
 * ## Current Implementation
 *
 * This implementation uses cached OSRM routes for offline navigation.
 * Routes are cached when calculated online and can be used offline.
 *
 * ## Future Enhancement
 *
 * Full offline routing with local graph processing would require:
 * - GraphHopper Mobile (complex native integration)
 * - Pre-built routing graphs (~50-200 MB per region)
 * - Significant memory and processing requirements
 *
 * ## Usage
 *
 * ```kotlin
 * val engine = OfflineRoutingEngine(context)
 * 
 * // Cache a route for offline use
 * engine.cacheRoute(route, "home-to-work")
 * 
 * // Retrieve cached route
 * val cachedRoute = engine.getCachedRoute("home-to-work")
 * ```
 */
package com.continuum.navigator.core.routing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline routing engine using route caching.
 */
class OfflineRoutingEngine(
    private val context: Context
) : RoutingEngine {

    companion object {
        private const val TAG = "OfflineRoutingEngine"
        private const val CACHE_FOLDER = "route_cache"
        private const val OSM_FOLDER = "osm_data"
        private const val MAX_CACHE_SIZE_MB = 50
    }

    override val provider: RoutingProvider = RoutingProvider.OFFLINE_OSM

    private val cacheDir: File by lazy { 
        File(context.filesDir, CACHE_FOLDER).also { it.mkdirs() }
    }
    private val osmDir: File by lazy {
        File(context.filesDir, OSM_FOLDER).also { it.mkdirs() }
    }

    /**
     * Status of offline routing data.
     */
    sealed class DataStatus {
        object NotDownloaded : DataStatus()
        object Downloading : DataStatus()
        data class Imported(val region: String, val sizeMb: Long) : DataStatus()
        data class Ready(val region: String, val profiles: List<String>) : DataStatus()
        data class Error(val message: String) : DataStatus()
    }
    
    private var dataStatus: DataStatus = DataStatus.NotDownloaded
    
    init {
        // Check for existing cached routes
        updateDataStatus()
    }
    
    private fun updateDataStatus() {
        val cachedRoutes = getCachedRouteCount()
        val osmFiles = getDownloadedOsmFiles()
        
        dataStatus = when {
            osmFiles.isNotEmpty() -> DataStatus.Ready(
                region = osmFiles.first().nameWithoutExtension,
                profiles = listOf("cached-routes")
            )
            cachedRoutes > 0 -> DataStatus.Ready(
                region = "Route Cache",
                profiles = listOf("$cachedRoutes routes")
            )
            else -> DataStatus.NotDownloaded
        }
    }
    
    /**
     * Get current data status.
     */
    fun getDataStatus(): DataStatus {
        updateDataStatus()
        return dataStatus
    }

    /**
     * Check if offline routing is available.
     * Returns true if there are cached routes.
     */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        getCachedRouteCount() > 0 || getDownloadedOsmFiles().isNotEmpty()
    }

    /**
     * Calculate route using cached routes.
     * Looks for a cached route that matches the origin/destination.
     */
    override suspend fun calculateRoute(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Looking for cached route: ${request.origin} -> ${request.destination}")
        
        // Look for a matching cached route
        val cachedRoute = findMatchingCachedRoute(request)
        
        if (cachedRoute != null) {
            Log.i(TAG, "Found cached route: ${cachedRoute.distanceText}")
            return@withContext RouteResult.Success(listOf(cachedRoute))
        }
        
        // No cached route found
        val hasOsmData = getDownloadedOsmFiles().isNotEmpty()
        
        val message = if (hasOsmData) {
            "OSM data downloaded but full offline routing not yet implemented.\n" +
            "Cache routes online first, then use them offline."
        } else {
            "No cached routes found for this origin/destination.\n" +
            "Calculate the route online first to cache it for offline use."
        }
        
        RouteResult.Error(
            code = RouteErrorCode.NOT_AVAILABLE,
            message = message
        )
    }
    
    /**
     * Cache a route for offline use.
     */
    fun cacheRoute(route: Route, name: String? = null): Boolean {
        return try {
            val routeName = name ?: generateRouteName(route)
            val fileName = "${routeName.hashCode()}.json"
            val cacheFile = File(cacheDir, fileName)
            
            val json = JSONObject().apply {
                put("name", routeName)
                put("id", route.id)
                put("provider", route.provider.name)
                put("summary", route.summary)
                put("totalDistanceMeters", route.totalDistanceMeters)
                put("totalDurationSeconds", route.totalDurationSeconds)
                put("calculatedAtMillis", route.calculatedAtMillis)
                
                // Store origin/destination for matching
                put("originLat", route.polyline.firstOrNull()?.latitude ?: 0.0)
                put("originLon", route.polyline.firstOrNull()?.longitude ?: 0.0)
                put("destLat", route.polyline.lastOrNull()?.latitude ?: 0.0)
                put("destLon", route.polyline.lastOrNull()?.longitude ?: 0.0)
                
                // Store polyline
                val polylineArray = JSONArray()
                route.polyline.forEach { point ->
                    polylineArray.put(JSONObject().apply {
                        put("lat", point.latitude)
                        put("lon", point.longitude)
                    })
                }
                put("polyline", polylineArray)
                
                // Store legs and maneuvers
                val legsArray = JSONArray()
                route.legs.forEach { leg ->
                    val legJson = JSONObject().apply {
                        put("distanceMeters", leg.distanceMeters)
                        put("durationSeconds", leg.durationSeconds)
                        put("startAddress", leg.startAddress)
                        put("endAddress", leg.endAddress)
                        
                        val maneuversArray = JSONArray()
                        leg.maneuvers.forEach { maneuver ->
                            maneuversArray.put(JSONObject().apply {
                                put("type", maneuver.type.name)
                                put("lat", maneuver.location.latitude)
                                put("lon", maneuver.location.longitude)
                                put("instruction", maneuver.instruction)
                                put("distanceMeters", maneuver.distanceMeters)
                                put("durationSeconds", maneuver.durationSeconds)
                                put("roadName", maneuver.roadName)
                                put("polylineStartIndex", maneuver.polylineStartIndex)
                            })
                        }
                        put("maneuvers", maneuversArray)
                    }
                    legsArray.put(legJson)
                }
                put("legs", legsArray)
            }
            
            cacheFile.writeText(json.toString(2))
            Log.i(TAG, "Route cached: $routeName")
            
            // Clean up old cache if needed
            cleanupCache()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache route", e)
            false
        }
    }
    
    /**
     * Find a cached route matching the request.
     */
    private fun findMatchingCachedRoute(request: RouteRequest): Route? {
        val cacheFiles = cacheDir.listFiles { file -> file.extension == "json" } ?: return null
        
        val originLat = request.origin.latitude
        val originLon = request.origin.longitude
        val destLat = request.destination.latitude
        val destLon = request.destination.longitude
        
        for (file in cacheFiles) {
            try {
                val json = JSONObject(file.readText())
                
                val cachedOriginLat = json.getDouble("originLat")
                val cachedOriginLon = json.getDouble("originLon")
                val cachedDestLat = json.getDouble("destLat")
                val cachedDestLon = json.getDouble("destLon")
                
                // Check if origin and destination match (within ~100m tolerance)
                val originDistance = calculateDistance(originLat, originLon, cachedOriginLat, cachedOriginLon)
                val destDistance = calculateDistance(destLat, destLon, cachedDestLat, cachedDestLon)
                
                if (originDistance < 100 && destDistance < 100) {
                    return parseRouteFromJson(json)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading cache file: ${file.name}", e)
            }
        }
        
        return null
    }
    
    /**
     * Parse a Route from cached JSON.
     */
    private fun parseRouteFromJson(json: JSONObject): Route {
        val polylineArray = json.getJSONArray("polyline")
        val polyline = mutableListOf<RoutePoint>()
        for (i in 0 until polylineArray.length()) {
            val point = polylineArray.getJSONObject(i)
            polyline.add(RoutePoint(point.getDouble("lat"), point.getDouble("lon")))
        }
        
        val legsArray = json.getJSONArray("legs")
        val legs = mutableListOf<RouteLeg>()
        for (i in 0 until legsArray.length()) {
            val legJson = legsArray.getJSONObject(i)
            
            val maneuversArray = legJson.getJSONArray("maneuvers")
            val maneuvers = mutableListOf<Maneuver>()
            for (j in 0 until maneuversArray.length()) {
                val mJson = maneuversArray.getJSONObject(j)
                maneuvers.add(Maneuver(
                    type = ManeuverType.valueOf(mJson.getString("type")),
                    location = RoutePoint(mJson.getDouble("lat"), mJson.getDouble("lon")),
                    instruction = mJson.getString("instruction"),
                    distanceMeters = mJson.getDouble("distanceMeters"),
                    durationSeconds = mJson.getDouble("durationSeconds"),
                    roadName = mJson.optString("roadName").takeIf { it.isNotEmpty() },
                    polylineStartIndex = mJson.optInt("polylineStartIndex", 0)
                ))
            }
            
            legs.add(RouteLeg(
                maneuvers = maneuvers,
                distanceMeters = legJson.getDouble("distanceMeters"),
                durationSeconds = legJson.getDouble("durationSeconds"),
                startAddress = legJson.optString("startAddress").takeIf { it.isNotEmpty() },
                endAddress = legJson.optString("endAddress").takeIf { it.isNotEmpty() }
            ))
        }
        
        return Route(
            id = json.optString("id", UUID.randomUUID().toString()),
            provider = RoutingProvider.OFFLINE_OSM,
            legs = legs,
            polyline = polyline,
            totalDistanceMeters = json.getDouble("totalDistanceMeters"),
            totalDurationSeconds = json.getDouble("totalDurationSeconds"),
            summary = json.optString("summary", "Cached route"),
            calculatedAtMillis = json.optLong("calculatedAtMillis", System.currentTimeMillis())
        )
    }
    
    /**
     * Calculate distance between two points in meters.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
    
    private fun generateRouteName(route: Route): String {
        val origin = route.polyline.firstOrNull()
        val dest = route.polyline.lastOrNull()
        return if (origin != null && dest != null) {
            "route_${origin.latitude.toInt()}_${origin.longitude.toInt()}_to_${dest.latitude.toInt()}_${dest.longitude.toInt()}"
        } else {
            "route_${System.currentTimeMillis()}"
        }
    }
    
    /**
     * Get count of cached routes.
     */
    fun getCachedRouteCount(): Int {
        return cacheDir.listFiles { file -> file.extension == "json" }?.size ?: 0
    }
    
    /**
     * Get list of cached route names.
     */
    fun getCachedRouteNames(): List<String> {
        return cacheDir.listFiles { file -> file.extension == "json" }?.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                json.optString("name")
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }
    
    /**
     * Get downloaded OSM files.
     */
    fun getDownloadedOsmFiles(): List<File> {
        return osmDir.listFiles { file -> 
            file.extension == "pbf" || file.extension == "osm"
        }?.toList() ?: emptyList()
    }
    
    /**
     * Get the OSM data directory for downloads.
     */
    fun getOsmDataDirectory(): File = osmDir
    
    /**
     * Get storage usage in MB.
     */
    fun getStorageUsageMb(): Long {
        val cacheSize = cacheDir.walkBottomUp().sumOf { it.length() }
        val osmSize = osmDir.walkBottomUp().sumOf { it.length() }
        return (cacheSize + osmSize) / (1024 * 1024)
    }
    
    /**
     * Clean up old cached routes if over size limit.
     */
    private fun cleanupCache() {
        val files = cacheDir.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.lastModified() } ?: return
        
        var totalSize = files.sumOf { it.length() }
        val maxSize = MAX_CACHE_SIZE_MB * 1024L * 1024L
        
        for (file in files) {
            if (totalSize <= maxSize) break
            totalSize -= file.length()
            file.delete()
            Log.d(TAG, "Deleted old cache file: ${file.name}")
        }
    }
    
    /**
     * Clear all cached routes.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Route cache cleared")
    }
    
    /**
     * Clear all offline data.
     */
    fun clearAllData() {
        clearCache()
        osmDir.listFiles()?.forEach { it.delete() }
        dataStatus = DataStatus.NotDownloaded
        Log.i(TAG, "All offline data cleared")
    }
    
    /**
     * Import OSM data file (for future use).
     * Currently just stores the file for reference.
     */
    suspend fun importOsmData(
        pbfFile: File,
        progressCallback: ((Float, String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            progressCallback?.invoke(0.1f, "Preparing...")
            
            // Just copy or move the file to our OSM directory
            val destFile = File(osmDir, pbfFile.name)
            if (pbfFile.absolutePath != destFile.absolutePath) {
                progressCallback?.invoke(0.5f, "Copying file...")
                pbfFile.copyTo(destFile, overwrite = true)
            }
            
            progressCallback?.invoke(0.9f, "Finalizing...")
            
            dataStatus = DataStatus.Ready(
                region = pbfFile.nameWithoutExtension,
                profiles = listOf("cached-routes")
            )
            
            progressCallback?.invoke(1.0f, "Complete!")
            Log.i(TAG, "OSM data stored: ${pbfFile.name}")
            
            // Note: Full offline routing would require building a routing graph here
            // This is complex and requires GraphHopper native integration
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import OSM data", e)
            dataStatus = DataStatus.Error(e.message ?: "Import failed")
            false
        }
    }
}
