/**
 * Map Activity
 *
 * Activity that displays the navigation map and integrates with the NavigationService.
 * Consumes NavigationOutput from the native engine to update map position.
 *
 * ## Features
 *
 * - Displays current position from NavigationOutput
 * - Supports Google Maps (online) and OSMDroid (offline)
 * - Runtime provider switching via settings
 * - Follow mode with auto-centering
 * - Heading-aware marker rotation
 *
 * ## Important
 *
 * This activity ONLY consumes NavigationOutput from the native engine.
 * It does NOT use Android Location APIs directly for rendering.
 */
package com.continuum.navigator.core.map

import android.R
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.NavigationOutput
import com.continuum.navigator.core.service.NavigationService
import com.continuum.navigator.core.ui.NavigatorColors
import com.continuum.navigator.core.ui.NavigatorTheme
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.cos

/**
 * Activity displaying the navigation map.
 *
 * Binds to NavigationService and observes NavigationOutput to update map position.
 */
class MapActivity : AppCompatActivity(), MapFragment.MapFragmentListener {

    companion object {
        private const val TAG = "MapActivity"
        private const val FRAGMENT_TAG = "map_fragment"
        private const val PREF_MAP_PROVIDER = "map_provider"
        private const val PREFS_NAME = "navigator_prefs"

        /**
         * Create an intent to launch MapActivity.
         */
        fun newIntent(context: Context): Intent {
            return Intent(context, MapActivity::class.java)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════

    private var navigationService: NavigationService.LocalBinder? = null
    private var serviceBound = false

    private var mapFragment: MapFragment? = null
    private var followButton: FloatingActionButton? = null
    
    // Trip logging UI
    private var recordButton: FloatingActionButton? = null
    private var tripStatusText: TextView? = null
    private var currentTripFile: File? = null
    
    // Navigation info panel
    private var infoPanel: LinearLayout? = null
    private var speedText: TextView? = null
    private var headingText: TextView? = null
    private var altitudeText: TextView? = null
    private var accuracyText: TextView? = null
    private var statusText: TextView? = null
    private var positionText: TextView? = null
    
    // Keep reference to mapContainer for tile downloads
    private var mapContainerId: Int = 0

    private val navigationOutputObserver = Observer<NavigationOutput> { output ->
        onNavigationOutputUpdate(output)
    }
    
    private val rawGnssObserver = Observer<GnssPositionInput> { gnss ->
        onRawGnssUpdate(gnss)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Service Connection
    // ═══════════════════════════════════════════════════════════════════════

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "NavigationService connected")
            navigationService = service as? NavigationService.LocalBinder
            observeNavigationOutput()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "NavigationService disconnected")
            navigationService = null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create layout programmatically to avoid resource file dependencies
        val rootLayout = FrameLayout(this).apply {
            id = R.id.content
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Map container
        val mapContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        rootLayout.addView(mapContainer)

        // Follow mode button
        followButton = FloatingActionButton(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (16 * resources.displayMetrics.density).toInt()
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { onFollowButtonClicked() }
            contentDescription = "Center on position"
        }
        rootLayout.addView(followButton)
        
        // Trip recording button (red circle - record)
        recordButton = FloatingActionButton(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = (16 * resources.displayMetrics.density).toInt()
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
            setImageResource(R.drawable.ic_menu_camera) // Will update icon
            setOnClickListener { onRecordButtonClicked() }
            contentDescription = "Record trip"
        }
        rootLayout.addView(recordButton)
        updateRecordButtonState(false)
        
        // Trip status text (top left)
        tripStatusText = TextView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = (16 * resources.displayMetrics.density).toInt()
                topMargin = (80 * resources.displayMetrics.density).toInt() // Below action bar
            }
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            textSize = 12f
            visibility = View.GONE
        }
        rootLayout.addView(tripStatusText)
        
