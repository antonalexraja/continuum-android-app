# Route Guidance System

## Overview

The Route Guidance system provides modular, selectable turn-by-turn navigation for the Navigator app. It supports multiple routing providers (Google, OSRM, Offline) with automatic fallback and produces provider-agnostic route data.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         RouteGuidanceManager                                 │
│  • Coordinates routing, position tracking, and instruction generation       │
│  • Provides unified GuidanceState via LiveData                              │
│  • Handles auto-rerouting when off-route                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                │                      │                      │
                ▼                      ▼                      ▼
┌───────────────────────┐  ┌─────────────────────┐  ┌─────────────────────────┐
│    RoutingService     │  │ RoutePositionTracker│  │  TurnInstructionEngine  │
│  • Mode selection     │  │ • Soft map matching │  │  • Voice prompts        │
│  • Caching            │  │ • Distance tracking │  │  • Distance thresholds  │
│  • LiveData state     │  │ • Off-route detect  │  │  • Metric/Imperial      │
└───────────────────────┘  └─────────────────────┘  └─────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        RoutingEngine (Interface)                             │
├─────────────────┬─────────────────┬─────────────────┬───────────────────────┤
│ GoogleRouting   │  OsrmRouting    │ OfflineRouting  │   HybridRouting       │
│ Engine          │  Engine         │ Engine (Stub)   │   Engine              │
│                 │                 │                 │                       │
│ • Paid API      │ • Free          │ • Local OSM     │ • Auto fallback       │
│ • Traffic-aware │ • No traffic    │ • Not impl.     │ • Google→OSRM→Offline │
│ • Polyline5     │ • Polyline6     │                 │ • Network-aware       │
└─────────────────┴─────────────────┴─────────────────┴───────────────────────┘
```

## Design Principles

1. **NavigationOutput Untouched** - Routing is purely observational; never modifies the localization filter
2. **Soft Map Matching** - Projects position onto route for display, but doesn't snap upstream position
3. **Provider-Agnostic** - All engines produce the common `Route` model
4. **Modular** - Each component works independently
5. **Hybrid Fallback** - Graceful degradation when network unavailable

## Files

### Core Data Models (`Route.kt`)

| Class | Description |
|-------|-------------|
| `RoutePoint` | Lat/lon coordinate with `distanceTo()` and `bearingTo()` |
| `Maneuver` | Turn instruction with type, location, distance, duration |
| `ManeuverType` | Enum: DEPART, ARRIVE, TURN_RIGHT, TURN_LEFT, etc. |
| `RouteLeg` | Segment between waypoints with maneuvers |
| `Route` | Complete route with polyline, legs, distance, duration |
| `RouteRequest` | Request parameters (origin, destination, mode, avoid) |
| `RouteResult` | Sealed class: Success or Error |

### Routing Engines

| File | Description |
|------|-------------|
| `RoutingEngine.kt` | Interface + factory for all engines |
| `GoogleRoutingEngine.kt` | Google Directions API (polyline5 encoding) |
| `OsrmRoutingEngine.kt` | OSRM API v5 (polyline6 encoding) |
| `OfflineRoutingEngine.kt` | Stub for future offline routing |
| `HybridRoutingEngine.kt` | Automatic fallback between providers |

### Services & State

| File | Description |
|------|-------------|
| `RoutingService.kt` | High-level routing with mode selection, caching |
| `RoutingMode.kt` | Enum: ONLINE_GOOGLE, ONLINE_OSRM, OFFLINE_OSM, HYBRID_FALLBACK |
| `RoutingModeSelector.kt` | UI dialog for provider selection |

### Position Tracking

| File | Description |
|------|-------------|
| `RoutePositionTracker.kt` | Map matching, distance tracking, off-route detection |
| `RoutePositionState` | Current position along route with all metrics |

### Turn Instructions

| File | Description |
|------|-------------|
| `TurnInstructionEngine.kt` | Voice/visual instruction generation |
| `TurnInstruction` | Ready-to-use instruction with voice prompt |
| `AnnouncementLevel` | FAR, PREPARE, SOON, NOW, CONTINUE |
| `ManeuverIcons.kt` | Maps ManeuverType to drawable resources |

### UI Components

| File | Description |
|------|-------------|
| `NavigationInstructionView.kt` | Turn-by-turn display overlay |
| `RouteRenderer.kt` | Polyline rendering on Google Maps / OSMDroid |
| `RouteNavigationDemoActivity.kt` | Demo activity showing integration |

### Utilities

| File | Description |
|------|-------------|
| `RouteComparison.kt` | Route normalization, comparison, metrics |

## Usage

### Basic Route Calculation

```kotlin
val routingService = RoutingService(context)
routingService.setMode(RoutingMode.ONLINE_OSRM)

