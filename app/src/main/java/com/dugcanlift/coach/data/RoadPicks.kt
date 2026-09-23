package com.dugcanlift.coach.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Road picks -- the items a coach is happy with at the places a client stops.
 *
 * LIFT has Road Food: a curated file of chains, items and gas-station snacks,
 * ranked against what is left of the client's day. A coach marking picks is
 * saying "these fit how I want you eating on the road" and nothing else -- no
 * calorie or macro claim, because LIFT already ranks on those and re-ranking
 * them from here would put a coach's tick in front of the client's own numbers.
 *
 * On the wire: `rf`, a flat list of item ids, omitted entirely when there are
 * none (PLAN-FORMAT.md "Road picks"). Item ids are the contract -- the Road
 * Food spec keeps them stable for exactly this -- and they are all that
 * travels. A whole chain is not a thing on the wire: "Pick all" here ticks the
 * items the coach can see at the time they tick them, so a chain that gains an
 * item next quarter does not silently gain a pick nobody looked at.
 *
 * **Nothing is filtered against this app's own copy of the data on the way
 * out.** The coach's bundle and the client's are two builds of two apps,
 * updated at different times, so only the receiver can say what it has. It
 * skips an id it does not know, silently, and shows no broken row. That is why
 * a withdrawn item is safe to leave in a coach's stored picks.
 *
 * Tracked and shown, never targeted, like saturated fat and the imbalance
 * figure: a pick is shown as a pick, and nothing anywhere judges what a client
 * ate or did not eat against it.
 *
 * **A port of Coach web's `coach/road-picks.js`, function for function** --
 * `normalise`, `wire`, `fromWire`, `catalogue`, `index`, `missing`, `countIn`,
 * `toggle`, `toggleAll` and `summary` -- the way [SideBalance] is a port of
 * `sides.js`. A rule changes there first and is ported again.
 */
object RoadPicks {

    /**
     * The stored shape: trimmed strings, no blanks, no duplicates, in the
     * order the coach ticked them. Anything else is dropped rather than
     * failing -- a picks list is not worth losing a backup over.
     */
    fun normalise(ids: List<String?>?): List<String> {
        val out = LinkedHashSet<String>()
        ids?.forEach { raw ->
            val id = raw?.trim().orEmpty()
            if (id.isNotEmpty()) out.add(id)
        }
        return out.toList()
    }

    /**
     * The `rf` value for a payload, or null when there is nothing to send.
     * Null means the key is left out; an empty array is never written.
     */
    fun wire(ids: List<String?>?): List<String>? = normalise(ids).takeIf { it.isNotEmpty() }

    /** `rf` read back, leniently: a missing or junk key is no picks at all. */
    fun fromWire(rf: JSONArray?): List<String> {
        if (rf == null) return emptyList()
        return normalise((0 until rf.length()).map { rf.opt(it) as? String })
    }

    /* ---------------- the bundled data ---------------- */

    /** One pickable thing and where it is found. */
    data class Entry(val id: String, val item: RoadFoodItem, val placeId: String, val placeName: String)

    /**
     * Every item in the file, chain items and gas-station snacks alike. Snacks
     * belong to no chain, so their place is the gas station they are all found
     * in -- web's `'snacks'` / "Gas station", the same two strings LIFT uses.
     */
    fun catalogue(data: RoadFoodData?): List<Entry> {
        if (data == null) return emptyList()
        val out = mutableListOf<Entry>()
        data.chains.forEach { chain ->
            chain.items.forEach { item ->
                if (item.id.isNotEmpty()) out += Entry(item.id, item, chain.id, chain.name)
            }
        }
        data.snacks.forEach { snack ->
            if (snack.id.isNotEmpty()) out += Entry(snack.id, snack, SNACKS_PLACE_ID, SNACKS_PLACE_NAME)
        }
        return out
    }

    const val SNACKS_PLACE_ID = "snacks"
    const val SNACKS_PLACE_NAME = "Gas station"

    /** The same, keyed by id, for a lookup that runs per row. */
    fun index(data: RoadFoodData?): Map<String, Entry> = catalogue(data).associateBy { it.id }

    /**
     * Picked ids this copy of the data has no item for. They still travel: the
     * client's app decides what it knows. Shown in Coach only so a coach is
     * never puzzled by a count that does not match what is on screen.
     */
    fun missing(ids: List<String?>?, data: RoadFoodData?): List<String> {
        val have = index(data)
        return normalise(ids).filterNot { it in have }
    }

    /** How many of [items] are picked. */
    fun countIn(ids: List<String?>?, items: List<RoadFoodItem>): Int {
        val picked = normalise(ids).toSet()
        return items.count { it.id in picked }
    }

    /**
     * [ids] with [id] added at the end or taken out. The order picks were
     * ticked in is kept: it is the only order a coach has authored.
     */
    fun toggle(ids: List<String?>?, id: String, on: Boolean): List<String> {
        val without = normalise(ids).filterNot { it == id }
        return if (on) without + id else without
    }

