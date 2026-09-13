package com.dugcanlift.coach.data
import org.junit.Assert.*
import org.junit.Test

class StatsTest {
    private fun set(w: Double?, r: Int?, warm: Boolean = false) = ExerciseSet("Back Squat", "Barbell", w, r, null, null, null, warm)
    private fun day(key: String, sets: List<ExerciseSet> = emptyList(), food: List<ClientFoodEntry> = emptyList(), ft: List<Double>? = null, bw: Double? = null) =
        TrainingDay(key, null, null, bw, null, ft?.get(0), ft?.get(1), ft?.get(2), ft?.get(3), ft?.get(4), sets, food)

    @Test fun `volume excludes warmups and treats nulls as zero`() =
        assertEquals(1125.0, Stats.dayVolume(day("d", listOf(set(135.0, 5, warm = true), set(225.0, 5), set(null, 10)))), 0.0)
    @Test fun `e1rm is Epley with no rep cap and null for warmups or missing data`() {
        assertEquals(225.0 * (1 + 5 / 30.0), Stats.e1rm(set(225.0, 5))!!, 1e-9)
        assertEquals(100.0 * (1 + 20 / 30.0), Stats.e1rm(set(100.0, 20))!!, 1e-9)   // 20 reps still estimates (Doug, 2026-09-13)
        assertNull(Stats.e1rm(set(225.0, 5, warm = true))); assertNull(Stats.e1rm(set(null, 5))); assertNull(Stats.e1rm(set(225.0, 0)))
    }
    // A zero weightLb is a bodyweight movement or a mis-entry, not a legitimate 0 lb lift; an Epley
    // estimate of 0.0 would poison a trend chart, so this must be null, not merely non-null-checked.
    @Test fun `e1rm is null for a zero-weight set even with real reps, not merely non-null`() =
        assertNull(Stats.e1rm(set(0.0, 5)))
    @Test fun `fuel prefers ft and falls back to itemized food and is null when neither`() {
        assertEquals(2410.0, Stats.fuel(day("d", ft = listOf(2410.0, 188.0, 71.0, 230.0, 33.0)))!!.calories, 0.0)
        assertEquals(380.0, Stats.fuel(day("d", food = listOf(ClientFoodEntry("Oats", 2.0, 380.0, 13.0, 6.6, 68.0, 10.0, 0))))!!.calories, 0.0)
        assertNull(Stats.fuel(day("d")))
    }
    @Test fun `weekly buckets align to the end key newest last and average only logged days`() {
        val c = Client("a", "Doug", "lb", null, 0, Goal(2400, 190, 70, 220, 34), listOf(
            day("2026-09-12", listOf(set(225.0, 5)), ft = listOf(2400.0, 190.0, 70.0, 220.0, 34.0)),
            day("2026-09-05", listOf(set(200.0, 5)), ft = listOf(2000.0, 150.0, 60.0, 200.0, 30.0))))
        val weeks = Stats.weeklyBuckets(c, 2, endKey = "2026-09-13")
        assertEquals(listOf("2026-09-06", "2026-09-13"), weeks.map { it.endKey })
        assertEquals(2400, weeks[1].kcalAvg); assertEquals(1.0, weeks[1].proteinHitRate!!, 0.0); assertEquals(0.0, weeks[0].proteinHitRate!!, 0.0)
        assertEquals(1, weeks[1].sessions); assertEquals(1125.0, weeks[1].volume, 0.0)
    }
    @Test fun `per-lift e1rm series is keyed by name plus equipment in day order`() {
        val c = Client("a", "Doug", "lb", null, 0, null, listOf(day("2026-09-01", listOf(set(200.0, 5))), day("2026-09-08", listOf(set(225.0, 5)))))
        assertEquals(listOf("2026-09-01", "2026-09-08"), Stats.perLiftE1rm(c).getValue("Back Squat|Barbell").map { it.first })
    }
    // The wire format's exercise dictionary is "name|equipment" precisely because a cable pulldown and
    // a machine pulldown are not the same lift; perLiftE1rm must key the same way or two same-named
    // lifts on different equipment silently merge into one misleading series.
    @Test fun `two same-named lifts on different equipment stay separate series`() {
        val barbellRow = ExerciseSet("Row", "Barbell", 135.0, 8, null, null, null, false)
        val cableRow = ExerciseSet("Row", "Cable", 100.0, 10, null, null, null, false)
        val c = Client("a", "Doug", "lb", null, 0, null, listOf(day("2026-09-01", listOf(barbellRow, cableRow))))
        val series = Stats.perLiftE1rm(c)
        assertEquals(setOf("Row|Barbell", "Row|Cable"), series.keys)
        assertEquals(Stats.e1rm(barbellRow), series.getValue("Row|Barbell").single().second)
        assertEquals(Stats.e1rm(cableRow), series.getValue("Row|Cable").single().second)
    }

    // --- Edges the brief leaves open, needed by later screens ---