lifecycleScope.launch {
    val result = routingService.calculateRoute(
        origin = RoutePoint(37.7749, -122.4194),
        destination = RoutePoint(37.3382, -121.8863)
    )
    
    when (result) {
        is RouteResult.Success -> {
            val route = result.primaryRoute
            println("Distance: ${route.distanceText}")
            println("Duration: ${route.durationText}")
        }
        is RouteResult.Error -> {
            println("Error: ${result.message}")
        }
    }
}
```

### Full Navigation with Guidance

```kotlin
// Initialize
val guidanceManager = RouteGuidanceManager(context)
val instructionEngine = TurnInstructionEngine()

// Observe state
guidanceManager.guidanceState.observe(this) { state ->
    when (state) {
        is GuidanceState.Navigating -> {
            // Update UI
            instructionView.updateFromGuidanceState(state)
            
            // Get voice instruction
            state.nextManeuver?.let { maneuver ->
                val instruction = instructionEngine.getInstruction(
                    maneuver = maneuver,
                    maneuverIndex = state.positionState.currentManeuverIndex,
                    distanceMeters = state.distanceToNextManeuver,
                    speedMps = currentSpeed
                )
                instruction?.let { tts.speak(it.voicePrompt) }
            }
        }
        is GuidanceState.Arrived -> {
            showArrivalDialog()
        }
        // ... handle other states
    }
}

// Start navigation
guidanceManager.startNavigation(origin, destination)

// Feed position updates (from NavigationEngine)
navigationEngine.output.observe(this) { navOutput ->
    guidanceManager.updatePosition(
        latitude = navOutput.latitudeDeg,
        longitude = navOutput.longitudeDeg,
        speedMps = navOutput.speed
    )
}
```

### Render Route on Map

```kotlin
val renderer = RouteRenderer()
val route = guidanceManager.getCurrentRoute()

// For Google Maps
val googleMap = googleMapController.getGoogleMap()
renderer.renderOnGoogleMaps(googleMap, route)

// For OSMDroid
val mapView = offlineMapController.getMapView()
renderer.renderOnOsmDroid(mapView, route)

