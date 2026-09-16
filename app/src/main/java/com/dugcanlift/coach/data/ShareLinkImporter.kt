package com.dugcanlift.coach.data
import com.dugcanlift.kit.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class ImportResult { data class Imported(val clientId: String, val clientName: String, val daysImported: Int) : ImportResult(); object UnsupportedVersion : ImportResult(); object Malformed : ImportResult() }

/**
 * Coach iOS's ShareLinkImporter, rule for rule: find-or-create by id, each wire day REPLACES the stored day, others untouched,
 * and everything that describes the client rather than a day -- name, unit, platform, goal, outdoor bests, last route --
 * follows the newest send, as Coach web's `absorb` does. See [runImport].
 */
object ShareLinkImporter {

    /**
     * Serialises the whole read-merge-write. `Files.move` makes each individual *write* atomic; it
     * does nothing for a lost update, and this function is read-modify-write over the entire
     * client file. RosterScreen dispatches an import per user action, so a pasted link importing
     * while a share-sheet link arrives gave two coroutines the same `existing`, each merging its
     * own days onto it and each writing the whole file -- the loser's days simply gone, under a
     * snackbar reporting success. `RosterLoader` (round 4) serialised the roster *reads*; the
     * writes are the ones that lose data.
     *
     * A blocking lock rather than a `Mutex` on purpose: every caller is already on
     * `Dispatchers.IO` (RosterScreen wraps this in `withContext(Dispatchers.IO)`), an import is
     * short, and keeping this a plain function means it is still correct if some future caller is
     * not in a coroutine at all.
     */
    private val importLock = ReentrantLock()

    fun import(fragment: String, repo: ClientRepository, nowEpochMs: Long = System.currentTimeMillis()): ImportResult =
        importLock.withLock { runImport(fragment, repo, nowEpochMs) }

    private fun runImport(fragment: String, repo: ClientRepository, nowEpochMs: Long): ImportResult {
        val p = when (val r = ShareLinkCodec.decode(fragment)) {
            is ShareDecodeResult.Success -> r.payload
            ShareDecodeResult.UnsupportedVersion -> return ImportResult.UnsupportedVersion
            ShareDecodeResult.MalformedPayload -> return ImportResult.Malformed
        }

        // ShareDecodeResult.Success means "the JSON parsed", not "this payload is safe to use".
        // `r` is taken as a bare string and `k` as a bare int by the codec, so a link carrying
        // "r":"not-a-date" or "k":2000000000 decoded as Success and then threw out of
        // DayKey.adding, killing the process instead of showing "That doesn't look like a LIFT
        // link." Coach iOS guards exactly this, per day:
        // `guard let dayKey = DayKey.adding(days: wireDay.k, to: payload.r) else { continue }`
        // (coach-ios/Sources/Shared/ShareLinkImporter.swift:27). Matched here: a day whose date
        // cannot be resolved is skipped, and a payload that yields nothing usable from days it
        // actually carried is the malformed link the coach should be told about. A payload with no
        // days at all is untouched by this -- a goal-only update is legitimate.
        val resolved = p.days.mapNotNull { d -> resolveDayKey(d.dayOffset, p.startDay)?.let { it to d } }
        if (p.days.isNotEmpty() && resolved.isEmpty()) return ImportResult.Malformed

        val existing = repo.get(p.client.id)
        val incoming = resolved.associate { (key, d) -> key to toDay(d, key) }
        val kept = existing?.days?.filter { it.dayKey !in incoming } ?: emptyList()
        // Outdoor bests and the last route are all-time, not per day, so they follow the send rather
        // than the window: a newer payload's replace the stored ones, and an ABSENT one clears them
        // -- a client who turned route sharing off expects the route gone, not frozen at the last one
        // they sent (SHARE-FORMAT "Outdoor"). An older link opened late must not undo either, so
        // "newer" is the payload's own `z` against the newest one absorbed, as Coach web's `absorb`
        // decides it. A client stored before `z` was kept has nothing to compare, and any send wins.
        val stored = existing?.exportedAtEpochSec
        val newer = stored == null || p.exportedAtEpochSeconds >= stored
        // The same rule for name, unit, platform and goal: a client who changed their goal last week
        // must not have it undone by an older link pasted late. Days above land from any link, since a
        // link is the truth for the days it covers whenever it arrives. An absent goal keeps the stored one.
        val profile = if (newer || existing == null) p.client else null
        val goal = (if (newer) p.goal?.let { Goal(it.calories, it.proteinG, it.fatG, it.carbsG, it.fiberG) } else null)
            ?: existing?.goal
        val client = Client(
            p.client.id,
            profile?.name ?: existing!!.name,
            profile?.unit ?: existing!!.displayUnit,
            profile?.platform ?: existing?.platform,
            nowEpochMs, goal,
            (kept + incoming.values).sortedBy { it.dayKey },
            outdoorBests = if (newer) toBests(p.outdoorBests) else existing?.outdoorBests,
            lastRoute = if (newer) toLastRoute(p.lastRoute) else existing?.lastRoute,
            exportedAtEpochSec = if (newer) p.exportedAtEpochSeconds else stored
        )
        repo.save(client)
        return ImportResult.Imported(client.id, client.name, incoming.size)
    }

