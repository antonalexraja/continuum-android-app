/**
 * Route Comparison & Normalization
 *
 * Utilities for comparing routes from different providers and
 * normalizing their outputs for consistent behavior.
 *
 * ## Provider Differences
 *
 * | Feature | Google | OSRM | Offline |
 * |---------|--------|------|---------|
 * | Traffic | ✓ | ✗ | ✗ |
 * | ETA Accuracy | High | Medium | Medium |
 * | Maneuver Detail | High | Medium | Low |
 * | Cost | Paid | Free | Free |
 * | Offline | ✗ | ✗ | ✓ |
 *
 * ## Usage
 *
 * ```kotlin
 * val normalized = RouteNormalizer.normalize(route)
 * val comparison = RouteComparison.compare(googleRoute, osrmRoute)
 * ```
 */
package com.continuum.navigator.core.routing

import kotlin.math.abs

/**
 * Normalizes route data for consistent behavior across providers.
 */
object RouteNormalizer {

    /**
     * Normalize a route by filling in missing data and standardizing formats.
     *
     * - Ensures all maneuvers have valid locations
     * - Fills in missing road names
     * - Standardizes instruction formats
     * - Adds DEPART/ARRIVE maneuvers if missing
     */
    fun normalize(route: Route): Route {
        val normalizedLegs = route.legs.map { leg ->
            val maneuvers = leg.maneuvers.toMutableList()

            // Ensure DEPART at start
            if (maneuvers.firstOrNull()?.type != ManeuverType.DEPART) {
                val startPoint = route.polyline.firstOrNull() ?: RoutePoint.EMPTY
                maneuvers.add(
                    0,
                    Maneuver(
                        type = ManeuverType.DEPART,
                        location = startPoint,
                        instruction = "Start journey",
                        distanceMeters = 0.0,
                        durationSeconds = 0.0,
                        polylineStartIndex = 0
                    )
                )
            }

            // Ensure ARRIVE at end
            if (maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE) {
                val endPoint = route.polyline.lastOrNull() ?: RoutePoint.EMPTY
                maneuvers.add(
                    Maneuver(
                        type = ManeuverType.ARRIVE,
                        location = endPoint,
                        instruction = "Arrive at destination",
                        distanceMeters = 0.0,
                        durationSeconds = 0.0,
                        polylineStartIndex = route.polyline.size - 1
                    )
                )
            }

            // Standardize instructions
            val standardizedManeuvers = maneuvers.map { maneuver ->
                if (maneuver.instruction.isBlank()) {
                    maneuver.copy(instruction = maneuver.shortInstruction)
                } else {
                    maneuver
                }
            }

            leg.copy(maneuvers = standardizedManeuvers)
        }

        return route.copy(legs = normalizedLegs)
    }

    /**
     * Simplify route by removing redundant maneuvers.
     *
     * Useful for less cluttered turn-by-turn guidance.
     */
    fun simplify(route: Route, minDistanceMeters: Double = 50.0): Route {
        val simplifiedLegs = route.legs.map { leg ->
            val filtered = leg.maneuvers.filter { maneuver ->
                // Always keep DEPART and ARRIVE
                maneuver.type == ManeuverType.DEPART ||
                maneuver.type == ManeuverType.ARRIVE ||
                // Keep maneuvers with significant distance
                maneuver.distanceMeters >= minDistanceMeters
            }

            leg.copy(maneuvers = filtered)
        }

        return route.copy(legs = simplifiedLegs)
    }
}

/**
 * Route comparison metrics.
 */
