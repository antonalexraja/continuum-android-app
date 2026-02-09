/**
 * Turn Instruction Engine
 *
 * Generates context-aware turn-by-turn instructions for voice and visual display.
 * Uses distance thresholds to provide timely announcements at appropriate moments.
 *
 * ## Features
 *
 * - Distance-based announcement thresholds (500m, 200m, 100m, now)
 * - Speed-adaptive timing (shorter warnings at higher speeds)
 * - Voice prompt templates with TTS-friendly text
 * - Visual instruction formatting
 * - Lane guidance support
 * - Instruction deduplication (prevents repeat announcements)
 *
 * ## Usage
 *
 * ```kotlin
 * val engine = TurnInstructionEngine()
 * 
 * // On each position update
 * val instruction = engine.getInstruction(
 *     maneuver = nextManeuver,
 *     distanceMeters = 450.0,
 *     speedMps = 13.0  // ~50 km/h
 * )
 * 
 * if (instruction != null) {
 *     tts.speak(instruction.voicePrompt)
 *     displayInstruction(instruction)
 * }
 * ```
 */
package com.continuum.navigator.core.routing

import java.util.Locale

/**
 * Announcement threshold level.
 */
enum class AnnouncementLevel {
    /** Far announcement (~500m or ~30s away) */
    FAR,
    /** Prepare announcement (~200m or ~15s away) */
    PREPARE,
    /** Soon announcement (~100m or ~7s away) */
    SOON,
    /** Immediate announcement (at turn) */
    NOW,
    /** Continue on current road (no turn imminent) */
    CONTINUE
}

/**
 * A turn instruction ready for display or TTS.
 */
