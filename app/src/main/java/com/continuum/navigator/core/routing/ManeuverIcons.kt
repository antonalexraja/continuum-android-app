/**
 * Maneuver Icons
 *
 * Maps ManeuverType to drawable resource IDs for visual display.
 * Provides both standard and directional icons.
 *
 * ## Usage
 *
 * ```kotlin
 * val iconRes = ManeuverIcons.getIcon(maneuver.type)
 * imageView.setImageResource(iconRes)
 * ```
 */
package com.continuum.navigator.core.routing

import com.continuum.navigator.core.R

/**
 * Maps maneuver types to icon resources.
 */
object ManeuverIcons {
    
    /**
     * Get the icon resource for a maneuver type.
     * Falls back to default arrow if no specific icon exists.
     */
    fun getIcon(type: ManeuverType): Int {
        return iconMap[type] ?: R.drawable.ic_arrow_forward
    }
    
    /**
     * Get the icon resource with fallback.
     */
    fun getIconOrDefault(type: ManeuverType, default: Int): Int {
        return iconMap[type] ?: default
    }
    
    /**
     * Check if a specific icon exists for this maneuver type.
     */
    fun hasIcon(type: ManeuverType): Boolean {
        return iconMap.containsKey(type)
    }
    
    /**
     * Get rotation angle for arrow-based icons.
     * Useful when using a single arrow drawable with rotation.
     */
    fun getRotationDegrees(type: ManeuverType): Float {
        return when (type) {
            ManeuverType.STRAIGHT -> 0f
            ManeuverType.SLIGHT_RIGHT -> 45f
            ManeuverType.TURN_RIGHT -> 90f
            ManeuverType.SHARP_RIGHT -> 135f
            ManeuverType.UTURN_RIGHT -> 180f
            ManeuverType.SLIGHT_LEFT -> -45f
            ManeuverType.TURN_LEFT -> -90f
            ManeuverType.SHARP_LEFT -> -135f
            ManeuverType.UTURN_LEFT -> 180f
            else -> 0f
        }
    }
    
    /**
     * Get background color resource based on instruction urgency.
     */
    fun getBackgroundColor(level: AnnouncementLevel): Int {
        return when (level) {
            AnnouncementLevel.NOW -> android.R.color.holo_red_light
            AnnouncementLevel.SOON -> android.R.color.holo_orange_light
            AnnouncementLevel.PREPARE -> android.R.color.holo_blue_light
            AnnouncementLevel.FAR -> android.R.color.darker_gray
            AnnouncementLevel.CONTINUE -> android.R.color.darker_gray
        }
    }
    
    // Icon mapping - uses standard Android drawables as fallback
    // In a real app, these would be custom navigation icons
    private val iconMap: Map<ManeuverType, Int> = mapOf(
        ManeuverType.DEPART to R.drawable.ic_depart,
        ManeuverType.ARRIVE to R.drawable.ic_arrive,
        ManeuverType.STRAIGHT to R.drawable.ic_arrow_forward,
        ManeuverType.SLIGHT_RIGHT to R.drawable.ic_turn_slight_right,
        ManeuverType.TURN_RIGHT to R.drawable.ic_turn_right,
        ManeuverType.SHARP_RIGHT to R.drawable.ic_turn_sharp_right,
        ManeuverType.UTURN_RIGHT to R.drawable.ic_uturn,
        ManeuverType.SLIGHT_LEFT to R.drawable.ic_turn_slight_left,
        ManeuverType.TURN_LEFT to R.drawable.ic_turn_left,
        ManeuverType.SHARP_LEFT to R.drawable.ic_turn_sharp_left,
        ManeuverType.UTURN_LEFT to R.drawable.ic_uturn,
        ManeuverType.MERGE to R.drawable.ic_merge,
        ManeuverType.FORK to R.drawable.ic_fork,
        ManeuverType.RAMP to R.drawable.ic_ramp,
        ManeuverType.ROUNDABOUT_ENTER to R.drawable.ic_roundabout,
        ManeuverType.ROUNDABOUT_EXIT to R.drawable.ic_roundabout,
        ManeuverType.FERRY to R.drawable.ic_ferry
    )
}
