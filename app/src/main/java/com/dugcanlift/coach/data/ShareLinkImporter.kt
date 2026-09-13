package com.dugcanlift.coach.data
import com.dugcanlift.kit.*

sealed class ImportResult { data class Imported(val clientId: String, val clientName: String, val daysImported: Int) : ImportResult(); object UnsupportedVersion : ImportResult(); object Malformed : ImportResult() }

/** Coach iOS's ShareLinkImporter, rule for rule: find-or-create by id, goal replaces, each wire day REPLACES the stored day, others untouched. */
object ShareLinkImporter {
    fun import(fragment: String, repo: ClientRepository, nowEpochMs: Long = System.currentTimeMillis()): ImportResult {
        val p = when (val r = ShareLinkCodec.decode(fragment)) {
            is ShareDecodeResult.Success -> r.payload
            ShareDecodeResult.UnsupportedVersion -> return ImportResult.UnsupportedVersion
            ShareDecodeResult.MalformedPayload -> return ImportResult.Malformed
        }
        val existing = repo.get(p.client.id)
        val goal = p.goal?.let { Goal(it.calories, it.proteinG, it.fatG, it.carbsG, it.fiberG) } ?: existing?.goal
        val incoming = p.days.associate { d -> DayKey.adding(d.dayOffset, p.startDay) to toDay(d, DayKey.adding(d.dayOffset, p.startDay)) }
        val kept = existing?.days?.filter { it.dayKey !in incoming } ?: emptyList()
        val client = Client(p.client.id, p.client.name, p.client.unit, p.client.platform ?: existing?.platform, nowEpochMs, goal, (kept + incoming.values).sortedBy { it.dayKey })
        repo.save(client)
        return ImportResult.Imported(client.id, client.name, incoming.size)
    }

    private fun toDay(d: ShareDay, key: String): TrainingDay {
        val sets = d.exercises.flatMap { ex -> ex.sets.map { s -> ExerciseSet(ex.name, ex.equipment.ifEmpty { null }, s.weightLb, s.reps, s.rpe, s.durationSec, s.distanceMeters, s.isWarmup) } }
        // Per-serving on the wire; as-eaten in the store. LIFT iOS sends servings=1 (no-op); LIFT Android sends real counts.
        val food = d.food.orEmpty().map { f -> ClientFoodEntry(f.name, f.servings, f.calories * f.servings, f.proteinG * f.servings, f.fatG * f.servings, f.carbsG * f.servings, f.fiberG * f.servings, f.meal) }
        val ft = d.foodTotals
        return TrainingDay(key, d.sessionName, d.focus, d.bodyweightLb, d.steps, ft?.get(0), ft?.get(1), ft?.get(2), ft?.get(3), ft?.get(4), sets, food)
    }
}