// Update traveled portion
renderer.updateTraveledPortion(positionState.progress)
```

## Routing Providers

### Google Directions API

- **Pros**: Traffic-aware, high-quality maneuvers, alternatives
- **Cons**: Paid, requires API key
- **Polyline**: Encoded with precision 5 (1e-5)

```kotlin
routingService.setMode(
    mode = RoutingMode.ONLINE_GOOGLE,
    apiKey = "YOUR_API_KEY"
)
```

### OSRM (Open Source Routing Machine)

- **Pros**: Free, self-hostable, fast
- **Cons**: No traffic data
- **Polyline**: Encoded with precision 6 (1e-6)

```kotlin
routingService.setMode(
    mode = RoutingMode.ONLINE_OSRM,
    osrmUrl = "https://router.project-osrm.org"  // or your server
)
```

### Hybrid (Automatic Fallback)

Falls back in order: Google → OSRM → Offline

```kotlin
routingService.setMode(RoutingMode.HYBRID_FALLBACK)
```

## Announcement Thresholds

| Level | Distance | Time (high speed) | Voice Example |
|-------|----------|-------------------|---------------|
| FAR | 500m | 30s | "In 500 meters, turn right onto Main Street" |
| PREPARE | 200m | 15s | "In 200 meters, turn right onto Main Street" |
| SOON | 100m | 7s | "Turn right onto Main Street in 100 meters" |
| NOW | 30m | 3s | "Turn right now" |

## Off-Route Detection

- **Threshold**: 50m cross-track error
- **Hysteresis**: Requires 3 consecutive readings to change state
- **Auto-reroute**: After 3 seconds off-route (configurable)

## Configuration

### TurnInstructionConfig

```kotlin
TurnInstructionConfig(
    useMetricUnits = true,
    farDistanceMeters = 500.0,
    prepareDistanceMeters = 200.0,
    soonDistanceMeters = 100.0,
    nowDistanceMeters = 30.0,
    useTimeBasedThresholds = true,  // At high speed
    highSpeedThresholdMps = 16.7    // ~60 km/h
)
```

### RouteTrackerConfig

```kotlin
RouteTrackerConfig(
    offRouteThresholdMeters = 50.0,
    reRouteThresholdMeters = 100.0,
    segmentSearchWindow = 20  // Optimization: search nearby segments
)
```

### GuidanceConfig

```kotlin
GuidanceConfig(
    autoReroute = true,
    rerouteDelayMs = 3000,
    arrivalThresholdMeters = 30.0
)
```

## Icons

Navigation icons are in `res/drawable/`:

| Icon | ManeuverType |
|------|--------------|
| `ic_depart.xml` | DEPART |
| `ic_arrive.xml` | ARRIVE |
| `ic_arrow_forward.xml` | STRAIGHT |
| `ic_turn_right.xml` | TURN_RIGHT |
| `ic_turn_left.xml` | TURN_LEFT |
| `ic_turn_slight_right.xml` | SLIGHT_RIGHT |
| `ic_turn_slight_left.xml` | SLIGHT_LEFT |
| `ic_turn_sharp_right.xml` | SHARP_RIGHT |
| `ic_turn_sharp_left.xml` | SHARP_LEFT |
| `ic_uturn.xml` | UTURN_RIGHT, UTURN_LEFT |
| `ic_merge.xml` | MERGE |
| `ic_fork.xml` | FORK |
| `ic_ramp.xml` | RAMP |
| `ic_off_ramp.xml` | OFF_RAMP |
| `ic_roundabout.xml` | ROUNDABOUT_ENTER, ROUNDABOUT_EXIT |
| `ic_ferry.xml` | FERRY |

## Future Work

1. **Offline Routing** - Implement OfflineRoutingEngine with GraphHopper or similar
2. **Lane Guidance** - Add lane information to maneuvers
3. **Voice Synthesis** - Integrate with Android TTS
4. **Route Simulation** - Playback routes for testing
5. **Traffic Updates** - Real-time traffic integration
6. **ETA Learning** - Personalized ETA based on driving patterns

## Phase History

| Phase | Description | Files |
|-------|-------------|-------|
| RG-1 | Core models, Google engine, renderer | Route.kt, GoogleRoutingEngine.kt, RouteRenderer.kt |
| RG-2 | OSRM engine, mode selection | OsrmRoutingEngine.kt, RoutingService.kt, RoutingModeSelector.kt |
| RG-3 | Position tracking, map matching | RoutePositionTracker.kt, RouteGuidanceManager.kt |
| RG-4 | Turn instructions, icons | TurnInstructionEngine.kt, ManeuverIcons.kt, drawable icons |
| RG-5 | UI integration, stubs, hybrid | OfflineRoutingEngine.kt, HybridRoutingEngine.kt, NavigationInstructionView.kt |