    @Test fun `a client with no days at all yields empty series and null-averaged buckets rather than crashing`() {
        val c = Client("a", "Doug", "lb", null, 0, Goal(2400, 190, 70, 220, 34), emptyList())
        val weeks = Stats.weeklyBuckets(c, 2, endKey = "2026-09-13")
        assertEquals(2, weeks.size)
        weeks.forEach {
            assertEquals(0, it.sessions); assertEquals(0, it.sets); assertEquals(0.0, it.volume, 0.0)
            assertNull(it.kcalAvg); assertNull(it.proteinAvg); assertNull(it.proteinHitRate); assertNull(it.stepsAvg)
        }
        assertTrue(Stats.bodyweightSeries(c).isEmpty())
        assertTrue(Stats.perLiftE1rm(c).isEmpty())
    }

    @Test fun `a week with training but no logged food has null averages, not zero`() {
        val c = Client("a", "Doug", "lb", null, 0, Goal(2400, 190, 70, 220, 34), listOf(
            day("2026-09-12", listOf(set(225.0, 5)))))
        val week = Stats.weeklyBuckets(c, 1, endKey = "2026-09-13").single()
        assertEquals(1, week.sessions); assertEquals(1125.0, week.volume, 0.0)
        assertNull(week.kcalAvg); assertNull(week.proteinAvg); assertNull(week.proteinHitRate); assertNull(week.stepsAvg)
    }

    @Test fun `a set with reps but no weight contributes nothing to volume and does not crash the e1rm estimate`() {
        val s = set(null, 20)
        assertEquals(0.0, Stats.dayVolume(day("d", listOf(s))), 0.0)
        assertNull(Stats.e1rm(s))
    }

    private fun week(rate: Double?) = WeekStats("2026-09-13", 0, 0, 0.0, null, null, rate, null)

    // --- avgProteinHitRate: the same null-exclusion averaging rule as every other average here ---

    @Test fun `avgProteinHitRate is null when every week has nothing to average`() =
        assertNull(Stats.avgProteinHitRate(listOf(week(null), week(null))))

    @Test fun `avgProteinHitRate is null for an empty list of weeks`() =
        assertNull(Stats.avgProteinHitRate(emptyList()))

    @Test fun `avgProteinHitRate excludes null weeks rather than averaging them in as zero`() =
        // (1.0 + 0.5) / 2 = 0.75 -- the null week must not count as a third entry or a zero.
        assertEquals(0.75, Stats.avgProteinHitRate(listOf(week(1.0), week(null), week(0.5)))!!, 1e-9)

    @Test fun `avgProteinHitRate averages every week when none are null`() =
        assertEquals(0.6, Stats.avgProteinHitRate(listOf(week(0.4), week(0.6), week(0.8)))!!, 1e-9)

    @Test fun `bodyweight series skips days with no bodyweight and stays in chronological order`() {
        val c = Client("a", "Doug", "lb", null, 0, null, listOf(
            day("2026-09-05", bw = 180.0),
            day("2026-09-10"),
            day("2026-09-01", bw = 178.0)))
        assertEquals(listOf("2026-09-01" to 178.0, "2026-09-05" to 180.0), Stats.bodyweightSeries(c))
    }

    // --- Round 6 [C-2]: weeklyBuckets calls the throwing daysBetween on every stored day key, from
    // inside ClientScreen's composition. One bad key must be skipped, not fatal.
    @Test fun `a day with an unparseable key is skipped rather than throwing out of the bucket walk`() {
        val c = Client("a", "A", "lb", null, 0, null, listOf(day("2026-9-3", listOf(set(225.0, 5))), day("2026-09-13", listOf(set(225.0, 5)))))
        val weeks = Stats.weeklyBuckets(c, 1, "2026-09-13")
        assertEquals(1, weeks.single().sessions)
        assertEquals(1125.0, weeks.single().volume, 0.0)
    }

    // --- historySpanWeeks: chart-all-history -- the selectable 4/8/12 window is gone, replaced by
    // a span computed from the client's own data (oldest day through today, rounded up to whole
    // weeks) so ClientScreen always charts the client's entire history.

    @Test fun `history span covers a known number of weeks, rounded up`() {
        // Oldest day is exactly 14 days before today -- the third 7-day block back, so this must
        // round up to 3 whole weeks (weeks 1-2 don't reach a day that old).
        val c = Client("a", "Doug", "lb", null, 0, null, listOf(
            day("2026-08-30", listOf(set(200.0, 5))),
            day("2026-09-13", listOf(set(225.0, 5)))))
        assertEquals(3, Stats.historySpanWeeks(c, "2026-09-13"))
        assertEquals(3, Stats.weeklyBuckets(c, Stats.historySpanWeeks(c, "2026-09-13"), "2026-09-13").size)
    }

