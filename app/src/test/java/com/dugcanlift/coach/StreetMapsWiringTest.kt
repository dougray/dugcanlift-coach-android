package com.dugcanlift.coach

import com.dugcanlift.coach.ui.MapLicences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Street maps are wired the way the design says, read from source because none of it can run in a
 * unit test (a MapView needs a GL context): the kit's `liftmap` is a dependency, the manifest holds
 * INTERNET and removes the two network-state permissions MapLibre merges in, the street file is
 * kept out of every backup, the guard is installed at launch, and the client's route card and the
 * Connect screen use the kit's composables.
 */
class StreetMapsWiringTest {
    private val app = NoAppConnection.mainSources().parentFile.parentFile
    private fun text(path: String) = File(app, path).readText()
    private val manifest get() = text("src/main/AndroidManifest.xml")
    private val kotlin get() = NoAppConnection.mainSources().walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

    @Test fun `liftmap is pinned exactly, per module`() {
        val gradle = text("build.gradle.kts")
        assertTrue(gradle.contains("com.github.dougray.dugcanlift-kit-android:liftcore:1.11.0"))
        assertTrue(gradle.contains("com.github.dougray.dugcanlift-kit-android:liftmap:1.11.0"))
        // The repo-level coordinate is an aggregate of both modules: never used.
        assertFalse(gradle.contains("\"com.github.dougray:dugcanlift-kit-android"))
    }

    @Test fun `the manifest declares INTERNET and removes the network-state permissions`() {
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.INTERNET\" />"))
        // MapLibre also merges in two location permissions; Coach never asks where the coach is.
        for (name in listOf("ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")) {
            assertTrue(
                name,
                Regex("<uses-permission[^>]*android\\.permission\\.$name\"[^>]*tools:node=\"remove\"").containsMatchIn(manifest),
            )
        }
        assertTrue(manifest.contains("xmlns:tools="))
    }

    @Test fun `the street file is excluded from every backup and transfer`() {
        for (file in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = text("src/main/res/xml/$file")
            val excludes = Regex("<exclude domain=\"file\" path=\"liftmap/\"\\s*/>").findAll(xml).count()
            val wanted = if (file == "backup_rules.xml") 1 else 2 // cloud-backup and device-transfer
            assertEquals(file, wanted, excludes)
        }
    }

    @Test fun `the guard is installed at launch`() {
        assertTrue(text("src/main/java/com/dugcanlift/coach/CoachApp.kt").contains("MapNetworkGuard.install(this)"))
    }

    @Test fun `the route card draws a RouteMap with the canvas as its fallback`() {
        val screen = text("src/main/java/com/dugcanlift/coach/ui/ClientScreen.kt")
        assertTrue(screen.contains("RouteMap("))
        assertTrue(screen.contains("fallback = { m -> RouteCanvas(points, m, aspectRatio) }"))
        assertTrue(screen.contains("StreetsSheet("))
        assertTrue(screen.contains("This asks for the area around your client's route."))
    }

    @Test fun `the Connect screen carries the Maps card with no location offer`() {
        val connect = text("src/main/java/com/dugcanlift/coach/ui/ConnectScreen.kt")
        assertTrue(connect.contains("MapsCard(streetStore, aroundHere = null, lastRunStart = null"))
    }

    @Test fun `no location permission and no WebView come with maps`() {
        assertFalse(Regex("<uses-permission(?![^>]*tools:node=\"remove\")[^>]*LOCATION").containsMatchIn(manifest))
        assertFalse(kotlin.contains("WebView"))
    }

    @Test fun `the licences for what maps draw with are listed`() {
        val licences = MapLicences.text
        assertTrue(licences.contains("MapLibre Native"))
        assertTrue(licences.contains("BSD-2-Clause"))
        assertTrue(licences.contains("Redistribution and use in source and binary forms"))
        assertTrue(licences.contains("Noto Sans"))
        assertTrue(licences.contains("SIL Open Font License"))
        assertTrue(licences.contains("OpenStreetMap contributors"))
        assertTrue(licences.contains("OpenFreeMap"))
        assertTrue(kotlin.contains("MapLicences.text"))
    }
}
