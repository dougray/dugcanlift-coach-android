package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Seconds between the Unix epoch (1970-01-01) and Foundation's reference date (2001-01-01),
 * which is what `Date`'s wire encoding (and this file's `lastImportedAt`) actually counts from.
 * Add this on read, subtract it on write. Getting the sign backwards silently shifts every
 * client's import date by 31 years, and the resulting number still looks plausible, so both
 * directions are pinned by [BackupCodecTest].
 */
private const val FOUNDATION_EPOCH_OFFSET_SECONDS = 978_307_200L

/**
 * The top-level keys this codec models itself. Everything else in the file -- `routines` and
 * `sessions`, the web build's `plans`/`workouts`/`settings`, whatever a newer Coach iOS adds
 * tomorrow -- is opaque cargo, preserved byte-for-byte. See [RestoreResult.preservedLibrary].
 *
 * `recipes` and `meals` joined this set when Cook arrived; `routines` and `sessions` when Train
 * did; `roadPicks` when Cook's Road section did. The browser build's own `plans` and `workouts`
 * stay cargo -- different keys carrying different shapes, which this codec would rather carry
 * whole than half-understand.
 *
 * A key that becomes modelled has to join this set in the same commit, or it is written twice:
 * once from the model and once out of [RestoreResult.preservedLibrary], where a file written by a
 * build that carried it as cargo still holds it.
 *
 * Note what this does NOT mean. Being modelled is not permission to be lossy: [recipeFromJson]
 * preserves every per-recipe key it has no field for, the same preservation-by-exclusion rule
 * this set applies at the top level, one layer down. A file written by a newer Coach iOS with a
 * field Cook has never heard of still round-trips through this app intact.
 */
private val ENVELOPE_KEYS = setOf("v", "clients", "recipes", "meals", "routines", "sessions", "roadPicks")

// optStringOrNull and optLongOrNull live in JsonExtensions.kt -- shared with Models.kt.

/**
 * [clients] decoded from the file, in Coach Android's own model. [preservedLibrary] holds every
 * top-level key that is not part of this codec's own envelope ([ENVELOPE_KEYS]) -- the four
 * library arrays, and anything else the file carried -- as the exact JSON values that were parsed,
 * never decoded into models, so [BackupCodec.export] can write them straight back out untouched.
 * `null` means the file had nothing beyond the envelope (a v1 file, or a v2 file with no library
 * yet), which must round-trip to "no library keys", not to "delete the library."
 *
 * Round 6: this used to be a fixed list of four key names, which meant a future Coach iOS file's
 * own top-level array (`programs`, say) was read, dropped on the floor, and then written away
 * permanently by the next Save Backup -- the identical failure class as losing the library,
 * reached through a key nobody had thought to enumerate. Preserving by exclusion rather than by
 * enumeration is what makes "never lose what you do not understand" hold for keys that do not
 * exist yet.
 */
data class RestoreResult(
    val clients: List<Client>,
    val preservedLibrary: JSONObject?,
    /** Decoded from `recipes`. Empty when the file carried none -- which must restore as "no
     *  recipes in this file", never as "delete the ones on this device"; see [BackupService]. */
    val recipes: List<Recipe> = emptyList(),
    /** Decoded from `meals`. The web build spells its own under `plans`, a different shape that
     *  stays in [preservedLibrary] rather than being half-understood here. */
    val meals: List<PlannedMeal> = emptyList(),
    /** Decoded from `routines`. The web build's equivalent is `workouts`, which stays cargo. */
    val routines: List<Routine> = emptyList(),
    /** Decoded from `sessions`. A row naming no client is dropped -- see
     *  [scheduledSessionFromJson]. */
    val sessions: List<ScheduledSession> = emptyList(),
    /**
     * Decoded from `roadPicks`: one list of Road Food item ids per client id
     * (BACKUP-FORMAT.md "The Coach backup's road picks"). Empty when the file
     * carried none, which must restore as "this file has no picks", never as
     * "clear the ones on this device" -- see [RoadPickRepository.merge].
     */
    val roadPicks: Map<String, List<String>> = emptyMap()
)