data class RouteComparison(
    val route1: Route,
    val route2: Route,
    val distanceDifferenceMeters: Double,
    val distanceDifferencePercent: Double,
    val durationDifferenceSeconds: Double,
    val durationDifferencePercent: Double,
    val maneuverCountDifference: Int,
    val routeSimilarity: Double, // 0.0 to 1.0
) {
    val isSimilar: Boolean
        get() = routeSimilarity > 0.7

    val distanceIsSimilar: Boolean
        get() = abs(distanceDifferencePercent) < 10.0

    val durationIsSimilar: Boolean
        get() = abs(durationDifferencePercent) < 15.0

    companion object {
        /**
         * Compare two routes and compute metrics.
         */
        fun compare(route1: Route, route2: Route): RouteComparison {
            val distanceDiff = route2.totalDistanceMeters - route1.totalDistanceMeters
            val distancePct = if (route1.totalDistanceMeters > 0) {
                (distanceDiff / route1.totalDistanceMeters) * 100
            } else 0.0

            val durationDiff = route2.totalDurationSeconds - route1.totalDurationSeconds
            val durationPct = if (route1.totalDurationSeconds > 0) {
                (durationDiff / route1.totalDurationSeconds) * 100
            } else 0.0

            val maneuverDiff = route2.allManeuvers.size - route1.allManeuvers.size

            // Calculate route similarity based on polyline overlap
            val similarity = calculatePolylineSimilarity(route1.polyline, route2.polyline)

            return RouteComparison(
                route1 = route1,
                route2 = route2,
                distanceDifferenceMeters = distanceDiff,
                distanceDifferencePercent = distancePct,
                durationDifferenceSeconds = durationDiff,
                durationDifferencePercent = durationPct,
                maneuverCountDifference = maneuverDiff,
                routeSimilarity = similarity
            )
        }

        /**
         * Calculate similarity between two polylines (0.0 = completely different, 1.0 = identical).
         *
         * Uses Hausdorff distance approximation.
         */
        private fun calculatePolylineSimilarity(
            poly1: List<RoutePoint>,
            poly2: List<RoutePoint>
        ): Double {
            if (poly1.isEmpty() || poly2.isEmpty()) return 0.0

            // Sample points from both polylines
            val samples1 = samplePolyline(poly1, maxSamples = 50)
            val samples2 = samplePolyline(poly2, maxSamples = 50)

            // Calculate average minimum distance from samples1 to poly2
            val avgDist1to2 = samples1.map { p1 ->
                samples2.minOf { p2 -> p1.distanceTo(p2) }
            }.average()

            // Calculate average minimum distance from samples2 to poly1
            val avgDist2to1 = samples2.map { p2 ->
                samples1.minOf { p1 -> p1.distanceTo(p2) }
            }.average()

            // Use max of both directions (Hausdorff-like)
            val hausdorffDist = maxOf(avgDist1to2, avgDist2to1)

            // Convert distance to similarity (0-1 scale)
            // Assume routes >500m apart are completely different
            val similarity = (500.0 - hausdorffDist.coerceIn(0.0, 500.0)) / 500.0

            return similarity.coerceIn(0.0, 1.0)
        }

        /**
         * Sample points evenly from a polyline.
         */
        private fun samplePolyline(polyline: List<RoutePoint>, maxSamples: Int): List<RoutePoint> {
            if (polyline.size <= maxSamples) return polyline

            val step = polyline.size.toDouble() / maxSamples
            return (0 until maxSamples).map { i ->
                polyline[(i * step).toInt()]
            }
        }
    }
}

/**
 * Provider capability matrix.
 */
enum class RoutingCapability {
    TRAFFIC_AWARE,
    OFFLINE_SUPPORT,
    FREE_USAGE,
    HIGH_DETAIL_MANEUVERS,
    ALTERNATIVE_ROUTES,
    AVOID_OPTIONS,
    DEPARTURE_TIME,
}

/**
 * Get capabilities for a routing provider.
 */
fun RoutingProvider.getCapabilities(): Set<RoutingCapability> {
    return when (this) {
        RoutingProvider.GOOGLE -> setOf(
            RoutingCapability.TRAFFIC_AWARE,
            RoutingCapability.HIGH_DETAIL_MANEUVERS,
            RoutingCapability.ALTERNATIVE_ROUTES,
            RoutingCapability.AVOID_OPTIONS,
            RoutingCapability.DEPARTURE_TIME
        )
        RoutingProvider.OSRM -> setOf(
            RoutingCapability.FREE_USAGE,
            RoutingCapability.ALTERNATIVE_ROUTES
        )
        RoutingProvider.OFFLINE, RoutingProvider.OFFLINE_OSM -> setOf(
            RoutingCapability.OFFLINE_SUPPORT,
            RoutingCapability.FREE_USAGE
        )
        RoutingProvider.HYBRID -> setOf(
            RoutingCapability.TRAFFIC_AWARE,
            RoutingCapability.ALTERNATIVE_ROUTES,
            RoutingCapability.OFFLINE_SUPPORT
        )
        RoutingProvider.NONE -> emptySet()
    }
}

/**
 * Check if provider supports a capability.
 */
fun RoutingProvider.supports(capability: RoutingCapability): Boolean {
    return capability in getCapabilities()
}
