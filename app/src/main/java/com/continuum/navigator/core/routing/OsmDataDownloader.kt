/**
 * OSM Data Download Manager
 *
 * Handles downloading OSM PBF files for offline routing.
 * Provides pre-configured regions and custom download options.
 *
 * ## Data Sources
 *
 * Uses Geofabrik's free OSM data extracts:
 * - https://download.geofabrik.de/
 *
 * ## Usage
 *
 * ```kotlin
 * val downloader = OsmDataDownloader(context)
 * 
 * // Get available regions
 * val regions = downloader.getAvailableRegions()
 * 
 * // Download a region
 * downloader.downloadRegion(region) { progress, status ->
 *     updateUI(progress, status)
 * }
 * ```
 */
package com.continuum.navigator.core.routing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads OSM data for offline routing.
 */
class OsmDataDownloader(
    private val context: Context
) {
    companion object {
        private const val TAG = "OsmDataDownloader"
        private const val GEOFABRIK_BASE = "https://download.geofabrik.de"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()
    
    private val downloadDir: File by lazy {
        File(context.filesDir, "osm_data").also { it.mkdirs() }
    }
    
    /**
     * Represents a downloadable OSM region.
     */
    data class OsmRegion(
        val id: String,
        val name: String,
        val description: String,
        val url: String,
        val approximateSizeMb: Int,
        val country: String = "",
        val continent: String = ""
    )
    
    /**
     * Download status.
     */
    sealed class DownloadStatus {
        object Idle : DownloadStatus()
        data class Downloading(val progress: Float, val downloadedMb: Float, val totalMb: Float) : DownloadStatus()
        data class Completed(val file: File) : DownloadStatus()
        data class Failed(val error: String) : DownloadStatus()
    }
    
    /**
     * Get list of available regions for download.
     * Focused on India and nearby regions for the current use case.
     */
    fun getAvailableRegions(): List<OsmRegion> {
        return listOf(
            // India - States/Regions (most relevant)
            OsmRegion(
                id = "karnataka",
                name = "Karnataka",
                description = "Karnataka state including Bangalore",
                url = "$GEOFABRIK_BASE/asia/india/karnataka-latest.osm.pbf",
                approximateSizeMb = 85,
                country = "India",
                continent = "Asia"
            ),
            OsmRegion(
                id = "tamil-nadu",
                name = "Tamil Nadu",
                description = "Tamil Nadu state including Chennai, Hosur",
                url = "$GEOFABRIK_BASE/asia/india/tamil-nadu-latest.osm.pbf",
                approximateSizeMb = 95,
                country = "India",
                continent = "Asia"
            ),
            OsmRegion(
                id = "kerala",
                name = "Kerala",
                description = "Kerala state",
                url = "$GEOFABRIK_BASE/asia/india/kerala-latest.osm.pbf",
                approximateSizeMb = 45,
                country = "India",
                continent = "Asia"
            ),
            OsmRegion(
                id = "andhra-pradesh",
                name = "Andhra Pradesh",
                description = "Andhra Pradesh state",
                url = "$GEOFABRIK_BASE/asia/india/andhra-pradesh-latest.osm.pbf",
                approximateSizeMb = 50,
                country = "India",
                continent = "Asia"
            ),
            OsmRegion(
                id = "telangana",
                name = "Telangana",
                description = "Telangana state including Hyderabad",
                url = "$GEOFABRIK_BASE/asia/india/telangana-latest.osm.pbf",
                approximateSizeMb = 40,
                country = "India",
                continent = "Asia"
            ),
            OsmRegion(
                id = "maharashtra",
                name = "Maharashtra",
                description = "Maharashtra state including Mumbai, Pune",
                url = "$GEOFABRIK_BASE/asia/india/maharashtra-latest.osm.pbf",
                approximateSizeMb = 120,
                country = "India",
                continent = "Asia"
            ),
            
            // Full India (large)
            OsmRegion(
                id = "india",
                name = "India (Full)",
                description = "Complete India - all states",
                url = "$GEOFABRIK_BASE/asia/india-latest.osm.pbf",
                approximateSizeMb = 950,
                country = "India",
                continent = "Asia"
            ),
            
            // Other countries
            OsmRegion(
                id = "sri-lanka",
                name = "Sri Lanka",
                description = "Sri Lanka",
                url = "$GEOFABRIK_BASE/asia/sri-lanka-latest.osm.pbf",
                approximateSizeMb = 25,
                country = "Sri Lanka",
                continent = "Asia"
            ),
            OsmRegion(
                id = "nepal",
                name = "Nepal",
                description = "Nepal",
                url = "$GEOFABRIK_BASE/asia/nepal-latest.osm.pbf",
                approximateSizeMb = 35,
                country = "Nepal",
                continent = "Asia"
            ),
            
            // Test region (small)
            OsmRegion(
                id = "monaco",
                name = "Monaco (Test)",
                description = "Small region for testing (~1MB)",
                url = "$GEOFABRIK_BASE/europe/monaco-latest.osm.pbf",
                approximateSizeMb = 1,
                country = "Monaco",
                continent = "Europe"
            )
        )
    }
    
    /**
     * Get downloaded regions.
     */
    fun getDownloadedRegions(): List<File> {
        return downloadDir.listFiles { file ->
            file.extension == "pbf"
        }?.toList() ?: emptyList()
    }
    
    /**
     * Check if a region is downloaded.
     */
    fun isRegionDownloaded(region: OsmRegion): Boolean {
        val file = File(downloadDir, "${region.id}.osm.pbf")
        return file.exists() && file.length() > 0
    }
    
    /**
     * Get the file path for a region.
     */
    fun getRegionFile(region: OsmRegion): File {
        return File(downloadDir, "${region.id}.osm.pbf")
    }
    
    /**
     * Download a region's OSM data.
     *
     * @param region The region to download
     * @param progressCallback Progress callback (0.0 to 1.0, status message)
     * @return Downloaded file or null if failed
     */
    suspend fun downloadRegion(
        region: OsmRegion,
        progressCallback: ((Float, String) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(downloadDir, "${region.id}.osm.pbf")
        val tempFile = File(downloadDir, "${region.id}.osm.pbf.tmp")
        
        try {
            Log.i(TAG, "Downloading region: ${region.name} from ${region.url}")
            progressCallback?.invoke(0f, "Connecting to server...")
            
            val request = Request.Builder()
                .url(region.url)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                progressCallback?.invoke(0f, "Download failed: HTTP ${response.code}")
                return@withContext null
            }
            
            val body = response.body ?: run {
                Log.e(TAG, "Empty response body")
                progressCallback?.invoke(0f, "Download failed: Empty response")
                return@withContext null
            }
            
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            
            progressCallback?.invoke(0f, "Downloading ${region.name}...")
            
            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val progress = if (totalBytes > 0) {
                            downloadedBytes.toFloat() / totalBytes
                        } else {
                            0f
                        }
                        
                        val downloadedMb = downloadedBytes / (1024f * 1024f)
                        val totalMb = totalBytes / (1024f * 1024f)
                        
                        progressCallback?.invoke(
                            progress,
                            "Downloading: ${String.format("%.1f", downloadedMb)} / ${String.format("%.1f", totalMb)} MB"
                        )
                    }
                }
            }
            
            // Rename temp file to final
            tempFile.renameTo(outputFile)
            
            Log.i(TAG, "Download complete: ${outputFile.name} (${outputFile.length() / (1024*1024)} MB)")
            progressCallback?.invoke(1f, "Download complete!")
            
            outputFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            progressCallback?.invoke(0f, "Error: ${e.message}")
            tempFile.delete()
            null
        }
    }
    
    /**
     * Delete a downloaded region.
     */
    fun deleteRegion(region: OsmRegion): Boolean {
        val file = File(downloadDir, "${region.id}.osm.pbf")
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
    
    /**
     * Get total storage used by downloaded regions.
     */
    fun getStorageUsedMb(): Long {
        return downloadDir.walkBottomUp().sumOf { it.length() } / (1024 * 1024)
    }
    
    /**
     * Clear all downloaded data.
     */
    fun clearAllDownloads() {
        downloadDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "All downloads cleared")
    }
}