/**
 * Reads and writes Coach iOS's `BackupCodec` v2 file
 * (`coach-ios/Sources/Shared/BackupCodec.swift`) -- the same file iOS's own Connect screen saves
 * and restores, so a coach can move between phone and Android without losing anything.
 *
 * `id`, `name`, `displayUnit`, `platform`, `goal` and `days` (and everything nested under a day --
 * sets and food entries) already share field names with [Client]'s own JSON shape, so this codec
 * only has custom handling for the top-level client envelope: `lastImportedAt`'s 2001-epoch
 * conversion, and the opaque top-level cargo. See [ENVELOPE_KEYS] and
 * [FOUNDATION_EPOCH_OFFSET_SECONDS].
 *
 * Clients restore by **replace** -- callers pass [RestoreResult.clients] to
 * [ClientRepository.replaceAll], exactly as iOS's own restore replaces its roster. `recipes`,
 * `meals`, `routines` and `sessions` decode into Cook and Train models, which keep every field
 * they do not model; any other top-level key is opaque cargo carried as-is. Either way a
 * phone -> Android -> phone round trip does not lose a coach's library.
 *
 * A day's `nutrientTotals` and a food's `saturatedFatG`/`sugarG`/`sodiumMg` are
 * [TrainingDay.toJson]'s own names; CLAUDE.md "Saturated fat, sugar and sodium" pins them for iOS.
 *
 * `v` is still written as 2 -- the format Coach Android actually produces -- even when the restored
 * file claimed a higher version. Echoing an unknown `v` back would claim a compatibility this codec
 * does not have; carrying the unknown keys is what actually protects the data.
 */
object BackupCodec {
    fun restore(json: String): RestoreResult {
        val root = JSONObject(json)
        // `clients` is non-optional on iOS (BackupCodec.swift:15), so a file missing it -- a LIFT
        // client-app backup, a web-app export, Coach Android's own preserved-library.json -- must
        // fail the whole decode there, not silently produce an empty list: BackupService.restore
        // passes an empty list straight to ClientRepository.replaceAll, which deletes every client
        // already on the device and reports "Restored 0 clients." as success. optJSONArray returns
        // null both when the key is absent and when it is present but not a JSON array, so either
        // case throws here; an explicitly empty `"clients": []` still decodes to an empty roster,
        // exactly as iOS accepts it.
        val clientsArray = root.optJSONArray("clients")
            ?: throw org.json.JSONException("Missing or invalid 'clients' array")
        val clients = (0 until clientsArray.length()).map { clientFromJson(clientsArray.getJSONObject(it)) }

        val recipes = root.optJSONArray("recipes").mapObjectsOrEmpty(::recipeFromJson)
        val meals = root.optJSONArray("meals").mapObjectsOrEmpty(::plannedMealFromJson)
        val routines = root.optJSONArray("routines").mapObjectsOrEmpty(::routineFromJson)
        val sessions = root.optJSONArray("sessions")
            .mapObjectsOrEmpty(::scheduledSessionFromJson).filterNotNull()
        val roadPicks = RoadPickRepository.decode(root.optJSONObject("roadPicks"))

        var preserved: JSONObject? = null
        for (key in root.keys()) {
            if (key in ENVELOPE_KEYS || root.isNull(key)) continue
            val library = preserved ?: JSONObject().also { preserved = it }
            library.put(key, root.get(key))
        }
        return RestoreResult(clients, preserved, recipes, meals, routines, sessions, roadPicks)
    }

    /**
     * [recipes] and [meals] are written from models; everything else the file carried rides along
     * in [preservedLibrary] untouched.
     *
     * Neither has a default, deliberately. A default of `emptyList()` would leave every existing
     * call site compiling unchanged while quietly writing an empty library over a coach's
     * recipes -- the same silent-loss shape that made preservation-by-exclusion necessary in the
     * first place. Required parameters make the compiler name every caller instead.
     *
     * An empty list writes no key at all, matching what this codec has always done for a library
     * it had nothing for: a v1 file restored and re-exported does not sprout v2 keys. That is
     * safe in both directions -- iOS merges the library by id and never deletes from it
     * (`BackupCodec.swift`: `where !existingRecipes.contains(row.id)`), so absent and empty mean
     * the same thing there.
     *
     * The caller must not pass empty when the library merely failed to load;
     * [StoredCookLibrary.isUnreadable] exists to make that distinguishable, and [BackupService]
     * refuses the whole export in that case rather than writing a file that silently drops them.
     */
    fun export(
        clients: List<Client>,
        preservedLibrary: JSONObject?,
        recipes: List<Recipe>,
        meals: List<PlannedMeal>,
        routines: List<Routine>,
        sessions: List<ScheduledSession>,
        roadPicks: Map<String, List<String>>
    ): String {
        val root = JSONObject()
        root.put("v", 2)
        root.put("clients", JSONArray(clients.map { clientToJson(it) }))
        if (recipes.isNotEmpty()) root.put("recipes", JSONArray(recipes.map { it.toJson() }))
        if (meals.isNotEmpty()) root.put("meals", JSONArray(meals.map { it.toJson() }))
        if (routines.isNotEmpty()) root.put("routines", JSONArray(routines.map { it.toJson() }))
        if (sessions.isNotEmpty()) root.put("sessions", JSONArray(sessions.map { it.toJson() }))
        // An object keyed by client id, omitted when there are none -- what
        // Coach web writes, and what its restore reads back per client.
        RoadPickRepository.encode(roadPicks)?.let { root.put("roadPicks", it) }
        if (preservedLibrary != null) {
            // Every preserved key, not a fixed list -- and never one of this codec's own envelope
            // keys, which are written above and must not be sourced from the cargo.
            for (key in preservedLibrary.keys()) {
                if (key in ENVELOPE_KEYS || preservedLibrary.isNull(key)) continue
                root.put(key, preservedLibrary.get(key))
            }
        }
        return root.toString()
    }