    @Test fun `a client with a single day produces exactly one bucket`() {
        val c = Client("a", "Doug", "lb", null, 0, null, listOf(day("2026-09-13", listOf(set(225.0, 5)))))
        assertEquals(1, Stats.historySpanWeeks(c, "2026-09-13"))
        val weeks = Stats.weeklyBuckets(c, Stats.historySpanWeeks(c, "2026-09-13"), "2026-09-13")
        assertEquals(1, weeks.size)
        assertEquals(1, weeks.single().sessions)
    }

    @Test fun `a client with no days produces no buckets at all, not a single empty one`() {
        val c = Client("a", "Doug", "lb", null, 0, null, emptyList())
        assertEquals(0, Stats.historySpanWeeks(c, "2026-09-13"))
        assertTrue(Stats.weeklyBuckets(c, Stats.historySpanWeeks(c, "2026-09-13"), "2026-09-13").isEmpty())
    }

    // A client restored from an old backup could hold a day key that's syntactically valid --
    // requireDayKey lets it through -- but absurdly far in the past. Without a cap this would ask
    // weeklyBuckets for tens of thousands of mostly-empty buckets.
    @Test fun `an absurdly old but syntactically valid day key is capped, not taken literally`() {
        val c = Client("a", "Doug", "lb", null, 0, null, listOf(day("0001-01-01", listOf(set(200.0, 5)))))
        val span = Stats.historySpanWeeks(c, "2026-09-13")
        assertEquals(Stats.MAX_HISTORY_SPAN_WEEKS, span)
        // Must not attempt to allocate/compute anywhere near the literal ~739,000 weeks that date
        // implies -- weeklyBuckets must complete and return exactly the capped count.
        assertEquals(Stats.MAX_HISTORY_SPAN_WEEKS, Stats.weeklyBuckets(c, span, "2026-09-13").size)
    }

    // --- weeklyBuckets grouping rewrite: re-filtering client.days once per bucket is quadratic
    // once historySpanWeeks can ask for a hundred-plus buckets. This fixture pins the exact
    // per-bucket values across three buckets with mixed sessions/food/steps -- and one unparseable
    // key thrown in -- so the single-pass grouping rewrite is provably behaviour-identical.
    @Test fun `fixed multi-week fixture produces unchanged bucket values after the grouping rewrite`() {
        fun fullDay(key: String, sets: List<ExerciseSet>, steps: Long?, ft: List<Double>?) =
            TrainingDay(key, null, null, null, steps, ft?.get(0), ft?.get(1), ft?.get(2), ft?.get(3), ft?.get(4), sets, emptyList())

        val c = Client("a", "Doug", "lb", null, 0, Goal(2400, 190, 70, 220, 34), listOf(
            fullDay("2026-09-08", listOf(set(200.0, 5)), 7000, listOf(2000.0, 150.0, 60.0, 200.0, 30.0)),
            fullDay("2026-09-13", listOf(set(225.0, 5)), 8000, listOf(2500.0, 200.0, 70.0, 220.0, 34.0)),
            fullDay("2026-09-18", emptyList(), 9000, listOf(2200.0, 160.0, 65.0, 210.0, 32.0)),
            fullDay("2026-09-25", listOf(set(250.0, 3)), null, null),
            fullDay("2026-9-1", listOf(set(999.0, 99)), 1, listOf(1.0, 1.0, 1.0, 1.0, 1.0)) // unparseable -- must be skipped
        ))

        val weeks = Stats.weeklyBuckets(c, 3, "2026-09-27")
        assertEquals(listOf("2026-09-13", "2026-09-20", "2026-09-27"), weeks.map { it.endKey })

        val w0 = weeks[0] // 2026-09-07..09-13: the two earliest real days
        assertEquals(2, w0.sessions); assertEquals(2, w0.sets); assertEquals(2125.0, w0.volume, 0.0)
        assertEquals(2250, w0.kcalAvg); assertEquals(175, w0.proteinAvg)
        assertEquals(0.5, w0.proteinHitRate!!, 1e-9) // only the 200g day clears 0.95*190=180.5
        assertEquals(7500, w0.stepsAvg)

        val w1 = weeks[1] // 2026-09-14..09-20: a rest day with food logged but no sets
        assertEquals(0, w1.sessions); assertEquals(0, w1.sets); assertEquals(0.0, w1.volume, 0.0)
        assertEquals(2200, w1.kcalAvg); assertEquals(160, w1.proteinAvg)
        assertEquals(0.0, w1.proteinHitRate!!, 0.0) // 160g doesn't clear 180.5
        assertEquals(9000, w1.stepsAvg)

        val w2 = weeks[2] // 2026-09-21..09-27: trained, nothing logged for food or steps
        assertEquals(1, w2.sessions); assertEquals(1, w2.sets); assertEquals(750.0, w2.volume, 0.0)
        assertNull(w2.kcalAvg); assertNull(w2.proteinAvg); assertNull(w2.proteinHitRate); assertNull(w2.stepsAvg)
    }
}
