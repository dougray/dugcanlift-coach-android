package com.dugcanlift.coach.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The 873-exercise reference library from free-exercise-db — the same
 * `exercises.json` the browser build of Coach ships, the same file LIFT
 * Android bundles, and the same 873 rows behind Coach for iPhone's
 * `exercises.db`.
 *
 * Why a coach needs it at all: a routine is a list of exercise names, and a
 * name a coach types by hand is a name that has to match what the client's app
 * calls the same movement. "Bench Press" written here and "Barbell Bench Press
 * - Medium Grip" logged there are one movement to a person and two unrelated
 * trend lines to everything downstream. Picking from the shared file is how
 * that stays true without anyone having to remember it.
 *
 * This is deliberately a near-copy of LIFT Android's `ExerciseLibrary.kt`
 * rather than a shared dependency — minus the history ranking, which needs a
 * training log this app doesn't keep for its user. The parser and the
 * title-casing are the parts where a divergence would cause the very bug the
 * library exists to prevent, so both apps pin them to one browser-generated
 * fixture (`src/test/fixtures/browser-parity.json`). Drift fails a build.
 *
 * Copy the asset from `dugcanlift-site/coach/` rather than editing it here.
 */
data class LibraryExercise(
    val name: String,
    /** Raw, as the file spells it: "abdominals", lowercase. */
    val muscle: String,
    /** Raw, as the file spells it: "body only", lowercase. Filters match this. */
    val equipment: String,
    val category: String,
    val level: String,
) {
    /**
     * The equipment as it gets **written into a routine** — "Body Only", not
     * "body only". The browser's spelling, which LIFT Android also stores, so
     * a prescription and a logged set agree on what the lift was.
     */
    val storedEquipment: String get() = titleCaseAscii(equipment)

    /** What the row says under the name: "chest, barbell". */
    val detailLabel: String
        get() = if (equipment.isBlank()) muscle else "$muscle, $equipment"

    /**
     * The routine editor's own line grammar: `name | equipment`, with the
     * scheme left off because the library knows the movement, not the
     * prescription. A line with no scheme is an exercise with no prescribed
     * sets, which [parseExercises] already treats as legitimate.
     */
    val routineLine: String
        get() = if (storedEquipment.isBlank()) name else "$name | $storedEquipment"
}

/** The equipment chips over the results, in the browser's order. */
val EQUIPMENT_FILTERS = listOf(
    "barbell", "dumbbell", "machine", "cable", "body only", "kettlebells"
)

/** How many rows the picker shows at once. The browser's cap, kept. */
const val EXERCISE_SEARCH_LIMIT = 40

/** Where the bundled copy lives. */
const val EXERCISE_LIBRARY_ASSET = "exercises.json"

/**
 * Reads the compact array-of-arrays form into something with names on it.
 *
 * An index the file doesn't have resolves to an empty string rather than
 * throwing — the browser's `raw.muscles[i] || ''`. One malformed row should
 * cost that row's label, not the whole library.
 */
fun parseExerciseLibrary(json: String): List<LibraryExercise> {
    val root = JSONObject(json)
    val muscles = root.stringList("muscles")
    val equipment = root.stringList("equipment")
    val categories = root.stringList("categories")
    val levels = root.stringList("levels")

    val rows = root.optJSONArray("exercises") ?: return emptyList()
    val out = ArrayList<LibraryExercise>(rows.length())
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        val name = row.optString(0, "")
        if (name.isBlank()) continue
        out.add(
            LibraryExercise(
                name = name,
                muscle = muscles.getOrEmpty(row.optInt(1, -1)),
                equipment = equipment.getOrEmpty(row.optInt(2, -1)),
                category = categories.getOrEmpty(row.optInt(3, -1)),
                level = levels.getOrEmpty(row.optInt(4, -1)),
            )
        )
    }
    return out
}

/**
 * Filtered by equipment, matched on name or muscle, capped — the browser's
 * `searchExercises`, exactly.
 *
 * LIFT Android floats previously logged lifts to the top here. This app has no
 * such log for its own user: a coach's history is their clients', and whose
 * bench press to promote is not a question with an obvious answer. So this is
 * the plain browser behaviour, and the ordering is the file's.
 */
fun searchExerciseLibrary(
    library: List<LibraryExercise>,
    query: String,
    equipmentFilter: String = "",
    limit: Int = EXERCISE_SEARCH_LIMIT,
): List<LibraryExercise> {
    val needle = query.trim().lowercase(Locale.US)
    return library.filter { candidate ->
        (equipmentFilter.isBlank() || candidate.equipment == equipmentFilter) &&
            (needle.isEmpty() ||
                candidate.name.lowercase(Locale.US).contains(needle) ||
                candidate.muscle.lowercase(Locale.US).contains(needle))
    }.take(limit)
}

/**
 * The browser's `titleCase`, transcribed: uppercase any lowercase letter that
 * starts a word. "body only" becomes "Body Only", "e-z curl bar" becomes
 * "E-Z Curl Bar" — the hyphen is a word boundary there, as it is in the
 * JavaScript `\b`.
 *
 * Word characters are the ASCII set JavaScript's `\w` means, not Unicode's,
 * so the two agree on every row this file holds.
 */
internal fun titleCaseAscii(text: String): String {
    val out = StringBuilder(text.length)
    var previousWasWord = false
    for (c in text) {
        val isWord = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
        out.append(if (!previousWasWord && c in 'a'..'z') c.uppercaseChar() else c)
        previousWasWord = isWord
    }
    return out.toString()
}

private fun JSONObject.stringList(key: String): List<String> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.optString(it, "") }
}

private fun List<String>.getOrEmpty(index: Int): String =
    if (index in indices) this[index] else ""

/**
 * Holds the parsed library for the life of the process.
 *
 * Loaded the first time a routine is opened for editing rather than at
 * startup, and a failure is remembered as well as a success — a missing asset
 * should produce one message and a working type-it-yourself box, not a retry
 * on every keystroke.
 */
object ExerciseLibraryStore {

    @Volatile private var cached: List<LibraryExercise>? = null
    @Volatile private var failure: String? = null

    /** The parsed library, or null with [lastError] set when it could not load. */
    suspend fun load(context: Context): List<LibraryExercise>? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        failure?.let { return@withContext null }

        try {
            val json = context.applicationContext.assets
                .open(EXERCISE_LIBRARY_ASSET)
                .bufferedReader()
                .use { it.readText() }
            val parsed = parseExerciseLibrary(json)
            if (parsed.isEmpty()) {
                failure = "the file is empty"
                null
            } else {
                cached = parsed
                parsed
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }

    val lastError: String? get() = failure
}
