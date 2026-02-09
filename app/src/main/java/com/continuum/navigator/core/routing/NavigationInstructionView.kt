/**
 * Navigation Instruction View
 *
 * Displays turn-by-turn navigation instructions as an overlay.
 * Shows upcoming maneuver icon, distance, street name, and ETA.
 *
 * ## Features
 *
 * - Large maneuver icon with direction
 * - Distance to next turn
 * - Street name
 * - ETA and remaining distance
 * - Compact and expanded modes
 *
 * ## Usage
 *
 * ```xml
 * <com.navigator.core.routing.NavigationInstructionView
 *     android:id="@+id/navigationInstructions"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content" />
 * ```
 *
 * ```kotlin
 * navigationInstructionView.updateInstruction(turnInstruction)
 * navigationInstructionView.updateProgress(distanceRemaining, etaSeconds)
 * ```
 */
package com.continuum.navigator.core.routing

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.continuum.navigator.core.R

/**
 * View displaying navigation turn instructions.
 */
class NavigationInstructionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Views
    private val maneuverIcon: ImageView
    private val distanceText: TextView
    private val instructionText: TextView
    private val streetNameText: TextView
    private val etaText: TextView
    private val remainingDistanceText: TextView
    
    // Bottom bar
    private val bottomBar: LinearLayout

    // Current state
    private var currentInstruction: TurnInstruction? = null
    private var useMetricUnits: Boolean = true

    init {
        orientation = VERTICAL
        
        // Main instruction card
        val instructionCard = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = resources.getDimensionPixelSize(R.dimen.nav_instruction_padding_horizontal)
            val padV = resources.getDimensionPixelSize(R.dimen.nav_instruction_padding_vertical)
            setPadding(padH, padV, padH, padV)
            val radius = resources.getDimension(R.dimen.nav_corner_radius)
            background = createRoundedBackground(Color.parseColor("#1A73E8"), radius)
        }
        
        // Maneuver icon
        maneuverIcon = ImageView(context).apply {
            val iconSize = resources.getDimensionPixelSize(R.dimen.nav_icon_size)
            layoutParams = LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.WHITE)
        }
        instructionCard.addView(maneuverIcon)
        
        // Text container
        val textContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.nav_instruction_text_margin_start)
            }
        }
        
        // Distance to turn
        distanceText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.nav_distance_text_size))
            typeface = Typeface.DEFAULT_BOLD
            text = "--"
        }
        textContainer.addView(distanceText)
        
        // Instruction
        instructionText = TextView(context).apply {
            setTextColor(Color.parseColor("#E8F0FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.nav_instruction_text_size))
            text = "Navigate"
        }
        textContainer.addView(instructionText)
        
        // Street name
        streetNameText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.nav_street_text_size))
            typeface = Typeface.DEFAULT_BOLD
            visibility = GONE
        }
        textContainer.addView(streetNameText)
        
        instructionCard.addView(textContainer)
        addView(instructionCard)
        
        // Bottom bar with ETA and remaining distance
        bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bPadH = resources.getDimensionPixelSize(R.dimen.nav_bottom_bar_padding_horizontal)
            val bPadV = resources.getDimensionPixelSize(R.dimen.nav_bottom_bar_padding_vertical)
            setPadding(bPadH, bPadV, bPadH, bPadV)
            setBackgroundColor(Color.parseColor("#303030"))
        }
        
        // ETA
        etaText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            text = "-- min"
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomBar.addView(etaText)
        
        // Remaining distance
        remainingDistanceText = TextView(context).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "-- km"
            gravity = Gravity.END
        }
        bottomBar.addView(remainingDistanceText)
        
        addView(bottomBar)
        
        // Set initial placeholder
        setPlaceholder()

        // Apply orientation-specific compacting for landscape to preserve map visibility
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Hide bottom bar to save vertical space
            setBottomBarVisible(false)

            // Scale down icon and distance text to be less intrusive
            try {
                val iconLp = maneuverIcon.layoutParams
                val iconSize = resources.getDimensionPixelSize(R.dimen.nav_icon_size)
                val compactIcon = (iconSize * 0.65f).toInt()
                iconLp.width = compactIcon
                iconLp.height = compactIcon
                maneuverIcon.layoutParams = iconLp

                val baseDistPx = resources.getDimension(R.dimen.nav_distance_text_size)
                distanceText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseDistPx * 0.6f)

                // Reduce instruction text slightly
                val baseInstrPx = resources.getDimension(R.dimen.nav_instruction_text_size)
                instructionText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseInstrPx * 0.85f)

                // Make card semi-transparent to let map be visible beneath
                val card = getChildAt(0) as? LinearLayout
                card?.alpha = 0.95f
            } catch (e: Exception) {
                // If any layout operations fail at init, ignore and keep defaults
            }
        }
    }

    /**
     * Update with a new turn instruction.
     */
    fun updateInstruction(instruction: TurnInstruction) {
        currentInstruction = instruction
        
        // Update icon
        val iconRes = ManeuverIcons.getIcon(instruction.maneuver.type)
        maneuverIcon.setImageResource(iconRes)
        
        // Apply rotation for directional icons
        val rotation = ManeuverIcons.getRotationDegrees(instruction.maneuver.type)
        maneuverIcon.rotation = rotation
        
        // Update distance
        distanceText.text = instruction.distanceText
        
        // Update instruction text
        instructionText.text = instruction.visualText
        
        // Update street name
        if (!instruction.streetName.isNullOrEmpty()) {
            streetNameText.text = instruction.streetName
            streetNameText.visibility = VISIBLE
        } else {
            streetNameText.visibility = GONE
        }
        
        // Update background color based on urgency
        updateUrgencyColor(instruction.level)
    }

    /**
     * Update progress info (ETA and remaining distance).
     */
    fun updateProgress(distanceRemainingMeters: Double, etaSeconds: Double) {
        // Format ETA
        val hours = (etaSeconds / 3600).toInt()
        val minutes = ((etaSeconds % 3600) / 60).toInt()
        
        etaText.text = when {
            hours > 0 -> "${hours}h ${minutes}min"
            minutes > 0 -> "$minutes min"
            else -> "< 1 min"
        }
        
        // Format remaining distance
        remainingDistanceText.text = if (useMetricUnits) {
            when {
                distanceRemainingMeters < 1000 -> "${distanceRemainingMeters.toInt()} m"
                else -> String.format("%.1f km", distanceRemainingMeters / 1000)
            }
        } else {
            val miles = distanceRemainingMeters / 1609.34
            when {
                miles < 0.1 -> "${(distanceRemainingMeters * 3.28084).toInt()} ft"
                else -> String.format("%.1f mi", miles)
            }
        }
    }

    /**
     * Update from guidance state.
     */
    fun updateFromGuidanceState(state: GuidanceState) {
        when (state) {
            is GuidanceState.Idle -> {
                setPlaceholder()
            }
            is GuidanceState.CalculatingRoute -> {
                instructionText.text = "Calculating route..."
                distanceText.text = "..."
                streetNameText.visibility = GONE
            }
            is GuidanceState.Navigating -> {
                state.nextManeuver?.let { maneuver ->
                    val visualInstruction = TurnInstructionEngine().getVisualInstruction(
                        maneuver,
                        state.distanceToNextManeuver
                    )
                    updateInstruction(visualInstruction)
                }
                updateProgress(state.distanceRemaining, state.etaSeconds)
            }
            is GuidanceState.Rerouting -> {
                instructionText.text = "Rerouting..."
                distanceText.text = "..."
            }
            is GuidanceState.Arrived -> {
                val arrivalInstruction = TurnInstructionEngine().getArrivalInstruction()
                updateInstruction(arrivalInstruction)
                etaText.text = "Arrived"
                remainingDistanceText.text = ""
            }
            is GuidanceState.RouteFailed -> {
                instructionText.text = "Route failed"
                distanceText.text = "!"
                streetNameText.text = state.error
                streetNameText.visibility = VISIBLE
            }
        }
    }

    /**
     * Set metric or imperial units.
     */
    fun setUseMetricUnits(metric: Boolean) {
        useMetricUnits = metric
    }

    /**
     * Show/hide the bottom bar.
     */
    fun setBottomBarVisible(visible: Boolean) {
        bottomBar.visibility = if (visible) VISIBLE else GONE
    }

    private fun setPlaceholder() {
        maneuverIcon.setImageResource(R.drawable.ic_arrow_forward)
        maneuverIcon.rotation = 0f
        distanceText.text = "--"
        instructionText.text = "Start navigation"
        streetNameText.visibility = GONE
        etaText.text = "-- min"
        remainingDistanceText.text = "-- km"
    }

        private fun updateUrgencyColor(level: AnnouncementLevel) {
        val color = when (level) {
            AnnouncementLevel.NOW -> Color.parseColor("#D32F2F")      // Red
            AnnouncementLevel.SOON -> Color.parseColor("#F57C00")      // Orange
            AnnouncementLevel.PREPARE -> Color.parseColor("#1976D2")   // Blue
            AnnouncementLevel.FAR -> Color.parseColor("#1A73E8")       // Default blue
            AnnouncementLevel.CONTINUE -> Color.parseColor("#388E3C")  // Green
        }
        
        val card = getChildAt(0) as? LinearLayout
        val radius = resources.getDimension(R.dimen.nav_corner_radius)
        card?.background = createRoundedBackground(color, radius)
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