data class TurnInstruction(
    /** The maneuver this instruction refers to */
    val maneuver: Maneuver,
    
    /** Announcement level/urgency */
    val level: AnnouncementLevel,
    
    /** Distance to maneuver in meters */
    val distanceMeters: Double,
    
    /** Voice prompt (TTS-friendly text) */
    val voicePrompt: String,
    
    /** Short visual text for display */
    val visualText: String,
    
    /** Distance text for display (e.g., "500 m", "0.3 mi") */
    val distanceText: String,
    
    /** Street name to turn onto (if available) */
    val streetName: String?,
    
    /** Whether this instruction has been announced */
    var announced: Boolean = false,
    
    /** Timestamp when this instruction was generated */
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Configuration for turn instruction generation.
 */
data class TurnInstructionConfig(
    /** Use metric units (meters/km) vs imperial (feet/miles) */
    val useMetricUnits: Boolean = true,
    
    /** Language/locale for instructions */
    val locale: Locale = Locale.getDefault(),
    
    /** Far announcement distance (meters) */
    val farDistanceMeters: Double = 500.0,
    
    /** Prepare announcement distance (meters) */
    val prepareDistanceMeters: Double = 200.0,
    
    /** Soon announcement distance (meters) */
    val soonDistanceMeters: Double = 100.0,
    
    /** Now announcement distance (meters) */
    val nowDistanceMeters: Double = 30.0,
    
    /** Minimum time between same-level announcements (ms) */
    val minAnnouncementIntervalMs: Long = 5000,
    
    /** Use time-based thresholds at high speed */
    val useTimeBasedThresholds: Boolean = true,
    
    /** Speed threshold for time-based announcements (m/s) ~60 km/h */
    val highSpeedThresholdMps: Double = 16.7,
    
    /** Far announcement time (seconds) at high speed */
    val farTimeSeconds: Double = 30.0,
    
    /** Prepare announcement time (seconds) at high speed */
    val prepareTimeSeconds: Double = 15.0,
    
    /** Soon announcement time (seconds) at high speed */
    val soonTimeSeconds: Double = 7.0
)

/**
 * Generates turn-by-turn instructions.
 */
class TurnInstructionEngine(
    private val config: TurnInstructionConfig = TurnInstructionConfig()
) {
    // Track announced levels to prevent duplicates
    private var lastManeuverIndex: Int = -1
    private var announcedLevels: MutableSet<AnnouncementLevel> = mutableSetOf()
    private var lastAnnouncementTime: Long = 0

    /**
     * Get instruction for current position relative to next maneuver.
     *
     * @param maneuver The upcoming maneuver
     * @param maneuverIndex Index of the maneuver (for deduplication)
     * @param distanceMeters Distance to the maneuver
     * @param speedMps Current speed in m/s
     * @return TurnInstruction if one should be announced, null otherwise
     */
    fun getInstruction(
        maneuver: Maneuver,
        maneuverIndex: Int,
        distanceMeters: Double,
        speedMps: Double
    ): TurnInstruction? {
        // Reset tracking if we've moved to a new maneuver
        if (maneuverIndex != lastManeuverIndex) {
            lastManeuverIndex = maneuverIndex
            announcedLevels.clear()
        }

        // Determine announcement level
        val level = determineLevel(distanceMeters, speedMps)
        
        // Skip if already announced at this level
        if (level in announcedLevels) {
            return null
        }
        
        // Skip if too soon since last announcement (except for NOW)
        val now = System.currentTimeMillis()
        if (level != AnnouncementLevel.NOW && 
            now - lastAnnouncementTime < config.minAnnouncementIntervalMs) {
            return null
        }
        
        // Skip CONTINUE level (no announcement needed)
        if (level == AnnouncementLevel.CONTINUE) {
            return null
        }
        
        // Generate instruction
        val instruction = generateInstruction(maneuver, level, distanceMeters)
        
        // Mark as announced
        announcedLevels.add(level)
        lastAnnouncementTime = now
        
        return instruction
    }

    /**
     * Force reset of announcement tracking (e.g., after reroute).
     */
    fun reset() {
        lastManeuverIndex = -1
        announcedLevels.clear()
        lastAnnouncementTime = 0
    }

    /**
     * Get a visual-only instruction (no TTS, no deduplication).
     * Use for continuous display updates.
     */
    fun getVisualInstruction(
        maneuver: Maneuver,
        distanceMeters: Double
    ): TurnInstruction {
        val level = when {
            distanceMeters < config.nowDistanceMeters -> AnnouncementLevel.NOW
            distanceMeters < config.soonDistanceMeters -> AnnouncementLevel.SOON
            distanceMeters < config.prepareDistanceMeters -> AnnouncementLevel.PREPARE
            distanceMeters < config.farDistanceMeters -> AnnouncementLevel.FAR
            else -> AnnouncementLevel.CONTINUE
        }
        
        return generateInstruction(maneuver, level, distanceMeters)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Level Determination
    // ═══════════════════════════════════════════════════════════════════════

    private fun determineLevel(distanceMeters: Double, speedMps: Double): AnnouncementLevel {
        // Use time-based thresholds at high speed
        if (config.useTimeBasedThresholds && speedMps > config.highSpeedThresholdMps) {
            val timeToManeuver = distanceMeters / speedMps
            
            return when {
                timeToManeuver < 3.0 -> AnnouncementLevel.NOW
                timeToManeuver < config.soonTimeSeconds -> AnnouncementLevel.SOON
                timeToManeuver < config.prepareTimeSeconds -> AnnouncementLevel.PREPARE
                timeToManeuver < config.farTimeSeconds -> AnnouncementLevel.FAR
                else -> AnnouncementLevel.CONTINUE
            }
        }
        
        // Use distance-based thresholds
        return when {
            distanceMeters < config.nowDistanceMeters -> AnnouncementLevel.NOW
            distanceMeters < config.soonDistanceMeters -> AnnouncementLevel.SOON
            distanceMeters < config.prepareDistanceMeters -> AnnouncementLevel.PREPARE
            distanceMeters < config.farDistanceMeters -> AnnouncementLevel.FAR
            else -> AnnouncementLevel.CONTINUE
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Instruction Generation
    // ═══════════════════════════════════════════════════════════════════════

    private fun generateInstruction(
        maneuver: Maneuver,
        level: AnnouncementLevel,
        distanceMeters: Double
    ): TurnInstruction {
        val voicePrompt = generateVoicePrompt(maneuver, level, distanceMeters)
        val visualText = generateVisualText(maneuver, level)
        val distanceText = formatDistance(distanceMeters)
        
        return TurnInstruction(
            maneuver = maneuver,
            level = level,
            distanceMeters = distanceMeters,
            voicePrompt = voicePrompt,
            visualText = visualText,
            distanceText = distanceText,
            streetName = maneuver.roadName
        )
    }

    private fun generateVoicePrompt(
        maneuver: Maneuver,
        level: AnnouncementLevel,
        distanceMeters: Double
    ): String {
        val action = getVoiceAction(maneuver.type)
        val street = maneuver.roadName?.let { " onto $it" } ?: ""
        val distance = formatDistanceForVoice(distanceMeters)
        
        return when (level) {
            AnnouncementLevel.FAR -> "In $distance, $action$street"
            AnnouncementLevel.PREPARE -> "In $distance, $action$street"
            AnnouncementLevel.SOON -> "$action$street in $distance"
            AnnouncementLevel.NOW -> "$action$street now"
            AnnouncementLevel.CONTINUE -> "Continue straight"
        }
    }

    private fun generateVisualText(maneuver: Maneuver, level: AnnouncementLevel): String {
        return when (level) {
            AnnouncementLevel.NOW -> getShortAction(maneuver.type)
            else -> maneuver.shortInstruction
        }
    }

    private fun getVoiceAction(type: ManeuverType): String {
        return when (type) {
            ManeuverType.DEPART -> "Head"
            ManeuverType.ARRIVE -> "You have arrived at your destination"
            ManeuverType.STRAIGHT -> "Continue straight"
            ManeuverType.SLIGHT_RIGHT -> "Keep right"
            ManeuverType.TURN_RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Make a sharp right"
            ManeuverType.UTURN_RIGHT -> "Make a U-turn"
            ManeuverType.SLIGHT_LEFT -> "Keep left"
            ManeuverType.TURN_LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Make a sharp left"
            ManeuverType.UTURN_LEFT -> "Make a U-turn"
            ManeuverType.MERGE -> "Merge"
            ManeuverType.FORK -> "Keep"
            ManeuverType.RAMP -> "Take the ramp"
            ManeuverType.ROUNDABOUT_ENTER -> "Enter the roundabout"
            ManeuverType.ROUNDABOUT_EXIT -> "Exit the roundabout"
            ManeuverType.FERRY -> "Take the ferry"
            ManeuverType.UNKNOWN -> "Continue"
        }
    }

    private fun getShortAction(type: ManeuverType): String {
        return when (type) {
            ManeuverType.DEPART -> "Go"
            ManeuverType.ARRIVE -> "Arrive"
            ManeuverType.STRAIGHT -> "Straight"
            ManeuverType.SLIGHT_RIGHT -> "Slight right"
            ManeuverType.TURN_RIGHT -> "Right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            ManeuverType.UTURN_RIGHT -> "U-turn"
            ManeuverType.SLIGHT_LEFT -> "Slight left"
            ManeuverType.TURN_LEFT -> "Left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.UTURN_LEFT -> "U-turn"
            ManeuverType.MERGE -> "Merge"
            ManeuverType.FORK -> "Fork"
            ManeuverType.RAMP -> "Ramp"
            ManeuverType.ROUNDABOUT_ENTER -> "Roundabout"
            ManeuverType.ROUNDABOUT_EXIT -> "Exit"
            ManeuverType.FERRY -> "Ferry"
            ManeuverType.UNKNOWN -> "Continue"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Distance Formatting
    // ═══════════════════════════════════════════════════════════════════════

    private fun formatDistance(meters: Double): String {
        return if (config.useMetricUnits) {
            formatMetricDistance(meters)
        } else {
            formatImperialDistance(meters)
        }
    }

    private fun formatDistanceForVoice(meters: Double): String {
        return if (config.useMetricUnits) {
            formatMetricDistanceForVoice(meters)
        } else {
            formatImperialDistanceForVoice(meters)
        }
    }

    private fun formatMetricDistance(meters: Double): String {
        return when {
            meters < 100 -> "${meters.toInt()} m"
            meters < 1000 -> "${(meters / 10).toInt() * 10} m"
            meters < 10000 -> String.format(config.locale, "%.1f km", meters / 1000)
            else -> "${(meters / 1000).toInt()} km"
        }
    }

    private fun formatMetricDistanceForVoice(meters: Double): String {
        return when {
            meters < 100 -> "${meters.toInt()} meters"
            meters < 200 -> "100 meters"
            meters < 300 -> "200 meters"
            meters < 400 -> "300 meters"
            meters < 600 -> "500 meters"
            meters < 900 -> "800 meters"
            meters < 1500 -> "1 kilometer"
            meters < 2500 -> "2 kilometers"
            meters < 4000 -> "3 kilometers"
            else -> "${(meters / 1000).toInt()} kilometers"
        }
    }

    private fun formatImperialDistance(meters: Double): String {
        val feet = meters * 3.28084
        val miles = meters / 1609.34
        
        return when {
            feet < 500 -> "${(feet / 10).toInt() * 10} ft"
            miles < 0.1 -> "${(feet / 50).toInt() * 50} ft"
            miles < 10 -> String.format(config.locale, "%.1f mi", miles)
            else -> "${miles.toInt()} mi"
        }
    }

    private fun formatImperialDistanceForVoice(meters: Double): String {
        val feet = meters * 3.28084
        val miles = meters / 1609.34
        
        return when {
            feet < 200 -> "${feet.toInt()} feet"
            feet < 350 -> "quarter mile"
            feet < 700 -> "500 feet"
            feet < 1200 -> "1000 feet"
            miles < 0.4 -> "quarter mile"
            miles < 0.7 -> "half mile"
            miles < 1.2 -> "1 mile"
            miles < 2.5 -> "2 miles"
            else -> "${miles.toInt()} miles"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Arrival Instructions
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generate arrival instruction.
     */
    fun getArrivalInstruction(destinationName: String? = null): TurnInstruction {
        val name = destinationName ?: "your destination"
        
        return TurnInstruction(
            maneuver = Maneuver(
                type = ManeuverType.ARRIVE,
                location = RoutePoint.EMPTY,
                instruction = "You have arrived at $name",
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                polylineStartIndex = 0
            ),
            level = AnnouncementLevel.NOW,
            distanceMeters = 0.0,
            voicePrompt = "You have arrived at $name",
            visualText = "Arrived",
            distanceText = "",
            streetName = destinationName
        )
    }

    /**
     * Generate "approaching destination" instruction.
     */
    fun getApproachingDestinationInstruction(
        distanceMeters: Double,
        side: String? = null  // "left" or "right"
    ): TurnInstruction {
        val sideText = side?.let { " on the $it" } ?: ""
        val distance = formatDistanceForVoice(distanceMeters)
        
        return TurnInstruction(
            maneuver = Maneuver(
                type = ManeuverType.ARRIVE,
                location = RoutePoint.EMPTY,
                instruction = "Your destination is $distance ahead$sideText",
                distanceMeters = distanceMeters,
                durationSeconds = 0.0,
                polylineStartIndex = 0
            ),
            level = AnnouncementLevel.PREPARE,
            distanceMeters = distanceMeters,
            voicePrompt = "Your destination is $distance ahead$sideText",
            visualText = "Destination ahead",
            distanceText = formatDistance(distanceMeters),
            streetName = null
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Continue Instructions
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generate "continue" instruction for long stretches.
     */
    fun getContinueInstruction(
        distanceMeters: Double,
        streetName: String? = null
    ): TurnInstruction {
        val street = streetName?.let { " on $it" } ?: ""
        val distance = formatDistanceForVoice(distanceMeters)
        
        return TurnInstruction(
            maneuver = Maneuver(
                type = ManeuverType.STRAIGHT,
                location = RoutePoint.EMPTY,
                instruction = "Continue$street for $distance",
                distanceMeters = distanceMeters,
                durationSeconds = 0.0,
                polylineStartIndex = 0
            ),
            level = AnnouncementLevel.CONTINUE,
            distanceMeters = distanceMeters,
            voicePrompt = "Continue$street for $distance",
            visualText = "Continue",
            distanceText = formatDistance(distanceMeters),
            streetName = streetName
        )
    }
}
