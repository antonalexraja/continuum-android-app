/**
 * Routing Mode Selector
 *
 * UI dialog for selecting routing provider and configuring settings.
 *
 * ## Usage
 *
 * ```kotlin
 * RoutingModeSelector.show(
 *     context = this,
 *     currentMode = routingService.getMode(),
 *     onModeSelected = { mode, apiKey, osrmUrl ->
 *         routingService.setMode(mode, apiKey, osrmUrl)
 *     }
 * )
 * ```
 */
package com.continuum.navigator.core.routing

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*
import kotlin.collections.get

/**
 * Dialog for selecting routing mode.
 */
object RoutingModeSelector {

    /**
     * Show routing mode selection dialog.
     *
     * @param context Android context
     * @param currentMode Currently selected mode
     * @param currentGoogleApiKey Current Google API key
     * @param currentOsrmUrl Current OSRM server URL
     * @param onModeSelected Callback when mode is selected
     */
    fun show(
        context: Context,
        currentMode: RoutingMode = RoutingMode.ONLINE_OSRM,
        currentGoogleApiKey: String = "",
        currentOsrmUrl: String = "https://router.project-osrm.org",
        onModeSelected: (mode: RoutingMode, googleApiKey: String, osrmUrl: String) -> Unit
    ) {
        // Create container
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        // Mode selection radio group
        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        val modes = listOf(
            Triple(RoutingMode.ONLINE_GOOGLE, "Google Directions", "Accurate, traffic-aware (requires API key)"),
            Triple(RoutingMode.ONLINE_OSRM, "OSRM Online", "Free, no API key, no traffic"),
            Triple(RoutingMode.OFFLINE_OSM, "Offline OSM", "Works offline (Phase RG-5)"),
            Triple(RoutingMode.HYBRID_FALLBACK, "Hybrid Fallback", "Tries all providers (Phase RG-5)")
        )

        val radioButtons = modes.map { (mode, label, description) ->
            RadioButton(context).apply {
                text = label
                id = View.generateViewId()
                isChecked = (mode == currentMode)
                isEnabled = true // All modes selectable
                
                // Add description below radio button
                val desc = TextView(context).apply {
                    text = description
                    textSize = 12f
                    setPadding(60, 0, 0, 16)
                    setTextColor(0xFF666666.toInt())
                }
                
                radioGroup.addView(this)
                radioGroup.addView(desc)
            }
        }.associateWith { modes[radioGroup.indexOfChild(it) / 2].first }

        container.addView(radioGroup)

        // Google API key input
        val googleApiKeyLabel = TextView(context).apply {
            text = "Google API Key:"
            setPadding(0, 24, 0, 8)
        }
        val googleApiKeyInput = EditText(context).apply {
            hint = "Enter Google Directions API key"
            setText(currentGoogleApiKey)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(googleApiKeyLabel)
        container.addView(googleApiKeyInput)

        // OSRM URL input
        val osrmUrlLabel = TextView(context).apply {
            text = "OSRM Server URL:"
            setPadding(0, 24, 0, 8)
        }
        val osrmUrlInput = EditText(context).apply {
            hint = "https://router.project-osrm.org"
            setText(currentOsrmUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(osrmUrlLabel)
        container.addView(osrmUrlInput)

        // Info text
        val infoText = TextView(context).apply {
            text = "\n💡 Google requires an API key and costs money at scale.\n" +
                   "OSRM is free but has no traffic data.\n" +
                   "⚠️ Offline/Hybrid modes require downloaded map data."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        container.addView(infoText)

        // Show dialog
        AlertDialog.Builder(context)
            .setTitle("Select Routing Provider")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val selectedButton = radioButtons.keys.find { it.isChecked }
                val selectedMode = radioButtons[selectedButton] ?: currentMode
                val apiKey = googleApiKeyInput.text.toString()
                val osrmUrl = osrmUrlInput.text.toString().ifBlank { currentOsrmUrl }
                
                onModeSelected(selectedMode, apiKey, osrmUrl)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show a simple toast with routing mode info.
     */
    fun showQuickInfo(context: Context, mode: RoutingMode) {
        val message = when (mode) {
            RoutingMode.ONLINE_GOOGLE -> "Using Google Directions (traffic-aware)"
            RoutingMode.ONLINE_OSRM -> "Using OSRM (free, no traffic)"
            RoutingMode.OFFLINE_OSM -> "Using offline routing"
            RoutingMode.HYBRID_FALLBACK -> "Using hybrid fallback mode"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
