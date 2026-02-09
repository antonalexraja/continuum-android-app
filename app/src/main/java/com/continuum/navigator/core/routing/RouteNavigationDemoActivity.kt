/**
 * Route Navigation Demo Activity
 *
 * Demonstrates the route guidance system integration.
 * Shows route calculation, turn-by-turn navigation, and map rendering.
 *
 * ## Features Demonstrated
 *
 * - Route calculation with mode selection
 * - Turn-by-turn instruction display
 * - Route polyline rendering on map
 * - Position tracking along route
 * - Rerouting when off-route
 */
package com.continuum.navigator.core.routing

import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.continuum.navigator.core.ui.NavigatorColors
import com.continuum.navigator.core.ui.NavigatorTheme
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.BoundingBox
import java.io.File
import kotlin.math.cos

/**
 * Demo activity for route navigation.
 */
class RouteNavigationDemoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RouteNavigationDemo"
        
        // Demo route: Hosur (Home) to Bosch Adugodi
        private val DEMO_ORIGIN = RoutePoint(
            12.741472,   // Hosur Home
            77.807583
        )

        private val DEMO_DESTINATION = RoutePoint(
            12.9509,     // Bosch Adugodi
            77.6143
        )

        private val DEMO_DESTINATION_EC360 = RoutePoint(
            12.8408,     // Bosch EC360
            77.6767
        )

    }

    // Routing components
    private lateinit var guidanceManager: RouteGuidanceManager
    private lateinit var instructionEngine: TurnInstructionEngine
    
    // UI
    private lateinit var instructionView: NavigationInstructionView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var modeButton: Button
    private lateinit var originInput: EditText
    private lateinit var destinationInput: EditText
    
    // Current origin and destination
    private var currentOrigin: RoutePoint = DEMO_ORIGIN
    private var currentDestination: RoutePoint = DEMO_DESTINATION
    
    // Waypoints (intermediate stops)
    private val waypoints = mutableListOf<RoutePoint>()
    private val waypointMarkers = mutableListOf<Marker>()
    private lateinit var waypointsContainer: LinearLayout
    
    // Map
    private lateinit var mapView: MapView
    private var routePolyline: Polyline? = null
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var positionMarker: Marker? = null
    
    // Location services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val LOCATION_PERMISSION_REQUEST = 1001
    private var pendingLocationTarget: String? = null // "origin" or "destination"
    
    // Live location tracking
    private var myLocationMarker: Marker? = null      // Fused location (blue)
    private var gpsOnlyMarker: Marker? = null         // Raw GPS location (yellow)
    private var isTrackingLocation = false
    private var currentLocation: Location? = null
    private var lastGpsLocation: Location? = null
    private var lastFusedLocation: Location? = null
    private lateinit var locationCallback: LocationCallback
    private lateinit var myLocationButton: Button
    private lateinit var locationStatusText: TextView  // Shows GPS signal status
    
    // GPS-only location listener
    private var locationManager: LocationManager? = null
    private var gpsLocationListener: LocationListener? = null
    
    // Offline routing
    private lateinit var osmDataDownloader: OsmDataDownloader
    private lateinit var offlineRoutingEngine: OfflineRoutingEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize location services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Initialize OSMDroid
        Configuration.getInstance().userAgentValue = packageName
        
        // Create main layout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        // Instruction view at top (overlays map)
        instructionView = NavigationInstructionView(this)
        
        // Map container (takes most of the screen)
        val mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f  // Take remaining space
            )
        }
        
        // OSMDroid MapView
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            // Center on origin
            controller.setCenter(GeoPoint(currentOrigin.latitude, currentOrigin.longitude))
        }
        mapContainer.addView(mapView)
        
        // Add instruction view overlay on top of map
        mapContainer.addView(instructionView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        })
        
        // Location status panel (floating on map - top right)
        val locationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E0FFFFFF"))
            setPadding(8, 8, 8, 8)
        }
        
        locationStatusText = TextView(this).apply {
            text = "📍 Location: Off"
            textSize = 10f
            setTextColor(Color.DKGRAY)
        }
        locationPanel.addView(locationStatusText)
        
        // Legend for markers
        TextView(this).apply {
            text = "🔵 Fused  🟡 GPS"
            textSize = 9f
            setTextColor(Color.GRAY)
        }.also { locationPanel.addView(it) }
        
        mapContainer.addView(locationPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, 60, 8, 0)
        })
        
        // My Location button (floating on map)
        myLocationButton = Button(this).apply {
            text = "📍"
            textSize = 18f
            minWidth = 56
            minimumWidth = 56
            minHeight = 56
            minimumHeight = 56
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { toggleLocationTracking() }
        }
        mapContainer.addView(myLocationButton, FrameLayout.LayoutParams(
            (56 * resources.displayMetrics.density).toInt(),
            (56 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 16, 16)
        })
        
        rootLayout.addView(mapContainer)
        
        // Bottom panel with inputs, status and buttons - Modern dark theme
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
            background = NavigatorTheme.createGradientBackground()
        }
        
        // Apply dark status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = NavigatorColors.primaryDark
        }
        
        // Main route input container (origin, swap, destination, waypoints)
        val routeInputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Left side: Origin, Waypoints, Destination inputs
        val inputsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        // Origin input row
        val originRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        TextView(this).apply {
            text = "🟢 "
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }.also { originRow.addView(it) }
        
        originInput = EditText(this).apply {
            hint = "Start location"
            setHintTextColor(NavigatorColors.textHint)
            setText("${DEMO_ORIGIN.latitude}, ${DEMO_ORIGIN.longitude}")
            setTextColor(NavigatorColors.textPrimary)
            textSize = 11f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) parseOriginInput() }
        }
        originRow.addView(originInput)
        
        // Current location button for origin
        Button(this).apply {
            text = "📍"
            textSize = 12f
            minWidth = 40
            minimumWidth = 40
            setPadding(4, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { setCurrentLocationAs("origin") }
        }.also { originRow.addView(it) }
        
        inputsColumn.addView(originRow)
        
        // Waypoints container (dynamic - starts empty)
        waypointsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inputsColumn.addView(waypointsContainer)
        
        // Add waypoint button row
        val addWaypointRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 4, 0, 4)
        }
        Button(this).apply {
            text = "➕ Add Stop"
            textSize = 10f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { addWaypoint() }
        }.also { addWaypointRow.addView(it) }
        
        // Show waypoint count
        TextView(this).apply {
            text = ""
            textSize = 10f
            setPadding(8, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }.also { 
            addWaypointRow.addView(it)
        }
        inputsColumn.addView(addWaypointRow)
        
        // Destination input row
        val destRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        TextView(this).apply {
            text = "🔴 "
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }.also { destRow.addView(it) }
        
        destinationInput = EditText(this).apply {
            hint = "Destination"
            setHintTextColor(NavigatorColors.textHint)
            setText("${DEMO_DESTINATION.latitude}, ${DEMO_DESTINATION.longitude}")
            setTextColor(NavigatorColors.textPrimary)
            textSize = 11f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) parseDestinationInput() }
        }
        destRow.addView(destinationInput)
        
        // Current location button for destination
        Button(this).apply {
            text = "📍"
            textSize = 12f
            minWidth = 40
            minimumWidth = 40
            setPadding(4, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { setCurrentLocationAs("destination") }
        }.also { destRow.addView(it) }
        
        inputsColumn.addView(destRow)
        routeInputContainer.addView(inputsColumn)
        
        // Swap button (right side of input container)
        Button(this).apply {
            text = "🔄"
            textSize = 18f
            minWidth = 48
            minimumWidth = 48
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            setOnClickListener { swapOriginDestination() }
        }.also { routeInputContainer.addView(it) }
        
        bottomPanel.addView(routeInputContainer)
        
        // Status text - styled for dark theme
        statusText = TextView(this).apply {
            text = "Tap 'Start' to calculate route"
            textSize = 14f
            setTextColor(NavigatorColors.textPrimary)
            setPadding(0, 8, 0, 8)
        }
        bottomPanel.addView(statusText)
        
        // Buttons Row 1 - Modern styled buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        startButton = Button(this).apply {
            text = "Start"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            setOnClickListener { startNavigation() }
        }
        NavigatorTheme.stylePrimaryButton(startButton, this)
        buttonLayout.addView(startButton)
        
        stopButton = Button(this).apply {
            text = "Stop"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
                marginEnd = 4
            }
            isEnabled = false
            setOnClickListener { stopNavigation() }
        }
        NavigatorTheme.styleOutlineButton(stopButton, this)
        buttonLayout.addView(stopButton)
        
        modeButton = Button(this).apply {
            text = "OSRM"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            setOnClickListener { showModeSelector() }
        }
        NavigatorTheme.styleAccentButton(modeButton, this)
        buttonLayout.addView(modeButton)
        
        bottomPanel.addView(buttonLayout)
        
        // Buttons Row 2 (Map Download & Back) - Modern styled
        val buttonLayout2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        
        Button(this).apply {
            text = "📥 Tiles"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
            setOnClickListener { showMapDownloadDialog() }
        }.also { 
            NavigatorTheme.styleOutlineButton(it, this)
            buttonLayout2.addView(it) 
        }
        
        Button(this).apply {
            text = "📦 Routing"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
                marginEnd = 4
            }
            setOnClickListener { showOfflineRoutingDownloadDialog() }
        }.also { 
            NavigatorTheme.styleOutlineButton(it, this)
            buttonLayout2.addView(it) 
        }
        
        Button(this).apply {
            text = "🏠 Home"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
            }
            setOnClickListener { finish() }
        }.also { 
            NavigatorTheme.styleOutlineButton(it, this)
            buttonLayout2.addView(it) 
        }
        
        bottomPanel.addView(buttonLayout2)
        rootLayout.addView(bottomPanel)
        
        setContentView(rootLayout)
        
        // Add origin and destination markers
        setupMarkers()
        
        // Initialize routing
        initializeRouting()
    }
    
    private fun setupMarkers() {
        // Origin marker (green)
        originMarker = Marker(mapView).apply {
            position = GeoPoint(currentOrigin.latitude, currentOrigin.longitude)
            title = "Start"
            icon = createMarkerIcon(Color.parseColor("#4CAF50")) // Green
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(originMarker)
        
        // Destination marker (red)
        destinationMarker = Marker(mapView).apply {
            position = GeoPoint(currentDestination.latitude, currentDestination.longitude)
            title = "End"
            icon = createMarkerIcon(Color.parseColor("#F44336")) // Red
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(destinationMarker)
        
        // Position marker (blue dot - hidden initially)
        positionMarker = Marker(mapView).apply {
            position = GeoPoint(currentOrigin.latitude, currentOrigin.longitude)
            title = "Current Position"
            icon = createPositionIcon()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        
        mapView.invalidate()
    }
    
    /**
     * Create a pin-style marker icon with the given color.
     */
    private fun createMarkerIcon(color: Int): Drawable {
        val size = (40 * resources.displayMetrics.density).toInt()
        val pinSize = (32 * resources.displayMetrics.density).toInt()
        
        // Outer circle (pin head)
        val pinHead = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
            setSize(pinSize, pinSize)
        }
        
        // Inner dot (white center)
        val innerDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            val dotSize = (12 * resources.displayMetrics.density).toInt()
            setSize(dotSize, dotSize)
        }
        
        // Layer them
        return LayerDrawable(arrayOf(pinHead, innerDot)).apply {
            val inset = (10 * resources.displayMetrics.density).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
    
    /**
     * Create a blue dot icon for current position.
     */
    private fun createPositionIcon(): Drawable {
        val size = (24 * resources.displayMetrics.density).toInt()
        
        // Blue circle with white border
        val blueDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2196F3")) // Blue
            setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
            setSize(size, size)
        }
        
        // Add a subtle glow/shadow effect
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#402196F3")) // Semi-transparent blue
            val glowSize = (size * 1.5).toInt()
            setSize(glowSize, glowSize)
        }
        
        return LayerDrawable(arrayOf(glow, blueDot)).apply {
            val inset = (size * 0.25).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
        // Stop location tracking when activity goes to background
        if (isTrackingLocation) {
            stopLocationTracking()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Ensure location updates are stopped
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun initializeRouting() {
        guidanceManager = RouteGuidanceManager(this)
        instructionEngine = TurnInstructionEngine()
        
        // Initialize offline routing components
        osmDataDownloader = OsmDataDownloader(this)
        offlineRoutingEngine = OfflineRoutingEngine(this)
        
        // Observe guidance state
        guidanceManager.guidanceState.observe(this) { state ->
            handleGuidanceState(state)
        }
        
        // Set up guidance listener
        guidanceManager.setListener(object : RouteGuidanceManager.GuidanceListener {
            override fun onNavigationStarted(route: Route) {
                Log.i(TAG, "Navigation started: ${route.durationText}")
                runOnUiThread {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "Route: ${route.distanceText}, ${route.durationText}", 
                        Toast.LENGTH_SHORT).show()
                }
            }

            override fun onManeuverApproaching(maneuver: Maneuver, distanceMeters: Double) {
                Log.d(TAG, "Approaching: ${maneuver.shortInstruction} in ${distanceMeters}m")
                // Here you would trigger TTS
                val instruction = instructionEngine.getInstruction(
                    maneuver = maneuver,
                    maneuverIndex = 0, // Would be actual index
                    distanceMeters = distanceMeters,
                    speedMps = 10.0
                )
                instruction?.let {
                    Log.i(TAG, "Voice: ${it.voicePrompt}")
                    // tts.speak(it.voicePrompt, ...)
                }
            }

            override fun onManeuverPassed(maneuver: Maneuver) {
                Log.d(TAG, "Passed: ${maneuver.shortInstruction}")
            }

            override fun onRerouting() {
                Log.i(TAG, "Rerouting...")
                runOnUiThread {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "Rerouting...", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onRerouteComplete(newRoute: Route) {
                Log.i(TAG, "New route: ${newRoute.durationText}")
            }

            override fun onArrived() {
                Log.i(TAG, "Arrived at destination!")
                runOnUiThread {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "You have arrived!", Toast.LENGTH_LONG).show()
                    stopNavigation()
                }
            }
        })
        
        updateModeButton()
    }

    private fun handleGuidanceState(state: GuidanceState) {
        instructionView.updateFromGuidanceState(state)
        
        when (state) {
            is GuidanceState.Idle -> {
                statusText.text = "Ready to navigate"
                startButton.isEnabled = true
                stopButton.isEnabled = false
                clearRouteFromMap()
            }
            is GuidanceState.CalculatingRoute -> {
                statusText.text = "Calculating route..."
                startButton.isEnabled = false
                stopButton.isEnabled = true
            }
            is GuidanceState.Navigating -> {
                val progress = (state.progress * 100).toInt()
                statusText.text = "Navigating $progress% | ${formatDistance(state.distanceRemaining)} | ETA: ${formatEta(state.etaSeconds)}"
                startButton.isEnabled = false
                stopButton.isEnabled = true
                
                // Draw route on first navigation state
                if (routePolyline == null) {
                    drawRouteOnMap(state.route)
                }
                
                // Update position marker
                state.positionState.projectedPosition?.let { pos ->
                    updatePositionOnMap(pos.latitude, pos.longitude)
                }
            }
            is GuidanceState.Rerouting -> {
                statusText.text = "Rerouting..."
            }
            is GuidanceState.Arrived -> {
                statusText.text = "Arrived! Total time: ${formatEta(state.totalTimeSeconds)}"
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
            is GuidanceState.RouteFailed -> {
                statusText.text = "Route failed: ${state.error}"
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }
    
    private fun drawRouteOnMap(route: Route) {
        // Remove old polyline if exists
        routePolyline?.let { mapView.overlays.remove(it) }
        
        // Create new polyline from route
        val geoPoints = route.polyline.map { GeoPoint(it.latitude, it.longitude) }
        routePolyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 10f
        }
        
        // Add polyline below markers
        mapView.overlays.add(0, routePolyline)
        
        // Zoom to fit route
        if (geoPoints.isNotEmpty()) {
            val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
            mapView.zoomToBoundingBox(boundingBox, true, 100)
        }
        
        mapView.invalidate()
        Log.i(TAG, "Route drawn on map: ${geoPoints.size} points")
    }
    
    private fun updatePositionOnMap(latitude: Double, longitude: Double) {
        positionMarker?.let { marker ->
            marker.position = GeoPoint(latitude, longitude)
            if (!mapView.overlays.contains(marker)) {
                mapView.overlays.add(marker)
            }
            // Center map on position
            mapView.controller.animateTo(marker.position)
        }
        mapView.invalidate()
    }
    
    private fun clearRouteFromMap() {
        routePolyline?.let { 
            mapView.overlays.remove(it)
            routePolyline = null
        }
        positionMarker?.let { mapView.overlays.remove(it) }
        mapView.invalidate()
    }

    private fun parseOriginInput() {
        val text = originInput.text.toString().trim()
        parseCoordinates(text)?.let { point ->
            currentOrigin = point
            updateOriginMarker()
            Log.i(TAG, "Origin updated: ${point.latitude}, ${point.longitude}")
        } ?: run {
            Toast.makeText(this, "Invalid origin format. Use: lat, lon", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun parseDestinationInput() {
        val text = destinationInput.text.toString().trim()
        parseCoordinates(text)?.let { point ->
            currentDestination = point
            updateDestinationMarker()
            Log.i(TAG, "Destination updated: ${point.latitude}, ${point.longitude}")
        } ?: run {
            Toast.makeText(this, "Invalid destination format. Use: lat, lon", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun parseCoordinates(text: String): RoutePoint? {
        return try {
            val parts = text.split(",").map { it.trim().toDouble() }
            if (parts.size == 2) {
                RoutePoint(parts[0], parts[1])
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // Swap & Waypoints Functions
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Swap origin and destination.
     */
    private fun swapOriginDestination() {
        // Swap the values
        val tempOrigin = currentOrigin
        currentOrigin = currentDestination
        currentDestination = tempOrigin
        
        // Update UI
        originInput.setText("${currentOrigin.latitude}, ${currentOrigin.longitude}")
        destinationInput.setText("${currentDestination.latitude}, ${currentDestination.longitude}")
        
        // Update markers
        updateOriginMarker()
        updateDestinationMarker()
        
        // Reverse waypoints order too
        if (waypoints.isNotEmpty()) {
            waypoints.reverse()
            refreshWaypointsUI()
            updateWaypointMarkers()
        }
        
        Toast.makeText(this, "🔄 Origin ↔ Destination swapped", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Swapped origin and destination")
    }
    
    /**
     * Add a new waypoint.
     */
    private fun addWaypoint() {
        // Show dialog to enter waypoint or use current location
        val options = arrayOf(
            "📍 Use Current Location",
            "📝 Enter Coordinates",
            "🗺️ Pick on Map (center)"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Add Stop Point")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addWaypointFromCurrentLocation()
                    1 -> showWaypointInputDialog()
                    2 -> addWaypointFromMapCenter()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    @SuppressLint("MissingPermission")
    private fun addWaypointFromCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "📍 Getting location...", Toast.LENGTH_SHORT).show()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                location?.let {
                    val waypoint = RoutePoint(it.latitude, it.longitude)
                    addWaypointToList(waypoint)
                }
            }
    }
    
    private fun showWaypointInputDialog() {
        val input = EditText(this).apply {
            hint = "lat, lon (e.g. 12.850, 77.650)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        
        AlertDialog.Builder(this)
            .setTitle("Enter Stop Point")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                parseCoordinates(input.text.toString())?.let { waypoint ->
                    addWaypointToList(waypoint)
                } ?: Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun addWaypointFromMapCenter() {
        val center = mapView.mapCenter
        val waypoint = RoutePoint(center.latitude, center.longitude)
        addWaypointToList(waypoint)
        Toast.makeText(this, "📍 Added map center as stop", Toast.LENGTH_SHORT).show()
    }
    
    private fun addWaypointToList(waypoint: RoutePoint) {
        waypoints.add(waypoint)
        refreshWaypointsUI()
        updateWaypointMarkers()
        Log.i(TAG, "Added waypoint ${waypoints.size}: ${waypoint.latitude}, ${waypoint.longitude}")
    }
    
    private fun removeWaypoint(index: Int) {
        if (index in waypoints.indices) {
            waypoints.removeAt(index)
            refreshWaypointsUI()
            updateWaypointMarkers()
            Log.i(TAG, "Removed waypoint at index $index")
        }
    }
    
    private fun refreshWaypointsUI() {
        waypointsContainer.removeAllViews()
        
        waypoints.forEachIndexed { index, waypoint ->
            val waypointRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 2, 0, 2)
            }
            
            // Waypoint icon (orange)
            TextView(this).apply {
                text = "🟠 "
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_VERTICAL }
            }.also { waypointRow.addView(it) }
            
            // Waypoint coordinates (read-only)
            TextView(this).apply {
                text = "Stop ${index + 1}: %.4f, %.4f".format(waypoint.latitude, waypoint.longitude)
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { gravity = Gravity.CENTER_VERTICAL }
            }.also { waypointRow.addView(it) }
            
            // Remove button
            Button(this).apply {
                text = "✕"
                textSize = 10f
                minWidth = 36
                minimumWidth = 36
                minHeight = 32
                minimumHeight = 32
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { removeWaypoint(index) }
            }.also { waypointRow.addView(it) }
            
            waypointsContainer.addView(waypointRow)
        }
    }
    
    private fun updateWaypointMarkers() {
        // Remove existing waypoint markers
        waypointMarkers.forEach { mapView.overlays.remove(it) }
        waypointMarkers.clear()
        
        // Add new markers for each waypoint
        waypoints.forEachIndexed { index, waypoint ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(waypoint.latitude, waypoint.longitude)
                title = "Stop ${index + 1}"
                icon = createMarkerIcon(Color.parseColor("#FF9800")) // Orange
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            waypointMarkers.add(marker)
            mapView.overlays.add(marker)
        }
        
        mapView.invalidate()
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // Current Location Functions
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Set current GPS location as origin or destination.
     * @param target "origin" or "destination"
     */
    private fun setCurrentLocationAs(target: String) {
        // Check location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            pendingLocationTarget = target
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        
        fetchCurrentLocation(target)
    }
    
    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(target: String) {
        Toast.makeText(this, "📍 Getting current location...", Toast.LENGTH_SHORT).show()
        
        val cancellationToken = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationToken.token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val coordText = "%.6f, %.6f".format(lat, lon)
                
                when (target) {
                    "origin" -> {
                        originInput.setText(coordText)
                        currentOrigin = RoutePoint(lat, lon)
                        updateOriginMarker()
                        mapView.controller.setCenter(GeoPoint(lat, lon))
                        Toast.makeText(this, "📍 Origin set to current location", Toast.LENGTH_SHORT).show()
                    }
                    "destination" -> {
                        destinationInput.setText(coordText)
                        currentDestination = RoutePoint(lat, lon)
                        updateDestinationMarker()
                        Toast.makeText(this, "📍 Destination set to current location", Toast.LENGTH_SHORT).show()
                    }
                }
                mapView.invalidate()
                Log.i(TAG, "Current location set as $target: $lat, $lon")
            } else {
                Toast.makeText(this, "❌ Could not get location. Try again.", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "❌ Location error: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Failed to get location", e)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingLocationTarget?.let { target ->
                    if (target == "tracking") {
                        startLocationTracking()
                    } else {
                        fetchCurrentLocation(target)
                    }
                }
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            }
            pendingLocationTarget = null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // Live Location Tracking (DR/Fused Location)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Toggle live location tracking on/off.
     */
    private fun toggleLocationTracking() {
        if (isTrackingLocation) {
            stopLocationTracking()
        } else {
            // Check permission first
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                pendingLocationTarget = "tracking"
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST
                )
                return
            }
            startLocationTracking()
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        Log.i(TAG, "Starting live location tracking (Fused + GPS)")
        
        // Create Fused location marker (blue) if not exists
        if (myLocationMarker == null) {
            myLocationMarker = Marker(mapView).apply {
                title = "Fused Location"
                icon = createLocationIcon(Color.parseColor("#2196F3")) // Blue
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(myLocationMarker)
        }
        
        // Create GPS-only marker (yellow) if not exists
        if (gpsOnlyMarker == null) {
            gpsOnlyMarker = Marker(mapView).apply {
                title = "GPS Location"
                icon = createLocationIcon(Color.parseColor("#FFC107")) // Yellow/Amber
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(gpsOnlyMarker)
        }
        
        // Fused Location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastFusedLocation = location
                    currentLocation = location
                    updateFusedLocationMarker(location)
                    updateLocationStatus()
                }
            }
        }
        
        // Create location request for Fused updates
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // Update every 1 second
        ).apply {
            setMinUpdateIntervalMillis(500L)
            setMinUpdateDistanceMeters(1f)
        }.build()
        
        // Start Fused location updates
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )
        
        // Start GPS-only updates for comparison
        startGpsOnlyTracking()
        
        isTrackingLocation = true
        myLocationButton.text = "🔵"
        myLocationButton.setBackgroundColor(Color.parseColor("#E3F2FD"))
        locationStatusText.text = "📍 Acquiring..."
        Toast.makeText(this, "📍 Tracking: 🔵Fused + 🟡GPS", Toast.LENGTH_SHORT).show()
        
        // Get immediate fused location
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                location?.let {
                    lastFusedLocation = it
                    currentLocation = it
                    updateFusedLocationMarker(it)
                    mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                    updateLocationStatus()
                }
            }
    }
    
    @SuppressLint("MissingPermission")
    private fun startGpsOnlyTracking() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        gpsLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastGpsLocation = location
                updateGpsLocationMarker(location)
                updateLocationStatus()
            }
            
            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "GPS provider enabled")
                runOnUiThread { 
                    locationStatusText.text = "📍 GPS enabled"
                }
            }
            
            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "GPS provider disabled")
                lastGpsLocation = null
                runOnUiThread {
                    locationStatusText.text = "⚠️ GPS disabled"
                    gpsOnlyMarker?.position = GeoPoint(0.0, 0.0)
                    mapView.invalidate()
                }
            }
            
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API level 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusStr = when (status) {
                    LocationProvider.AVAILABLE -> "Available"
                    LocationProvider.TEMPORARILY_UNAVAILABLE -> "Temporarily Unavailable"
                    LocationProvider.OUT_OF_SERVICE -> "Out of Service"
                    else -> "Unknown"
                }
                Log.i(TAG, "GPS status changed: $statusStr")
            }
        }
        
        // Check if GPS is available
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
        if (!isGpsEnabled) {
            Log.w(TAG, "GPS provider not enabled")
            locationStatusText.text = "⚠️ GPS off (using Fused only)"
        }
        
        // Request GPS-only updates
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 second
                1f,    // 1 meter
                gpsLocationListener!!
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS tracking", e)
            locationStatusText.text = "⚠️ GPS unavailable"
        }
    }
    
    private fun stopLocationTracking() {
        Log.i(TAG, "Stopping live location tracking")
        
        // Stop Fused location updates
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        
        // Stop GPS-only updates
        gpsLocationListener?.let {
            locationManager?.removeUpdates(it)
        }
        gpsLocationListener = null
        
        isTrackingLocation = false
        lastGpsLocation = null
        lastFusedLocation = null
        myLocationButton.text = "📍"
        myLocationButton.setBackgroundColor(Color.WHITE)
        locationStatusText.text = "📍 Location: Off"
        Toast.makeText(this, "📍 Location tracking stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateFusedLocationMarker(location: Location) {
        myLocationMarker?.let { marker ->
            marker.position = GeoPoint(location.latitude, location.longitude)
            if (location.hasBearing()) {
                marker.rotation = -location.bearing
            }
            mapView.invalidate()
            
            // If navigation is active, update guidance
            if (startButton.isEnabled == false) {
                guidanceManager.updatePosition(location.latitude, location.longitude, location.speed.toDouble())
            }
        }
    }
    
    private fun updateGpsLocationMarker(location: Location) {
        gpsOnlyMarker?.let { marker ->
            marker.position = GeoPoint(location.latitude, location.longitude)
            if (location.hasBearing()) {
                marker.rotation = -location.bearing
            }
            mapView.invalidate()
        }
    }
    
    private fun updateLocationStatus() {
        runOnUiThread {
            val gps = lastGpsLocation
            val fused = lastFusedLocation
            
            val statusParts = mutableListOf<String>()
            
            // GPS status
            if (gps != null) {
                val gpsAge = (System.currentTimeMillis() - gps.time) / 1000
                val gpsAcc = gps.accuracy.toInt()
                statusParts.add("🟡GPS: ±${gpsAcc}m")
                if (gpsAge > 5) {
                    statusParts.add("(${gpsAge}s ago)")
                }
            } else {
                val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
                statusParts.add(if (isGpsEnabled) "🟡GPS: No fix" else "🟡GPS: Off")
            }
            
            // Fused status
            if (fused != null) {
                val fusedAcc = fused.accuracy.toInt()
                statusParts.add("🔵Fused: ±${fusedAcc}m")
            } else {
                statusParts.add("🔵Fused: --")
            }
            
            // Distance between GPS and Fused
            if (gps != null && fused != null) {
                val distance = gps.distanceTo(fused)
                if (distance > 1) {
                    statusParts.add("Δ${distance.toInt()}m")
                }
            }
            
            locationStatusText.text = statusParts.joinToString(" | ")
        }
    }
    
    /**
     * Create a location dot icon with the given color.
     */
    private fun createLocationIcon(color: Int): Drawable {
        val size = (24 * resources.displayMetrics.density).toInt()
        
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
            setSize(size, size)
        }
        
        // Outer glow ring
        val glowRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            // Semi-transparent version of the color
            val glowColor = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
            setStroke((2 * resources.displayMetrics.density).toInt(), glowColor)
            val glowSize = (size * 1.4).toInt()
            setSize(glowSize, glowSize)
        }
        
        return LayerDrawable(arrayOf(glowRing, dot)).apply {
            val inset = (size * 0.2).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
    
    private fun updateOriginMarker() {
        originMarker?.position = GeoPoint(currentOrigin.latitude, currentOrigin.longitude)
        mapView.controller.setCenter(GeoPoint(currentOrigin.latitude, currentOrigin.longitude))
        mapView.invalidate()
    }
    
    private fun updateDestinationMarker() {
        destinationMarker?.position = GeoPoint(currentDestination.latitude, currentDestination.longitude)
        mapView.invalidate()
    }

    private fun startNavigation() {
        // Parse inputs before starting
        parseOriginInput()
        parseDestinationInput()
        
        Log.e(TAG, "=== START NAVIGATION BUTTON PRESSED ===")
        
        val waypointInfo = if (waypoints.isNotEmpty()) " with ${waypoints.size} stops" else ""
        Toast.makeText(this, "Starting navigation$waypointInfo...", Toast.LENGTH_SHORT).show()
        
        Log.i(TAG, "Starting navigation from (${currentOrigin.latitude}, ${currentOrigin.longitude}) to (${currentDestination.latitude}, ${currentDestination.longitude}) with ${waypoints.size} waypoints")
        
        // Start navigation with or without waypoints
        if (waypoints.isNotEmpty()) {
            guidanceManager.startNavigation(currentOrigin, currentDestination, waypoints)
        } else {
            guidanceManager.startNavigation(currentOrigin, currentDestination)
        }
        
        // In a real app, you would also:
        // 1. Start listening to NavigationEngine updates
        // 2. Feed positions to guidanceManager.updatePosition()
        // 3. Render route polyline on map
        
        simulatePositionUpdates()
    }

    private fun stopNavigation() {
        Log.i(TAG, "Stopping navigation")
        guidanceManager.stopNavigation()
    }

    private fun showModeSelector() {
        RoutingModeSelector.show(this, guidanceManager.getRoutingMode()) { mode, apiKey, osrmUrl ->
            guidanceManager.setRoutingMode(mode)
            updateModeButton()
            Toast.makeText(this, "Mode: ${mode.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateModeButton() {
        modeButton.text = "Mode: ${guidanceManager.getRoutingMode().name.substringAfter("_")}"
    }

    /**
     * Simulate position updates for demo purposes.
     * In a real app, these would come from NavigationEngine.
     */
    private fun simulatePositionUpdates() {
        lifecycleScope.launch {
            // Simulate a few position updates
            val route = guidanceManager.getCurrentRoute() ?: return@launch
            
            for (i in 0 until minOf(10, route.polyline.size)) {
                delay(1000)
                
                if (!guidanceManager.isNavigating()) break
                
                val point = route.polyline[i]
                guidanceManager.updatePosition(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    speedMps = 15.0 // ~54 km/h
                )
            }
        }
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters < 1000 -> "${meters.toInt()} m"
            else -> String.format("%.1f km", meters / 1000)
        }
    }

    private fun formatEta(seconds: Double): String {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        return when {
            hours > 0 -> "${hours}h ${minutes}min"
            else -> "$minutes min"
        }
    }
    
    /**
     * Show dialog for downloading offline map tiles.
     * OSMDroid supports caching tiles for offline use.
     */
    private fun showMapDownloadDialog() {
        val regions = arrayOf(
            "Current View (Visible Area)",
            "Route Area (Origin to Destination)",
            "Bangalore Region (50km radius)",
            "Karnataka State",
            "Clear Tile Cache"
        )
        
        AlertDialog.Builder(this)
            .setTitle("🗺️ Download Offline Map Tiles")
            .setItems(regions) { _, which ->
                when (which) {
                    0 -> downloadCurrentViewTiles()
                    1 -> downloadRouteAreaTiles()
                    2 -> downloadRegionTiles("Bangalore", 12.9716, 77.5946, 50.0)
                    3 -> downloadRegionTiles("Karnataka", 15.3173, 75.7139, 250.0)
                    4 -> clearTileCache()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun downloadCurrentViewTiles() {
        val bounds = mapView.boundingBox
        val zoomMin = mapView.zoomLevelDouble.toInt().coerceAtLeast(10)
        val zoomMax = (zoomMin + 4).coerceAtMost(18)
        
        downloadTilesForArea(
            "Current View",
            bounds.latSouth, bounds.lonWest,
            bounds.latNorth, bounds.lonEast,
            zoomMin, zoomMax
        )
    }
    
    private fun downloadRouteAreaTiles() {
        val minLat = minOf(currentOrigin.latitude, currentDestination.latitude) - 0.05
        val maxLat = maxOf(currentOrigin.latitude, currentDestination.latitude) + 0.05
        val minLon = minOf(currentOrigin.longitude, currentDestination.longitude) - 0.05
        val maxLon = maxOf(currentOrigin.longitude, currentDestination.longitude) + 0.05
        
        downloadTilesForArea(
            "Route Area",
            minLat, minLon, maxLat, maxLon,
            10, 16
        )
    }
    
    private fun downloadRegionTiles(name: String, centerLat: Double, centerLon: Double, radiusKm: Double) {
        val latDelta = radiusKm / 111.0  // ~111 km per degree latitude
        val lonDelta = radiusKm / (111.0 * cos(Math.toRadians(centerLat)))
        
        downloadTilesForArea(
            name,
            centerLat - latDelta, centerLon - lonDelta,
            centerLat + latDelta, centerLon + lonDelta,
            8, 14  // Lower zoom levels for large regions
        )
    }
    
    /**
     * Create a tile source that allows bulk downloads.
     * Uses a custom policy that permits bulk downloading.
     */
    private fun createBulkDownloadTileSource(): XYTileSource {
        // Create a tile source with permissive policy
        return XYTileSource(
            "OSM_Cacheable",
            1, 19, 256, ".png",
            arrayOf(
                "https://tile.openstreetmap.org/"
            ),
            "© OpenStreetMap contributors",
            TileSourcePolicy(
                4, // Max concurrent downloads (be reasonable)
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
                    TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
                // Note: Not setting FLAG_NO_BULK allows bulk downloads
            )
        )
    }
    
    private fun downloadTilesForArea(
        name: String,
        latSouth: Double, lonWest: Double,
        latNorth: Double, lonEast: Double,
        zoomMin: Int, zoomMax: Int
    ) {
        lifecycleScope.launch {
            try {
                // Temporarily switch to a tile source that allows bulk download
                val originalTileSource = mapView.tileProvider.tileSource
                val bulkTileSource = createBulkDownloadTileSource()
                mapView.setTileSource(bulkTileSource)
                
                val cacheManager = CacheManager(mapView)
                val boundingBox = BoundingBox(latNorth, lonEast, latSouth, lonWest)
                
                // Estimate tile count
                val tileCount = cacheManager.possibleTilesInArea(boundingBox, zoomMin, zoomMax)
                Log.i(TAG, "Downloading ~$tileCount tiles for $name")
                
                runOnUiThread {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "📥 Downloading ~$tileCount tiles for $name...", 
                        Toast.LENGTH_LONG).show()
                }
                
                if (tileCount > 5000) {
                    runOnUiThread {
                        AlertDialog.Builder(this@RouteNavigationDemoActivity)
                            .setTitle("Large Download")
                            .setMessage("This will download ~$tileCount tiles (~${tileCount / 10}MB).\nContinue?")
                            .setPositiveButton("Download") { _, _ ->
                                performTileDownload(cacheManager, boundingBox, zoomMin, zoomMax, name, originalTileSource)
                            }
                            .setNegativeButton("Cancel") { _, _ ->
                                mapView.setTileSource(originalTileSource)
                            }
                            .show()
                    }
                } else {
                    performTileDownload(cacheManager, boundingBox, zoomMin, zoomMax, name, originalTileSource)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tile download error", e)
                runOnUiThread {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun performTileDownload(
        cacheManager: CacheManager,
        boundingBox: BoundingBox,
        zoomMin: Int, zoomMax: Int,
        name: String,
        originalTileSource: ITileSource? = null
    ) {
        cacheManager.downloadAreaAsync(
            this,
            boundingBox,
            zoomMin,
            zoomMax,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    runOnUiThread {
                        // Restore original tile source if needed
                        originalTileSource?.let { mapView.setTileSource(it) }
                        Toast.makeText(this@RouteNavigationDemoActivity, 
                            "✅ $name tiles downloaded!", Toast.LENGTH_LONG).show()
                    }
                }
                
                override fun onTaskFailed(errors: Int) {
                    runOnUiThread {
                        // Restore original tile source if needed
                        originalTileSource?.let { mapView.setTileSource(it) }
                        Toast.makeText(this@RouteNavigationDemoActivity, 
                            "⚠️ Download completed with $errors errors", Toast.LENGTH_SHORT).show()
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
            .setMessage("Delete all downloaded map tiles?\nThis will free up storage but require re-downloading for offline use.")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    val cacheManager = CacheManager(mapView)
                    val cacheDir = Configuration.getInstance().osmdroidTileCache
                    cacheDir?.deleteRecursively()
                    Toast.makeText(this, "🗑️ Tile cache cleared", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Tile cache cleared")
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ Failed to clear cache: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // Offline Routing Data Management
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Show dialog for downloading offline routing data.
     */
    @Suppress("DEPRECATION")
    private fun showOfflineRoutingDownloadDialog() {
        val regions = osmDataDownloader.getAvailableRegions()
        
        // Build status info
        val statusInfo = buildString {
            append("📊 Offline Routing Status\n\n")
            
            when (val status = offlineRoutingEngine.getDataStatus()) {
                is OfflineRoutingEngine.DataStatus.Ready -> {
                    append("✅ Ready: ${status.region}\n")
                    append("   Profiles: ${status.profiles.joinToString()}\n")
                }
                is OfflineRoutingEngine.DataStatus.Error -> {
                    append("❌ Error: ${status.message}\n")
                }
                else -> {
                    append("⚠️ No routing data available\n")
                }
            }
            
            append("\n📦 Storage: ${offlineRoutingEngine.getStorageUsageMb()} MB used")
            append("\n\n─────────────────────────\n")
            append("Select region to download:\n")
        }
        
        // Create scrollable list
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        
        // Status header
        TextView(this).apply {
            text = statusInfo
            textSize = 13f
        }.also { container.addView(it) }
        
        // Region list
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400
            )
        }
        
        val regionList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        regions.forEach { region ->
            val isDownloaded = osmDataDownloader.isRegionDownloaded(region)
            val statusIcon = if (isDownloaded) "✅" else "📥"
            
            Button(this).apply {
                text = "$statusIcon ${region.name} (~${region.approximateSizeMb} MB)\n${region.description}"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    if (isDownloaded) {
                        showImportOrRedownloadDialog(region)
                    } else {
                        startRegionDownload(region)
                    }
                }
            }.also { regionList.addView(it) }
        }
        
        scrollView.addView(regionList)
        container.addView(scrollView)
        
        // Action buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        
        Button(this).apply {
            text = "🔄 Build Graph"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val files = osmDataDownloader.getDownloadedRegions()
                if (files.isNotEmpty()) {
                    importOsmFile(files.first())
                } else {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "No OSM files downloaded yet", Toast.LENGTH_SHORT).show()
                }
            }
        }.also { buttonRow.addView(it) }
        
        Button(this).apply {
            text = "🗑️ Clear All"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                AlertDialog.Builder(this@RouteNavigationDemoActivity)
                    .setTitle("Clear All Data")
                    .setMessage("Delete all offline routing data?")
                    .setPositiveButton("Clear") { _, _ ->
                        offlineRoutingEngine.clearAllData()
                        osmDataDownloader.clearAllDownloads()
                        Toast.makeText(this@RouteNavigationDemoActivity, 
                            "🗑️ All offline data cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.also { buttonRow.addView(it) }
        
        container.addView(buttonRow)
        
        AlertDialog.Builder(this)
            .setTitle("📦 Offline Routing Data")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()
    }
    
    private fun showImportOrRedownloadDialog(region: OsmDataDownloader.OsmRegion) {
        AlertDialog.Builder(this)
            .setTitle(region.name)
            .setMessage("This region is already downloaded.\n\nWhat would you like to do?")
            .setPositiveButton("Build Graph") { _, _ ->
                val file = osmDataDownloader.getRegionFile(region)
                importOsmFile(file)
            }
            .setNeutralButton("Re-download") { _, _ ->
                osmDataDownloader.deleteRegion(region)
                startRegionDownload(region)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    @Suppress("DEPRECATION")
    private fun startRegionDownload(region: OsmDataDownloader.OsmRegion) {
        // ProgressDialog is deprecated; create a simple AlertDialog with an embedded ProgressBar
        val progressBar = ProgressBar(this, null, R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading ${region.name}")
            .setView(progressBar)
            .setCancelable(false)
            .create().apply { show() }

        lifecycleScope.launch {
            val file = osmDataDownloader.downloadRegion(region) { progress, status ->
                // Ensure progress update runs on UI thread
                runOnUiThread {
                    progressBar.progress = (progress * 100).toInt()
                }
            }
            
            runOnUiThread {
                dialog.dismiss()

                if (file != null) {
                    AlertDialog.Builder(this@RouteNavigationDemoActivity)
                        .setTitle("Download Complete")
                        .setMessage("${region.name} downloaded successfully.\n\nBuild routing graph now?\n(This may take a few minutes)")
                        .setPositiveButton("Build Now") { _, _ ->
                            importOsmFile(file)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    Toast.makeText(this@RouteNavigationDemoActivity,
                        "❌ Download failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    @Suppress("DEPRECATION")
    private fun importOsmFile(file: File) {
        val progressBar = ProgressBar(this, null, R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Building Routing Graph")
            .setView(progressBar)
            .setCancelable(false)
            .create().apply { show() }

        lifecycleScope.launch {
            val success = offlineRoutingEngine.importOsmData(file) { progress, status ->
                runOnUiThread {
                    progressBar.progress = (progress * 100).toInt()
                }
            }
            
            runOnUiThread {
                dialog.dismiss()

                if (success) {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "✅ Offline routing ready!", Toast.LENGTH_LONG).show()
                    
                    // Update mode button to show offline is available
                    updateModeButton()
                } else {
                    Toast.makeText(this@RouteNavigationDemoActivity, 
                        "❌ Failed to build routing graph", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