    /** [ids] with every id in [items] added, or every one of them removed. */
    fun toggleAll(ids: List<String?>?, items: List<RoadFoodItem>, on: Boolean): List<String> {
        val these = items.map { it.id }.filter { it.isNotEmpty() }
        val list = normalise(ids)
        return if (on) normalise(list + these) else list.filterNot { it in these.toSet() }
    }

    /**
     * What is picked, in words: "6 items at 3 places", "1 item at 1 place", ""
     * when there are none. Counts only what this copy of the data has, so the
     * sentence matches the ticks on screen; the wire still carries the rest.
     */
    fun summary(ids: List<String?>?, data: RoadFoodData?): String {
        val have = index(data)
        val found = normalise(ids).mapNotNull { have[it] }
        if (found.isEmpty()) return ""
        val places = found.map { it.placeId }.distinct().size
        return "${found.size} ${if (found.size == 1) "item" else "items"} at " +
            "$places ${if (places == 1) "place" else "places"}"
    }
}

/**
 * One menu item or gas-station product, as `road-food.json` lists it -- only
 * the fields a coach picking items is shown.
 *
 * Coach reads a deliberate **subset** of the file LIFT reads: no ordering
 * rules, no `kind`, no "checked on" staleness, and none of the nutrition
 * beyond what a row prints. Coach does not rank -- LIFT ranks, against a day
 * this app knows nothing about -- so a full parser here would be a second
 * implementation of rules nothing here uses. [RoadFoodItem.id] is the whole
 * contract; the rest is text on a row.
 *
 * Every number is null when the file does not list it, never zero: blank stays
 * blank, so a row says "kcal not listed" rather than showing a 0 the file
 * never claimed.
 */
data class RoadFoodItem(
    val id: String,
    val name: String,
    val serving: String? = null,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val modification: String? = null,
    val category: String? = null
)

/** A chain. Its items are what a coach ticks; the chain itself never travels. */
data class RoadFoodChain(val id: String, val name: String, val items: List<RoadFoodItem>)

data class RoadFoodData(val chains: List<RoadFoodChain>, val snacks: List<RoadFoodItem>)

/**
 * Reads `road-food.json`, as lenient as LIFT's own loader: a chain without an
 * id or an items list is dropped, an entry that is not an object is dropped.
 * Throws only when the file is not a Road Food file at all (no `chains`).
 */
fun parseRoadFood(json: String): RoadFoodData {
    val root = JSONObject(json)
    val chains = root.optJSONArray("chains") ?: throw IllegalArgumentException("not a Road Food file")
    return RoadFoodData(
        chains = chains.roadObjects().mapNotNull { c ->
            val id = c.roadText("id") ?: return@mapNotNull null
            val items = c.optJSONArray("items") ?: return@mapNotNull null
            RoadFoodChain(id = id, name = c.roadText("name") ?: id, items = items.roadObjects().map(::roadItem))
        },
        snacks = root.optJSONArray("snacks")?.roadObjects()?.map(::roadItem) ?: emptyList()
    )
}

private fun roadItem(o: JSONObject): RoadFoodItem = RoadFoodItem(
    id = o.roadText("id") ?: o.roadText("name").orEmpty(),
    name = o.roadText("name").orEmpty(),
    serving = o.roadText("serving"),
    kcal = roadNumber(o, "kcal"),
    proteinG = roadNumber(o, "proteinG"),
    modification = o.roadText("modification"),
    category = o.roadText("category")
)

/**
 * `road-food.js`'s `num`: a finite, non-negative number, or null. A numeric
 * string counts, as it does there; a boolean, a blank or anything else is not
 * a number and is not zero.
 */
private fun roadNumber(o: JSONObject, key: String): Double? {
    if (!o.has(key) || o.isNull(key)) return null
    val n = when (val v = o.opt(key)) {
        is Boolean -> return null
        is Number -> v.toDouble()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
        else -> return null
    }
    return n.takeIf { it.isFinite() && it >= 0.0 }
}

private fun JSONObject.roadText(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun JSONArray.roadObjects(): List<JSONObject> =
    (0 until length()).mapNotNull { opt(it) as? JSONObject }

/** Where the bundled copy lives. Copy it from `dugcanlift-kit/data/`, never edit it here. */
const val ROAD_FOOD_ASSET = "road-food.json"

/**
 * The bundled Road Food file, parsed once for the life of the process.
 *
 * The same bytes as `dugcanlift-kit/data/road-food.json`, which LIFT bundles
 * too -- item ids are the contract picks travel on, so the copies must not
 * drift. Offline by construction: it is an app asset, and nothing here touches
 * the network. Read the first time the Road section is opened rather than at
 * launch, the way [ExerciseLibraryStore] reads the exercise file, and a
 * failure is remembered as well as a success.
 */
object RoadFoodStore {

    @Volatile private var cached: RoadFoodData? = null
    @Volatile private var failure: String? = null

    /** The parsed file, or null with [lastError] set when it could not load. */
    suspend fun load(context: Context): RoadFoodData? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        failure?.let { return@withContext null }
        try {
            val json = context.applicationContext.assets
                .open(ROAD_FOOD_ASSET)
                .bufferedReader()
                .use { it.readText() }
            parseRoadFood(json).also { cached = it }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }

    val lastError: String? get() = failure
}
