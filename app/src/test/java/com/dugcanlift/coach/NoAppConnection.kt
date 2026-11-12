package com.dugcanlift.coach

import java.io.File

/**
 * The form the old "Coach asks for no INTERNET" guarantee takes now that it does: **no app source
 * opens a connection.** The only network code in the installed APK is the kit's `MapDownload`
 * (dugcanlift-kit-android, `liftmap`), which runs only when a coach starts a street-map download;
 * every other line here is a library call that cannot reach a server.
 *
 * Scans every file under `app/src/main` (Kotlin, Java, manifest, XML, JSON, assets) for the
 * spellings a connection takes. A new request has to be written as a deliberate change to this
 * list and to the privacy page, not slip in beside a map.
 */
object NoAppConnection {
    val forbidden = listOf(
        "HttpURLConnection",
        "HttpsURLConnection",
        "openConnection",
        "openStream",
        "OkHttp",
        "java.net.URL(",
        "URL(\"",
        "WebView",
        "openfreemap",
        "Socket(",
    )

    private val textExtensions = setOf("kt", "java", "xml", "json", "txt", "kts", "md", "html", "js")

    /** `app/src/main` whether the test runs from `app/` or from the repo root. */
    fun mainSources(): File =
        listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    /** Every `"file: spelling"` that should not be there. */
    fun offenders(root: File = mainSources()): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension in textExtensions }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter { text.contains(it) }.map { "${file.relativeTo(root)}: $it" }
            }
            .toList()
}
