package com.dugcanlift.coach.data

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One send: the plan payload exactly as it was encoded, with the canonical hash of it.
 *
 * Coach built a plan link fresh on every render and handed it straight to the share sheet, so once
 * a routine was edited the store no longer said what the client got -- and the client page could
 * never put what was booked beside what came back. This is the record that makes that possible.
 *
 * The payload, not the fragment: a fragment has to be inflated on every render and a future `v`
 * would make it unreadable, while the decoded payload is a few hundred bytes for a training week
 * and survives a version bump. And the whole payload, not the training half -- `r`/`m`/`rf` cost
 * almost nothing and leave "did they eat the plan?" open without a second migration.
 *
 * [payloadJson] is the bytes as encoded rather than a [JSONObject], so two rows compare as values
 * (a [JSONObject] compares by identity) and "exactly as encoded" is literally true.
 */
data class SentPlan(
    val id: String,
    val clientId: String,
    /** Unix seconds, as Coach web and Coach iOS write it -- never a Foundation date. */
    val sentAt: Long,
    val payloadHash: String,
    val payloadJson: String
) {
    /** The payload, parsed. Cheap enough to do per comparison; nothing caches it. */
    fun payload(): JSONObject = runCatching { JSONObject(payloadJson) }.getOrDefault(JSONObject())

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("clientId", clientId)
        .put("sentAt", sentAt)
        .put("payloadHash", payloadHash)
        .put("payload", payload())

    companion object {
        /** Null for a row missing anything that makes it a record of a send -- junk is not a restore failure. */
        fun fromJson(o: JSONObject?): SentPlan? {
            if (o == null) return null
            val id = o.optStringOrNull("id") ?: return null
            val clientId = o.optStringOrNull("clientId") ?: o.optStringOrNull("clientID") ?: return null
            val payload = o.optJSONObject("payload") ?: return null
            return SentPlan(
                id = id,
                clientId = clientId,
                sentAt = o.optLongOrNull("sentAt") ?: 0L,
                payloadHash = o.optStringOrNull("payloadHash").orEmpty(),
                payloadJson = payload.toString()
            )
        }
    }
}

/**
 * The rules about the record itself: how a send is filed, how the file merges, and how many are
 * kept. A port of the `SentPlan` half of Coach web's `coach/plan-log.js`, function for function --
 * `canonical`, `hash`, `forClient`, `record`, `mergeBackup` -- the way [RoadPicks] is a port of
 * `road-picks.js`. A rule changes there first and is ported again.
 *
 * Pure and free of Android so it can be tested directly; the file it lives in is
 * [SentPlanRepository].
 */
object SentPlans {

    /**
     * The newest sends kept per client. A backup must not grow without limit, and a coach reading
     * eight weeks back has never needed more than this.
     */
    const val CAP = 26

    /**
     * JSON with every object's keys in sorted order, at every depth -- the canonical form Coach
     * web hashes and the one LIFT iOS's `PlanImporter.hash(of:)` computes. Arrays keep their
     * order: `k` is a list of bookings and reordering it would be a different plan.
     */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.opt(key))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> numberText(value)
        else -> JSONObject.quote(value.toString())
    }

    /**
     * A number the way `JSON.stringify` writes one: an integral value carries no `.0`, so a weight
     * that survived a round trip through a double does not hash differently from the one that was
     * typed. A value JSON cannot represent is `null`, which is what it would be written as.
     */
    private fun numberText(value: Number): String {
        if (value is Int || value is Long || value is Short || value is Byte) return value.toString()
        val d = value.toDouble()
        if (!d.isFinite()) return "null"
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    /** SHA-256 of the canonical form, lower-case hex. */
    fun hash(payload: JSONObject): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical(payload).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /** This client's rows, newest first. */
    fun forClient(rows: List<SentPlan>, clientId: String?): List<SentPlan> {
        if (clientId == null) return emptyList()
        return rows.filter { it.clientId == clientId }.sortedByDescending { it.sentAt }
    }

    /**
     * [rows] with this send recorded, as a new list.
     *
     * A send whose hash matches this client's **newest** send replaces it: an abandoned share
     * sheet is re-sent identically a moment later, and that is one plan, not two. Its id stays, so
     * a backup written before and one written after merge as one row rather than two. An older
     * matching send is left alone -- a coach who went back to last week's plan after a week of
     * something else did send it again, and the two dates are both true.
     *
     * Pruned to the newest [CAP] per client, oldest first. Other clients' rows are untouched and
     * keep their order.
     */
    fun record(rows: List<SentPlan>, entry: SentPlan): List<SentPlan> {
        val mine = forClient(rows, entry.clientId)
        val others = rows.filter { it.clientId != entry.clientId }
        val newest = mine.firstOrNull()
        val kept = if (newest != null && newest.payloadHash.isNotEmpty() && newest.payloadHash == entry.payloadHash) {
            listOf(entry.copy(id = newest.id)) + mine.drop(1)
        } else {
            listOf(entry) + mine
        }
        return others + kept.take(CAP)
    }

    /**
     * Rows out of a backup folded in, by id, additively -- the library half's rule, not the
     * roster's: an older backup must never delete a newer send, and a file written before sent
     * plans existed has no key at all and changes nothing. Ids are compared case-insensitively, as
     * BACKUP-FORMAT asks everywhere, because iOS writes UUIDs upper case and a browser lower. The
     * cap is applied after the merge, so restoring two files cannot leave a client with more rows
     * than sending would.
     *
     * @return the merged rows and how many were added.
     */
    fun mergeBackup(rows: List<SentPlan>, incoming: List<SentPlan>): MergeResult {
        if (incoming.isEmpty()) return MergeResult(rows, 0)
        val have = rows.map { it.id.lowercase(Locale.US) }.toMutableSet()
        val all = rows.toMutableList()
        var added = 0
        incoming.forEach { row ->
            if (!have.add(row.id.lowercase(Locale.US))) return@forEach
            added += 1
            all += row
        }
        val kept = all.map { it.clientId }.distinct().flatMap { forClient(all, it).take(CAP) }
        return MergeResult(kept, added)
    }

    data class MergeResult(val rows: List<SentPlan>, val added: Int)

    /** The backup's `sentPlans` array, read leniently: a row that is not a send is skipped. */
    fun decode(array: JSONArray?): List<SentPlan> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { SentPlan.fromJson(array.opt(it) as? JSONObject) }
    }

    /** The same array for a backup, or null when there is nothing to write -- never `[]`. */
    fun encode(rows: List<SentPlan>): JSONArray? =
        JSONArray().also { a -> rows.forEach { a.put(it.toJson()) } }.takeIf { it.length() > 0 }
}
