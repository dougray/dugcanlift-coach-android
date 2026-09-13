package com.dugcanlift.coach.data

/**
 * The paste sheet accepts either a full `https://www.dugcanlift.com/coach/#<fragment>` URL or a
 * bare fragment copied on its own. If the text contains `#`, only what follows the LAST `#` is
 * kept (a share URL never legitimately contains a second `#`, but a bare fragment might have been
 * pasted twice back-to-back). When a `#` was found, the result is further cut at the first
 * whitespace character -- a share-sheet caption can trail the link (e.g. "...#XYZ thanks!"), and
 * the encoded fragment itself never contains whitespace -- otherwise (no `#` at all, i.e. a bare
 * fragment) the whole text is kept as-is. Either way the result is trimmed of surrounding
 * whitespace.
 */
fun fragmentFrom(text: String): String {
    val lastHash = text.lastIndexOf('#')
    if (lastHash < 0) return text.trim()
    val raw = text.substring(lastHash + 1).trim()
    val whitespace = raw.indexOfFirst { it.isWhitespace() }
    return if (whitespace >= 0) raw.substring(0, whitespace) else raw
}

private const val ACTION_SEND = "android.intent.action.SEND"

/**
 * Reduces an inbound intent's already-extracted pieces to a fragment ready for
 * [ShareLinkImporter.import], or null when the intent carries nothing importable. Kept as a plain
 * Kotlin function (no `android.content.Intent` parameter) so it stays testable without Robolectric,
 * matching [fragmentFrom] -- callers pass in `intent.data?.fragment`, `intent.action`, and
 * `intent.getStringExtra(Intent.EXTRA_TEXT)`.
 *
 * [dataFragment] -- `Uri.fragment` from a VIEW/App-Links intent -- already isolates the fragment,
 * so it wins outright when present (the OS delivered the full URL to the activity; the fragment
 * never touched the server). Otherwise, an `ACTION_SEND` share's text is reduced with
 * [fragmentFrom], exactly like a paste. Anything else -- a different action, or a null/blank
 * `EXTRA_TEXT` -- yields null so the caller knows not to attempt an import.
 */
fun fragmentToImport(dataFragment: String?, action: String?, extraText: String?): String? {
    if (dataFragment != null) return dataFragment
    if (action == ACTION_SEND && !extraText.isNullOrEmpty()) return fragmentFrom(extraText)
    return null
}