        // Navigation info panel (top right) - Modern card style
        val density = resources.displayMetrics.density
        infoPanel = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = (12 * density).toInt()
                topMargin = (70 * density).toInt()
            }
            background = NavigatorTheme.createInfoPanelBackground(this@MapActivity)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            elevation = 8 * density
        }
        
        // Speed - Large display
        speedText = TextView(this).apply {
            text = "-- km/h"
            textSize = 20f
            setTextColor(NavigatorColors.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        infoPanel?.addView(speedText)
        
        // Heading
        headingText = createInfoText("🧭 --°")
        infoPanel?.addView(headingText)
        
        // Altitude
        altitudeText = createInfoText("⛰️ -- m")
        infoPanel?.addView(altitudeText)
        
        // Accuracy
        accuracyText = createInfoText("📍 ±-- m")
        infoPanel?.addView(accuracyText)
        
        // Status badge
        statusText = TextView(this).apply {
            text = "● INIT"
            textSize = 10f
            setTextColor(NavigatorColors.warning)
            setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
        }
        infoPanel?.addView(statusText)
        
        // Position
        positionText = createInfoText("--.------, --.------").apply {
            textSize = 9f
            setTextColor(NavigatorColors.textHint)
        }
        infoPanel?.addView(positionText)
        
        // Divider
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt()) }
            setBackgroundColor(Color.argb(50, 255, 255, 255))
        }.also { infoPanel?.addView(it) }
        
        // Legend for trajectory colors
        val legendText = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        TextView(this).apply {
            text = "━"
            textSize = 12f
            setTextColor(NavigatorColors.info)
        }.also { legendText.addView(it) }
        TextView(this).apply {
            text = " ESKF  "
            textSize = 9f
            setTextColor(NavigatorColors.textSecondary)
        }.also { legendText.addView(it) }
        TextView(this).apply {
            text = "━"
            textSize = 12f
            setTextColor(NavigatorColors.warning)
        }.also { legendText.addView(it) }
        TextView(this).apply {
            text = " GPS"
            textSize = 9f
            setTextColor(NavigatorColors.textSecondary)
        }.also { legendText.addView(it) }
        infoPanel?.addView(legendText)
        
        rootLayout.addView(infoPanel)
        
        // Apply dark status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = NavigatorColors.primaryDark
        }

        setContentView(rootLayout)
        mapContainerId = mapContainer.id

        // Set up action bar
        supportActionBar?.apply {
            title = "Navigation Map"
            setDisplayHomeAsUpEnabled(true)
        }

        // Initialize map fragment
        if (savedInstanceState == null) {
            val provider = getSavedMapProvider()
            mapFragment = MapFragment.newInstance(provider, followMode = true)
            supportFragmentManager.beginTransaction()
                .replace(mapContainer.id, mapFragment!!, FRAGMENT_TAG)
                .commit()
        } else {
            mapFragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? MapFragment
        }

        mapFragment?.setListener(this)
        updateFollowButtonState(true)

        Log.d(TAG, "MapActivity.onCreate complete - mapFragment=${mapFragment != null}, mapContainerId=${mapContainer.id}")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MapActivity.onStart")
        
        // Start navigation service (with sensors) if not already running
        NavigationService.Companion.start(this, null)
        
        // Bind to observe output
        bindNavigationService()
    }

    override fun onResume() {
        super.onResume()
        val frag = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
        Log.d(TAG, "MapActivity.onResume - fragment=$frag, isAdded=${frag?.isAdded}, isVisible=${frag?.isVisible}")
    }

    override fun onStop() {
        super.onStop()
        unbindNavigationService()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Navigation Service Binding
    // ═══════════════════════════════════════════════════════════════════════

    private fun bindNavigationService() {
        val intent = Intent(this, NavigationService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        serviceBound = true
        Log.d(TAG, "Binding to NavigationService")
    }

    private fun unbindNavigationService() {
        if (serviceBound) {
            navigationService?.navigationOutput?.removeObserver(navigationOutputObserver)
            navigationService?.rawGnssPosition?.removeObserver(rawGnssObserver)
            unbindService(serviceConnection)
            serviceBound = false
            navigationService = null
            Log.d(TAG, "Unbound from NavigationService")
        }
    }

    private fun observeNavigationOutput() {
        navigationService?.navigationOutput?.observe(this, navigationOutputObserver)
        navigationService?.rawGnssPosition?.observe(this, rawGnssObserver)
        Log.d(TAG, "Observing NavigationOutput and RawGNSS")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Navigation Output Handler
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called when new NavigationOutput is received from the native engine.
     *
     * Converts the native output to MapPosition and updates the map.
     * This is the ONLY source of position data for the map.
     */
    private fun onNavigationOutputUpdate(output: NavigationOutput) {
        val mapPosition = MapPosition.fromNavigationOutput(output)

        if (mapPosition.isValid) {
            mapFragment?.updatePosition(mapPosition, animate = true)
            // Update overlay with navigation data (trajectory, uncertainty, etc.)
            mapFragment?.updateNavigationOverlay(output)
            // Update info panel
            updateInfoPanel(output)
            Log.v(TAG, "Map updated: lat=${mapPosition.latitude}, lon=${mapPosition.longitude}")
        } else {
            Log.w(TAG, "Invalid NavigationOutput received")
        }
    }

    /**
     * Called when raw GNSS position is received (unfiltered).
     */
    private fun onRawGnssUpdate(gnss: GnssPositionInput) {
        mapFragment?.updateRawGnss(
            gnss.latitudeDeg,
            gnss.longitudeDeg,
            gnss.timestampNs,
            gnss.horizontalAccuracyM.toFloat()
        )
    }

    /**
     * Update the info panel with navigation data.
     */
    private fun updateInfoPanel(output: NavigationOutput) {
        // Speed in km/h - prominent display
        val speedKmh = output.speed * 3.6
        speedText?.text = "%.0f km/h".format(speedKmh)
        
        // Heading in degrees
        headingText?.text = "🧭 %.0f°".format(output.headingDeg)
        
        // Altitude
        altitudeText?.text = "⛰️ %.0f m".format(output.altitudeM)
        
        // Position accuracy
        accuracyText?.text = "📍 ±%.1f m".format(output.positionStdM)
        
        // Status with themed colors
        val (statusColor, statusLabel) = when {
            output.isRunning -> NavigatorColors.success to "ACTIVE"
            output.status == 0 -> NavigatorColors.warning to "INIT"
            else -> NavigatorColors.error to "ERROR"
        }
        statusText?.apply {
            text = "● $statusLabel"
            setTextColor(statusColor)
        }
        
        // Position
        positionText?.text = "%.6f, %.6f".format(output.latitudeDeg, output.longitudeDeg)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI Actions
    // ═══════════════════════════════════════════════════════════════════════

    private fun onFollowButtonClicked() {
        val currentFollowMode = mapFragment?.getFollowMode() ?: false
        val newFollowMode = !currentFollowMode

        mapFragment?.setFollowMode(newFollowMode)
        updateFollowButtonState(newFollowMode)

        val message = if (newFollowMode) "Following position" else "Free navigation"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        Log.d(TAG, "Follow mode toggled: $newFollowMode")
    }

    private fun updateFollowButtonState(followMode: Boolean) {
        followButton?.alpha = if (followMode) 1.0f else 0.5f
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Trip Logging
    // ═══════════════════════════════════════════════════════════════════════

    private fun onRecordButtonClicked() {
        val service = navigationService
        if (service == null) {
            Toast.makeText(this, "Navigation service not connected", Toast.LENGTH_SHORT).show()
            return
        }

        if (service.isTripLogging()) {
            // Stop recording
            service.stopTripLogging()
            currentTripFile = null
            updateRecordButtonState(false)
            tripStatusText?.visibility = View.GONE
            Toast.makeText(this, "Trip recording stopped", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Trip recording stopped")
        } else {
            // Start recording
            currentTripFile = service.startTripLogging()
            if (currentTripFile != null) {
                updateRecordButtonState(true)
                tripStatusText?.apply {
                    text = "● REC: ${currentTripFile?.name}"
                    setTextColor(NavigatorColors.error)
                    visibility = View.VISIBLE
                }
                Toast.makeText(this, "Trip recording started", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Trip recording started: ${currentTripFile?.absolutePath}")
            } else {
                Toast.makeText(this, "Failed to start trip recording", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to start trip recording")
            }
        }
    }

    private fun updateRecordButtonState(isRecording: Boolean) {
        recordButton?.apply {
            if (isRecording) {
                // Recording - show stop icon, red tint
                setImageResource(R.drawable.ic_media_pause)
                backgroundTintList = ColorStateList.valueOf(Color.RED)
                contentDescription = "Stop recording"
            } else {
                // Not recording - show record icon
                setImageResource(R.drawable.ic_menu_camera)
                backgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
                contentDescription = "Record trip"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Menu
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Google Maps")
        menu.add(0, 2, 0, "Offline Maps (OSM)")
        menu.add(0, 3, 0, "Recorded Trips")
        menu.add(0, 4, 0, "📥 Download Tiles")
        menu.add(0, 5, 0, "Clear Trajectories")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.home -> {
                finish()
                true
            }
            1 -> {
                switchToProvider(MapProvider.GOOGLE_MAPS)
                true
            }
            2 -> {
                switchToProvider(MapProvider.OFFLINE_OSM)
                true
            }
            3 -> {
                showRecordedTrips()
                true
            }
            4 -> {
                showMapDownloadDialog()
                true
            }
            5 -> {
                mapFragment?.clearOverlayTrajectory()
                Toast.makeText(this, "Trajectories cleared", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRecordedTrips() {
        val trips = navigationService?.getRecordedTrips() ?: emptyList()
        
        if (trips.isEmpty()) {
            Toast.makeText(this, "No recorded trips yet", Toast.LENGTH_SHORT).show()
            return
        }
        
        val tripNames = trips.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Recorded Trips (${trips.size})")
            .setItems(tripNames) { _, which ->
                val trip = trips[which]
                val size = trip.length() / 1024
                Toast.makeText(this, "${trip.name}\nSize: ${size}KB", Toast.LENGTH_LONG).show()
                Log.i(TAG, "Selected trip: ${trip.absolutePath}, size=$size KB")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun switchToProvider(provider: MapProvider) {
        mapFragment?.switchProvider(provider)
        saveMapProvider(provider)

        val providerName = when (provider) {
            MapProvider.GOOGLE_MAPS -> "Google Maps"
            MapProvider.OFFLINE_OSM -> "Offline Maps (OSM)"
        }
        Toast.makeText(this, "Switched to $providerName", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tile Caching
    // ═══════════════════════════════════════════════════════════════════════

    private fun showMapDownloadDialog() {
        val regions = arrayOf(
            "Current View (Visible Area)",
            "10km Radius from Current Position",
            "Clear Tile Cache"
        )
        
        AlertDialog.Builder(this)
            .setTitle("📥 Download Offline Map Tiles")
            .setItems(regions) { _, which ->
                when (which) {
                    0 -> downloadCurrentViewTiles()
                    1 -> downloadRadiusTiles(10.0)
                    2 -> clearTileCache()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun downloadCurrentViewTiles() {
        val controller = mapFragment?.getController()
        if (controller == null) {
            Toast.makeText(this, "Map not ready", Toast.LENGTH_SHORT).show()
            return
        }
        
        val center = controller.getCameraCenter()
        val zoom = controller.getZoom()
        
        if (center == null) {
            Toast.makeText(this, "No position available", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Calculate visible area (rough approximation)
        val latSpan = 360.0 / Math.pow(2.0, zoom.toDouble()) * 0.5
        val lonSpan = 360.0 / Math.pow(2.0, zoom.toDouble()) * 0.8
        
        val zoomMin = zoom.toInt().coerceAtLeast(10)
        val zoomMax = (zoomMin + 3).coerceAtMost(18)
        
        downloadTilesForArea(
            "Current View",
            center.first - latSpan, center.second - lonSpan,
            center.first + latSpan, center.second + lonSpan,
            zoomMin, zoomMax
        )
    }
    
    private fun downloadRadiusTiles(radiusKm: Double) {
        val controller = mapFragment?.getController()
        val center = controller?.getCameraCenter()
        
        if (center == null) {
            Toast.makeText(this, "No position available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val latDelta = radiusKm / 111.0  // ~111 km per degree
        val lonDelta = radiusKm / (111.0 * cos(Math.toRadians(center.first)))
        
        downloadTilesForArea(
            "${radiusKm}km Radius",
            center.first - latDelta, center.second - lonDelta,
            center.first + latDelta, center.second + lonDelta,
            10, 16
        )
    }
    
    private fun createBulkDownloadTileSource(): XYTileSource {
        return XYTileSource(
            "OSM_Cacheable",
            1, 19, 256, ".png",
            arrayOf("https://tile.openstreetmap.org/"),
            "© OpenStreetMap contributors",
            TileSourcePolicy(
                4,
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
                    TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
            )
        )
    }
    
    private fun downloadTilesForArea(
        name: String,
        latSouth: Double, lonWest: Double,
        latNorth: Double, lonEast: Double,
        zoomMin: Int, zoomMax: Int
    ) {
        // Need OSM MapView for tile download
        Toast.makeText(this, "📥 Preparing tile download for $name...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                // Create temp OSM MapView for download
                val osmConfig = Configuration.getInstance()
                osmConfig.userAgentValue = packageName
                
                val tempMapView = MapView(this@MapActivity)
                tempMapView.setTileSource(createBulkDownloadTileSource())
                
                val cacheManager = CacheManager(tempMapView)
                val boundingBox = BoundingBox(latNorth, lonEast, latSouth, lonWest)
                
                val tileCount = cacheManager.possibleTilesInArea(boundingBox, zoomMin, zoomMax)
                Log.i(TAG, "Downloading ~$tileCount tiles for $name")
                
                runOnUiThread {
                    if (tileCount > 2000) {
                        AlertDialog.Builder(this@MapActivity)
                            .setTitle("Large Download")
                            .setMessage("This will download ~$tileCount tiles (~${tileCount / 10}MB).\nContinue?")
                            .setPositiveButton("Download") { _, _ ->
                                performTileDownload(cacheManager, boundingBox, zoomMin, zoomMax, name)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        performTileDownload(cacheManager, boundingBox, zoomMin, zoomMax, name)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tile download error", e)
                runOnUiThread {
                    Toast.makeText(this@MapActivity, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun performTileDownload(
        cacheManager: CacheManager,
        boundingBox: BoundingBox,
        zoomMin: Int, zoomMax: Int,
        name: String
    ) {
        Toast.makeText(this, "📥 Downloading tiles for $name...", Toast.LENGTH_SHORT).show()
        
        cacheManager.downloadAreaAsync(
            this,
            boundingBox,
            zoomMin,
            zoomMax,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    runOnUiThread {
                        Toast.makeText(this@MapActivity, "✅ $name tiles downloaded!", Toast.LENGTH_LONG).show()
                    }
                }
                
                override fun onTaskFailed(errors: Int) {
                    runOnUiThread {
                        Toast.makeText(this@MapActivity, "⚠️ Completed with $errors errors", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                    Log.d(TAG, "Download progress: $progress% (zoom $currentZoomLevel)")
                }
                
                override fun downloadStarted() {
                    Log.i(TAG, "Tile download started for $name")
                }
                
                override fun setPossibleTilesInArea(total: Int) {
                    Log.i(TAG, "Total tiles to download: $total")
                }
            }
        )
    }
    
    private fun clearTileCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear Cache")
            .setMessage("Delete all downloaded map tiles?")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    val cacheDir = Configuration.getInstance().osmdroidTileCache
                    cacheDir?.deleteRecursively()
                    Toast.makeText(this, "🗑️ Tile cache cleared", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Tile cache cleared")
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Preferences
    // ═══════════════════════════════════════════════════════════════════════

    private fun getSavedMapProvider(): MapProvider {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val providerName = prefs.getString(PREF_MAP_PROVIDER, MapProvider.OFFLINE_OSM.name)
        return try {
            MapProvider.valueOf(providerName!!)
        } catch (e: Exception) {
            MapProvider.OFFLINE_OSM
        }
    }

    private fun saveMapProvider(provider: MapProvider) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_MAP_PROVIDER, provider.name)
            .apply()
    }

    private fun createInfoText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MapFragmentListener Implementation
    // ═══════════════════════════════════════════════════════════════════════

    override fun onMapReady(controller: MapController) {
        Log.i(TAG, "Map ready: ${controller.provider}")
    }

    override fun onProviderChanged(oldProvider: MapProvider, newProvider: MapProvider) {
        Log.i(TAG, "Provider changed: $oldProvider -> $newProvider")
    }

    override fun onFollowModeChanged(enabled: Boolean) {
        updateFollowButtonState(enabled)
        Log.d(TAG, "Follow mode changed: $enabled")
    }

    override fun onMapError(error: String) {
        Log.e(TAG, "Map error: $error")
        Toast.makeText(this, "Map error: $error", Toast.LENGTH_LONG).show()
    }
}