    /** `r` + `k` as a real calendar date, or null for any `r` or `k` no calendar can resolve. */
    private fun resolveDayKey(dayOffset: Int, startDay: String): String? =
        runCatching { DayKey.adding(dayOffset, startDay) }.getOrNull()?.takeIf { DayKey.parse(it) != null }

    private fun toDay(d: ShareDay, key: String): TrainingDay {
        val sets = d.exercises.flatMap { ex -> ex.sets.map { s -> ExerciseSet(ex.name, ex.equipment.ifEmpty { null }, s.weightLb.finite(), s.reps, s.rpe.finite(), s.durationSec.finite(), s.distanceMeters.finite(), s.isWarmup) } }
        // Per-serving on the wire; as-eaten in the store. LIFT iOS sends servings=1 (no-op); LIFT Android sends real counts.
        // `fe` (saturated fat, sugar, sodium) is per serving too and gets the same multiply -- a stored food whose
        // calories are as eaten and whose sodium is per serving would disagree with itself. Null stays null.
        val food = d.food.orEmpty().mapNotNull { f ->
            val x = f.details
            val entry = ClientFoodEntry(f.name, f.servings, f.calories * f.servings, f.proteinG * f.servings, f.fatG * f.servings, f.carbsG * f.servings, f.fiberG * f.servings, f.meal,
                saturatedFatG = x?.saturatedFatG.asEaten(f.servings), sugarG = x?.sugarG.asEaten(f.servings), sodiumMg = x?.sodiumMg.asEaten(f.servings))
            entry.takeIf { listOf(it.servings, it.calories, it.proteinG, it.fatG, it.carbsG, it.fiberG).all { v -> v.isFinite() } }
        }
        val ft = d.foodTotals
        val outdoor = d.outdoor.orEmpty().filter { it.type.isOutdoorType() }.map { OutdoorActivity(it.type, it.durationSec, it.distanceMeters, it.climbMeters) }
        return TrainingDay(requireDayKey(key), d.sessionName, d.focus, d.bodyweightLb.finite(), d.steps, ft?.get(0).finite(), ft?.get(1).finite(), ft?.get(2).finite(), ft?.get(3).finite(), ft?.get(4).finite(), sets, food, outdoor,
            nutrientTotals = toNutrientTotals(d.nutrientTotals))
    }

    /**
     * `fx` exactly as sent: its totals are already as eaten and rounded by the sender, so nothing is
     * recomputed here -- not even for an itemised day, whose `fx` SHARE-FORMAT says is always sent
     * "so a decoder never has to add it up". A day without `fx` stores no totals, which is what an
     * older link and a day with nothing recorded both mean.
     */
    private fun toNutrientTotals(t: ShareNutrientTotals?): DayNutrientTotals? =
        t?.let { DayNutrientTotals(it.saturatedFatG.finite(), it.sugarG.finite(), it.sodiumMg.finite(), it.foods, it.withSaturatedFat, it.withSugar, it.withSodium) }
            ?.takeIf { !it.isEmpty }

    private fun Double?.asEaten(servings: Double): Double? = this?.let { it * servings }.finite()

    /**
     * Coach web's `readBests`, rule for rule: a type this app does not know is skipped rather than
     * guessed at, and a best that is not positive is no best -- the wire never sends a zero, so one
     * that arrives is not a measurement. Nothing left is null, the same as nothing sent.
     */
    private fun toBests(ob: List<ShareOutdoorBest>?): List<OutdoorBest>? =
        ob.orEmpty().filter { it.type.isOutdoorType() }
            .map { OutdoorBest(it.type, it.count, it.farthestMeters.positive(), it.longestSec.positive(), it.fastestSecPerKm.positive()) }
            .takeIf { it.isNotEmpty() }

    /** `readLastRoute`: a route that does not decode to two points cannot be drawn, and is no route. */
    private fun toLastRoute(lr: ShareLastRoute?): LastRoute? {
        if (lr == null || !lr.type.isOutdoorType()) return null
        if (OutdoorShare.decodePolyline(lr.polyline).size < 2) return null
        return LastRoute(lr.type, lr.startedAtEpochSec, lr.durationSec, lr.distanceMeters, lr.climbMeters, lr.polyline)
    }

    private fun Int.isOutdoorType() = this in 0 until OutdoorShare.TYPE_COUNT
    private fun Long?.positive(): Long? = this?.takeIf { it > 0 }

    /**
     * The kit's `ft` reader is `a.optDouble(it)` with no NaN guard (unlike its `optDoubleOrNull`,
     * which has one), so `"ft":[2410,188,71,230,"x"]` decodes to a NaN fifth value -- and
     * `JSONObject.put("foodFiberG", NaN)` throws from inside `repo.save`, killing the process on a
     * link the coach cannot see anything wrong with. A value JSON cannot represent is not a
     * measurement: it is absent, which this app already models properly everywhere else.
     */
    private fun Double?.finite(): Double? = this?.takeIf { it.isFinite() }
}