    /**
     * Deliberately more lenient than iOS: Swift's `BackupClient.days` is a non-optional
     * `[BackupDay]`, so a client object with no `days` key at all fails iOS's decode outright and
     * rejects the whole file. Here a missing `days` key defaults to an empty list instead of
     * throwing. This is the same call [ClientRepository] already makes for its own per-client
     * files ("a corrupt file is skipped, not fatal") applied to backups: Coach Android is both a
     * reader and a writer of this format, always emits `days` itself, and a stricter read buys no
     * safety against its own output -- it only makes a hand-edited or partially-transcribed file
     * (or a future, more minimal encoder) an all-or-nothing failure instead of restoring the
     * client with no training days. Pinned by
     * `a client object missing its days key restores with an empty list, not a crash`.
     */
    private fun clientFromJson(json: JSONObject): Client {
        val lastImportedAtEpochMs = if (json.has("lastImportedAt") && !json.isNull("lastImportedAt")) {
            val foundationSeconds = json.getDouble("lastImportedAt")
            ((foundationSeconds + FOUNDATION_EPOCH_OFFSET_SECONDS) * 1000.0).toLong()
        } else {
            System.currentTimeMillis()
        }
        return Client(
            id = json.getString("id"),
            name = json.getString("name"),
            displayUnit = json.getString("displayUnit"),
            platform = json.optStringOrNull("platform"),
            lastImportedAtEpochMs = lastImportedAtEpochMs,
            goal = if (json.has("goal") && !json.isNull("goal")) Goal.fromJson(json.getJSONObject("goal")) else null,
            days = json.optJSONArray("days")?.let { arr -> (0 until arr.length()).map { TrainingDay.fromJson(arr.getJSONObject(it)) } }.orEmpty(),
            // Absent in every file Coach iOS has written so far, and in every Android file before
            // outdoor arrived: both read as "nothing sent", which is what they were.
            outdoorBests = Client.outdoorBestsFromJson(json),
            lastRoute = Client.lastRouteFromJson(json),
            exportedAtEpochSec = json.optLongOrNull("exportedAtEpochSec")
        )
    }

    private fun clientToJson(client: Client): JSONObject = JSONObject().apply {
        put("id", client.id)
        put("name", client.name)
        put("displayUnit", client.displayUnit)
        put("platform", client.platform ?: JSONObject.NULL)
        put("lastImportedAt", client.lastImportedAtEpochMs / 1000.0 - FOUNDATION_EPOCH_OFFSET_SECONDS)
        put("goal", client.goal?.toJson() ?: JSONObject.NULL)
        put("days", JSONArray(client.days.map { it.toJson() }))
        // Unix seconds, spelled so in the key, unlike `lastImportedAt` above: this one is the
        // wire's own `z`, never a Foundation date.
        put("outdoorBests", client.outdoorBests?.let { b -> JSONArray(b.map { it.toJson() }) } ?: JSONObject.NULL)
        put("lastRoute", client.lastRoute?.toJson() ?: JSONObject.NULL)
        put("exportedAtEpochSec", client.exportedAtEpochSec ?: JSONObject.NULL)
    }
}

/** Decodes every JSON object in [this], skipping any element that is not one. */
private fun <T> JSONArray?.mapObjectsOrEmpty(decode: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> (opt(i) as? JSONObject)?.let(decode) }
}
