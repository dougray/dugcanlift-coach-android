package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The record of what a coach sent: how a send is filed, how the file merges, how many are kept.
 *
 * A port of the `SentPlan` half of Coach web's `coach/plan-log.test.mjs`, case for case, plus what
 * only Android has: the file itself, `BackupCodec`, and Train's Send filing one.
 */
class SentPlanTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun row(id: String, clientId: String, sentAt: Long, hash: String) =
        SentPlan(id, clientId, sentAt, hash, JSONObject().put("v", 1).put("k", JSONArray()).toString())

    private fun entry(id: String, clientId: String, sentAt: Long, hash: String) =
        SentPlan(id, clientId, sentAt, hash, JSONObject().put("v", 1).toString())

    /* ---------------- recording ---------------- */

    @Test fun `an identical re-share replaces the newest row rather than adding one`() {
        val rows = SentPlans.record(listOf(row("a", "c", 100, "h1")), entry("b", "c", 200, "h1"))
        assertEquals(1, rows.size)
        assertEquals("the id stays, so a backup merges it as one row", "a", rows[0].id)
        assertEquals(200L, rows[0].sentAt)
    }

    @Test fun `a different plan is a new row, and an older matching one is left alone`() {
        var rows = SentPlans.record(listOf(row("a", "c", 100, "h1")), entry("b", "c", 200, "h2"))
        rows = SentPlans.record(rows, entry("d", "c", 300, "h1"))
        assertEquals(listOf("d", "b", "a"), SentPlans.forClient(rows, "c").map { it.id })
    }

    @Test fun `the cap prunes oldest first, per client, leaving other clients alone`() {
        var rows = listOf(row("other", "z", 1, "h"))
        for (i in 0 until SentPlans.CAP + 4) {
            rows = SentPlans.record(rows, entry("n$i", "c", (i + 1).toLong(), "h$i"))
        }
        val mine = SentPlans.forClient(rows, "c")
        assertEquals(SentPlans.CAP, mine.size)
        assertEquals("newest kept", "n${SentPlans.CAP + 3}", mine.first().id)
        assertEquals("oldest four pruned", "n4", mine.last().id)
        assertEquals(1, SentPlans.forClient(rows, "z").size)
    }

    /* ---------------- the hash ---------------- */

    @Test fun `the hash is the canonical re-encode, so key order does not change it`() {
        val a = SentPlans.hash(
            JSONObject().put("v", 1).put("t", "plan")
                .put("k", JSONArray().put(JSONObject().put("d", "2026-10-12").put("x", 0)))
        )
        val b = SentPlans.hash(
            JSONObject().put("k", JSONArray().put(JSONObject().put("x", 0).put("d", "2026-10-12")))
                .put("t", "plan").put("v", 1)
        )
        assertEquals(a, b)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(a))
    }

    @Test fun `array order is part of the plan, not incidental`() {
        val c = SentPlans.hash(JSONObject().put("k", JSONArray()
            .put(JSONObject().put("d", "2026-10-13")).put(JSONObject().put("d", "2026-10-12"))))
        val d = SentPlans.hash(JSONObject().put("k", JSONArray()
            .put(JSONObject().put("d", "2026-10-12")).put(JSONObject().put("d", "2026-10-13"))))
        assertTrue(c != d)
    }

    @Test fun `an integral weight hashes the way JSON writes it, whatever it went through`() {
        // 220.0 out of a kilogram round trip and 220 typed are the same plan, and `JSON.stringify`
        // writes both as `220`. A canonical form that wrote `220.0` for one of them would file a
        // re-send as a second plan.
        assertEquals("{\"w\":220}", SentPlans.canonical(JSONObject().put("w", 220.0)))
        assertEquals("{\"w\":220.5}", SentPlans.canonical(JSONObject().put("w", 220.5)))
        assertEquals("{\"w\":null}", SentPlans.canonical(JSONObject().put("w", JSONObject.NULL)))
    }

    /* ---------------- the backup ---------------- */

    @Test fun `a backup written before sentPlans existed restores unchanged`() {
        val rows = listOf(row("a", "c", 100, "h1"))
        val merged = SentPlans.mergeBackup(rows, emptyList())
        assertEquals(rows, merged.rows)
        assertEquals(0, merged.added)
    }

    @Test fun `an older backup never deletes a newer send, and merges by id`() {
        val rows = listOf(row("new", "c", 300, "h2"))
        val merged = SentPlans.mergeBackup(rows, listOf(row("old", "c", 100, "h1"), row("NEW", "c", 1, "h2")))
        assertEquals(listOf("new", "old"), SentPlans.forClient(merged.rows, "c").map { it.id })
        assertEquals("NEW is new only in spelling", 1, merged.added)
        assertEquals("the newer send is untouched", 300L, SentPlans.forClient(merged.rows, "c")[0].sentAt)
    }

    @Test fun `a restore cannot leave a client with more rows than sending would`() {
        val incoming = (0 until SentPlans.CAP + 5).map { row("f$it", "c", (it + 1).toLong(), "h$it") }
        val merged = SentPlans.mergeBackup(emptyList(), incoming)
        assertEquals(SentPlans.CAP, SentPlans.forClient(merged.rows, "c").size)
    }

    @Test fun `a round trip through the backup shape keeps every field`() {
        val rows = SentPlans.record(emptyList(), SentPlan("a", "c", 1760745600, "abc",
            JSONObject().put("v", 1).put("t", "plan").put("w", JSONArray()).toString()))
        val file = JSONObject(JSONObject().put("sentPlans", SentPlans.encode(rows)).toString())
        val back = SentPlans.mergeBackup(emptyList(), SentPlans.decode(file.optJSONArray("sentPlans")))
        assertEquals(rows.size, back.rows.size)
        assertEquals(rows[0].copy(payloadJson = ""), back.rows[0].copy(payloadJson = ""))
        assertEquals(
            SentPlans.canonical(rows[0].payload()),
            SentPlans.canonical(back.rows[0].payload())
        )
    }

    @Test fun `a coach who has sent nothing writes the backup file they always did`() {
        val json = BackupCodec.export(emptyList(), null, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyMap(), emptyList())
        assertTrue(!JSONObject(json).has("sentPlans"))
    }

    @Test fun `the backup carries sends and reads them back`() {
        val rows = listOf(row("a", "c", 100, "h1"))
        val json = BackupCodec.export(emptyList(), null, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyMap(), rows)
        val restored = BackupCodec.restore(json)
        assertEquals(listOf("a"), restored.sentPlans.map { it.id })
        assertEquals("c", restored.sentPlans[0].clientId)
        assertEquals(100L, restored.sentPlans[0].sentAt)
        assertEquals("h1", restored.sentPlans[0].payloadHash)
    }

    @Test fun `a backup key this codec now models is not written twice`() {
        // sentPlans joined ENVELOPE_KEYS in the same commit that modelled it. A file written by a
        // build that carried it as opaque cargo must not come back out of preservedLibrary as well.
        val file = JSONObject()
            .put("v", 2)
            .put("clients", JSONArray())
            .put("sentPlans", SentPlans.encode(listOf(row("a", "c", 100, "h1"))))
        val restored = BackupCodec.restore(file.toString())
        assertNull(restored.preservedLibrary?.opt("sentPlans"))
        assertEquals(1, restored.sentPlans.size)
    }

    @Test fun `a row that is not a send is skipped rather than failing the restore`() {
        val array = JSONArray()
            .put(JSONObject().put("id", "ok").put("clientId", "c").put("payload", JSONObject().put("v", 1)))
            .put(JSONObject().put("clientId", "c").put("payload", JSONObject()))
            .put(JSONObject().put("id", "no-payload").put("clientId", "c"))
            .put("not an object")
        assertEquals(listOf("ok"), SentPlans.decode(array).map { it.id })
    }

    /* ---------------- the file ---------------- */

    @Test fun `the repository files a send and reads it back`() {
        val store = SentPlanRepository(tmp.root)
        assertEquals(emptyList<SentPlan>(), store.load().rows)
        store.record(row("a", "c", 100, "h1"))
        store.record(row("b", "c", 200, "h2"))
        assertEquals(listOf("b", "a"), store.load().forClient("c").map { it.id })
    }

    @Test fun `an unreadable file is not no sends, and is never written over`() {
        java.io.File(tmp.root, "sent-plans.json").writeText("{ not json")
        val store = SentPlanRepository(tmp.root)
        assertTrue(store.load().isUnreadable)
        // Writing here would save an empty read over the real file.
        var threw = false
        try { store.record(row("a", "c", 100, "h1")) } catch (t: Throwable) { threw = true }
        assertTrue("a write over an unreadable file must not happen silently", threw)
        assertEquals("{ not json", java.io.File(tmp.root, "sent-plans.json").readText())
    }

    @Test fun `removing a client takes their sends and leaves everyone else's`() {
        val store = SentPlanRepository(tmp.root)
        store.record(row("a", "jordan", 100, "h1"))
        store.record(row("b", "sam", 200, "h2"))
        store.removeClient("jordan")
        assertEquals(listOf("b"), store.load().rows.map { it.id })
        // A client with none is not an error.
        store.removeClient("nobody")
        assertEquals(listOf("b"), store.load().rows.map { it.id })
    }

    @Test fun `restoring adds a file's sends and never deletes a newer one`() {
        val store = SentPlanRepository(tmp.root)
        store.record(row("new", "c", 300, "h2"))
        assertEquals(1, store.merge(listOf(row("old", "c", 100, "h1"))))
        assertEquals(listOf("new", "old"), store.load().forClient("c").map { it.id })
        assertEquals("a file written before sent plans changes nothing", 0, store.merge(emptyList()))
        assertEquals(listOf("new", "old"), store.load().forClient("c").map { it.id })
    }

    /* ---------------- Train's Send ---------------- */

    @Test fun `Train's Send has a plan to file, and files the payload it actually sent`() {
        val routine = Routine(id = "r1", name = "Lower A", exercises = listOf(
            RoutineExercise(name = "Back Squat", equipment = "Barbell",
                sets = listOf(PrescribedSet(targetWeightKg = 100.0, targetReps = 5)))
        ))
        val session = ScheduledSession(id = "s1", clientId = "c", dayKey = "2026-10-12", routineId = "r1")
        val send = TrainPlanSend.build(
            clientId = "c", clientName = "Jordan", week = PlanWeek("2026-10-12"),
            sessions = listOf(session), routines = listOf(routine), coachName = "Coach"
        )
        assertTrue(send.isSendable)
        val filed = send.sentPlan("id-1", 1760745600)!!
        assertEquals("c", filed.clientId)
        assertEquals(1760745600L, filed.sentAt)
        // The record is the payload inside the link, not a second encode of the same week.
        assertEquals(SentPlans.hash(filed.payload()), filed.payloadHash)
        assertEquals("2026-10-12", filed.payload().getJSONArray("k").getJSONObject(0).getString("d"))
        assertEquals("Lower A", filed.payload().getJSONArray("w").getJSONObject(0).getString("n"))
    }

    @Test fun `a week with nothing bookable files nothing`() {
        val send = TrainPlanSend.build(
            clientId = "c", clientName = "Jordan", week = PlanWeek("2026-10-12"),
            sessions = emptyList(), routines = emptyList(), coachName = "Coach"
        )
        assertNull(send.sentPlan("id-1", 1))
    }
}
