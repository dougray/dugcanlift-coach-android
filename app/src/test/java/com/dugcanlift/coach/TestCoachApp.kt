package com.dugcanlift.coach

/**
 * The application Robolectric runs the JVM unit tests with (`robolectric.properties`): [CoachApp]
 * without the street-map guard, because MapLibre's native library does not load on a JVM. The
 * guard's own behavior is the kit's to test; that it is installed at launch is pinned from source
 * by `StreetMapsWiringTest`.
 */
class TestCoachApp : CoachApp() {
    override fun installMapGuard() = Unit
}
