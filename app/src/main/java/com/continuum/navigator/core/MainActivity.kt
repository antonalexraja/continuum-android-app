/**
 * Navigator Control Center
 *
 * This activity provides the main interface for:
 * - Launching navigation and mapping
 * - Managing the Navigation Service
 * - Monitoring system status and native engine logs
 */
package com.continuum.navigator.core

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.continuum.navigator.core.map.MapActivity
import com.continuum.navigator.core.native.NavigatorNative
import com.continuum.navigator.core.routing.RouteNavigationDemoActivity
import com.continuum.navigator.core.service.NavigationService
import com.continuum.navigator.core.service.NavigationServiceState
import com.continuum.navigator.core.ui.NavigatorColors
import com.continuum.navigator.core.ui.NavigatorTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnTestNative: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnOpenMap: Button
    private lateinit var btnRouteNavigation: Button
    private lateinit var btnClearLog: Button

    // Service binding
    private var serviceBinder: NavigationService.LocalBinder? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBinder = service as? NavigationService.LocalBinder
            serviceBound = true
            log("Service connected")
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            serviceBound = false
            log("Service disconnected")
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            log("All permissions granted")
        } else {
            log("WARNING: Some permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, NavigationService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupUI() {
        val density = resources.displayMetrics.density
        
        val rootLayout = FrameLayout(this).apply {
            background = NavigatorTheme.createGradientBackground()
        }
        
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (40 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, (32 * density).toInt())
        }
        
        TextView(this).apply {
            text = "NAVIGATOR"
            textSize = 28f
            letterSpacing = 0.2f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }.also { header.addView(it) }
        
        statusText = TextView(this).apply {
            text = "Engine Standby"
            textSize = 14f
            setTextColor(NavigatorColors.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        header.addView(statusText)
        mainLayout.addView(header)
        
        // Navigation Cards
        val cardsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        btnOpenMap = Button(this).apply {
            text = "Launch Map Navigation"
            setOnClickListener { openMapActivity() }
        }
        NavigatorTheme.stylePrimaryButton(btnOpenMap, this)
        cardsLayout.addView(btnOpenMap)
        
        btnRouteNavigation = Button(this).apply {
            text = "Route Planning Demo"
            setOnClickListener { openRouteNavigation() }
        }
        NavigatorTheme.styleOutlineButton(btnRouteNavigation, this, Color.WHITE)
        btnRouteNavigation.apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = (12 * density).toInt()
            layoutParams = params
        }
        cardsLayout.addView(btnRouteNavigation)
        
        mainLayout.addView(cardsLayout)
        
        // Divider
        View(this).apply {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt())
            params.setMargins(0, (32 * density).toInt(), 0, (16 * density).toInt())
            layoutParams = params
            setBackgroundColor(Color.argb(30, 255, 255, 255))
        }.also { mainLayout.addView(it) }
        
        // Service Controls
        val controlsLabel = TextView(this).apply {
            text = "SYSTEM CONTROLS"
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(NavigatorColors.textSecondary)
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        mainLayout.addView(controlsLabel)
        
        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        btnStartService = Button(this).apply {
            text = "Start Engine"
            setOnClickListener { startNavigationService() }
        }
        NavigatorTheme.styleOutlineButton(btnStartService, this, NavigatorColors.success)
        btnStartService.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (6 * density).toInt()
        }
        serviceRow.addView(btnStartService)

        btnStopService = Button(this).apply {
            text = "Stop Engine"
            setOnClickListener { stopNavigationService() }
        }
        NavigatorTheme.styleOutlineButton(btnStopService, this, NavigatorColors.error)
        btnStopService.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (6 * density).toInt()
        }
        serviceRow.addView(btnStopService)
        mainLayout.addView(serviceRow)
        
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, (12 * density).toInt(), 0, 0)
        }

        btnTestNative = Button(this).apply {
            text = "Run Diagnostics"
            setOnClickListener { testNativeLibrary() }
        }
        NavigatorTheme.styleOutlineButton(btnTestNative, this, NavigatorColors.textSecondary)
        btnTestNative.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (6 * density).toInt()
        }
        toolRow.addView(btnTestNative)

        btnClearLog = Button(this).apply {
            text = "Clear Terminal"
            setOnClickListener { clearLog() }
        }
        NavigatorTheme.styleOutlineButton(btnClearLog, this, NavigatorColors.textSecondary)
        btnClearLog.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (6 * density).toInt()
        }
        toolRow.addView(btnClearLog)
        mainLayout.addView(toolRow)
        
        // Terminal View
        val terminalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NavigatorTheme.createGlassCard(this@MainActivity)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            params.topMargin = (24 * density).toInt()
            layoutParams = params
        }
        
        TextView(this).apply {
            text = "SYSTEM TERMINAL"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(NavigatorColors.accent)
            setPadding(0, 0, 0, (8 * density).toInt())
        }.also { terminalCard.addView(it) }

        logText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#C0C0C0"))
            typeface = Typeface.MONOSPACE
        }

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            addView(logText)
        }
        terminalCard.addView(logScroll)
        mainLayout.addView(terminalCard)

        rootLayout.addView(mainLayout)
        setContentView(rootLayout)
        
        NavigatorTheme.applyDarkTheme(this, rootLayout)

        log("System Initialized")
        log("Native Engine v${NavigatorNative.Companion.version()}")
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun testNativeLibrary() {
        log("Running diagnostic suite...")
        try {
            val loaded = NavigatorNative.Companion.isLibraryLoaded()
            log("Engine Load: $loaded")
            val version = NavigatorNative.Companion.version()
            log("Engine Version: $version")
            val nav = NavigatorNative.Companion.create()
            log("Instance: ${nav.isInitialized()}")
            nav.destroy()
            log("Diagnostics complete.")
        } catch (e: Exception) {
            log("Diagnostic Failure: ${e.message}")
        }
    }

    private fun startNavigationService() {
        log("Launching Navigation Engine...")
        NavigationService.Companion.start(this, null)
    }

    private fun stopNavigationService() {
        log("Terminating Navigation Engine...")
        NavigationService.Companion.stop(this)
    }

    private fun openMapActivity() {
        startActivity(MapActivity.Companion.newIntent(this))
    }

    private fun openRouteNavigation() {
        startActivity(Intent(this, RouteNavigationDemoActivity::class.java))
    }

    private fun observeService() {
        serviceBinder?.let { binder ->
            binder.serviceState.observe(this) { state ->
                updateStatus(state)
            }
        }
    }

    private fun updateStatus(state: NavigationServiceState) {
        statusText.text = when (state) {
            NavigationServiceState.STOPPED -> "Engine Standby"
            NavigationServiceState.STARTING -> "Initializing..."
            NavigationServiceState.RUNNING -> "Engine Active"
            NavigationServiceState.ERROR -> "Engine Error"
        }
        statusText.setTextColor(if (state == NavigationServiceState.RUNNING) NavigatorColors.success else NavigatorColors.accent)
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            logText.append("[$timestamp] $message\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        logText.text = ""
        log("Log cleared")
    }
}
