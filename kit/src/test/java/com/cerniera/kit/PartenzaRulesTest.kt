package com.cerniera.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Partenza's rules, and Senso's leaving soon timed to leave-by. */
class PartenzaRulesTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 26, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private val interview = SensoEvent("Interview", at(9), at(10), "1 Park Row, Leeds")

    /** A 34-minute drive, 6 of it traffic, 10-minute buffer: leave 08:16. */
    private fun dep(checked: Long = at(8), driveMin: Int = 34) = Departure("Interview", at(9), "1 Park Row, Leeds",
        PartenzaRules.leaveBy(at(9), driveMin * 60, 10), driveMin * 60, 6 * 60, 21_000, 10, checked, "here")

    @Test fun leaveByIsStartLessDriveLessBuffer() {
        assertEquals(at(8, 16), PartenzaRules.leaveBy(at(9), 34 * 60, 10))
        assertEquals(at(8, 50), PartenzaRules.arriveAt(at(9), 10))
    }

    @Test fun onlyTimedEventsSomewhereToDriveTo() {
        val now = at(7)
        assertTrue(PartenzaRules.eligible(interview, now))
        assertFalse(PartenzaRules.eligible(interview.copy(where = "Microsoft Teams Meeting"), now))
        assertFalse(PartenzaRules.eligible(interview.copy(where = null), now))
        assertFalse(PartenzaRules.eligible(interview.copy(allDay = true), now))
        assertFalse(PartenzaRules.eligible(interview, at(9, 5)))
        assertFalse(PartenzaRules.eligible(interview.copy(start = now + 25 * 60 * min), now))
    }

    @Test fun nextSkipsCallsForTheFirstPlace() {
        val call = SensoEvent("Stand-up", at(8), at(8, 15), "https://teams.microsoft.com/l/abc")
        assertEquals("Interview", PartenzaRules.next(listOf(call, interview), at(7))?.title)
    }

    @Test fun watchBeginsTwoHoursOutOrEarlierForALongDrive() {
        assertEquals(at(7), PartenzaRules.watchFrom(at(9), null, 10))
        assertEquals(at(7), PartenzaRules.watchFrom(at(9), 34 * 60, 10))
        // Two and a half hours to London: watch from 05:20 (an hour before leaving at 06:20)
        assertEquals(at(5, 20), PartenzaRules.watchFrom(at(9), 150 * 60, 10))
    }

    @Test fun reChecksTightenTowardsLeaveBy() {
        val by = at(8, 16)
        assertEquals(at(7), PartenzaRules.nextCheck(at(6), at(9), null))
        assertEquals(at(7, 5), PartenzaRules.nextCheck(at(7), at(9), null))
        // 07:00: next is 90 min before leave-by (06:46 is past), so 07:16
        assertEquals(at(7, 16), PartenzaRules.nextCheck(at(7), at(9), by))
        assertEquals(at(7, 31), PartenzaRules.nextCheck(at(7, 16), at(9), by))
        assertEquals(at(8, 6), PartenzaRules.nextCheck(at(8, 1), at(9), by))
        assertEquals(at(8, 11), PartenzaRules.nextCheck(at(8, 6), at(9), by))
        assertEquals(at(8, 16), PartenzaRules.nextCheck(at(8, 11), at(9), by))
        // Past leave-by: every five minutes until it starts, then nothing
        assertEquals(at(8, 21), PartenzaRules.nextCheck(at(8, 16), at(9), by))
        assertNull(PartenzaRules.nextCheck(at(8, 57), at(9), by))
        assertNull(PartenzaRules.nextCheck(at(9), at(9), by))
    }

    @Test fun plannedFarOutLiveNear() {
        assertEquals(PartenzaRules.Mode.ARRIVE_AT, PartenzaRules.mode(at(7), at(9), dep(), 10))
        assertEquals(PartenzaRules.Mode.DRIVE_NOW, PartenzaRules.mode(at(7, 40), at(9), dep(), 10))
        // No estimate yet: guessed from a 45-minute drive (leave 08:05)
        assertEquals(PartenzaRules.Mode.ARRIVE_AT, PartenzaRules.mode(at(7), at(9), null, 10))
        assertEquals(PartenzaRules.Mode.DRIVE_NOW, PartenzaRules.mode(at(7, 30), at(9), null, 10))
    }

    @Test fun phases() {
        val d = dep()
        assertEquals(PartenzaRules.Phase.AHEAD, PartenzaRules.phase(at(7, 30), d))
        assertEquals(PartenzaRules.Phase.SOON, PartenzaRules.phase(at(7, 45), d))
        assertEquals(PartenzaRules.Phase.CONCENTRATO, PartenzaRules.phase(at(8, 10), d))
        assertEquals(PartenzaRules.Phase.GO, PartenzaRules.phase(at(8, 20), d))
        assertEquals(PartenzaRules.Phase.GONE, PartenzaRules.phase(at(9), d))
    }

    @Test fun anEstimateSpeaksOnlyForItsEventWhileFresh() {
        assertEquals(dep(), PartenzaRules.current(dep(), interview, at(8, 30)))
        assertNull(PartenzaRules.current(dep(), interview.copy(start = at(11)), at(8)))
        assertNull(PartenzaRules.current(dep(checked = at(7)), interview, at(8, 1)))
        assertNull(PartenzaRules.current(dep(), null, at(8)))
    }

    @Test fun tomTomTimes() {
        assertEquals(at(8, 16), PartenzaRules.time("2026-11-26T08:16:00Z"))
        assertEquals(at(8, 16), PartenzaRules.time("2026-11-26T09:16:00+01:00"))
        assertNull(PartenzaRules.time("not a time"))
        assertEquals("2026-11-26T08:50:00Z", PartenzaRules.iso(at(8, 50), zone))
    }

    @Test fun lines() {
        assertEquals("Leave 08:16 · Interview 09:00 · 34 min drive", PartenzaRules.line(dep(), zone))
        assertEquals("Interview 09:00 · 1 Park Row, Leeds: leave 08:16 · 34 min, 6 of it traffic · 21.0 km · 10 min buffer · from here · driven now",
            PartenzaRules.logLine(dep(), PartenzaRules.Mode.DRIVE_NOW, zone))
    }

    // ---- Senso's leaving soon, timed to leave-by -----------------------------------------------

    private fun leaving(now: Long, leave: Departure? = dep()) = Rules.leaving(Signals(event = interview, leave = leave), now, zone)

    @Test fun silentUntil45MinutesBeforeLeaveBy() {
        // Leave-by 08:16: leaving soon begins at 07:31, where the old rule waited until 08:15
        assertNull(leaving(at(7, 30)))
        val m = leaving(at(7, 40))!!
        assertTrue(m.confidence >= Rules.THRESHOLD)
        assertEquals("Leave 08:16 · Interview 09:00 · 34 min drive", m.why)
    }

    @Test fun surestOnceLeaveByHasCome() {
        val m = leaving(at(8, 20))!!
        assertEquals(0.95f, m.confidence, 0.001f)
        assertEquals("Leave now · Interview 09:00 · 34 min drive", m.why)
        assertNull(leaving(at(9)))
    }

    @Test fun aShortDriveSpeaksLaterThanTheOldRule() {
        // Ten minutes' drive: leave 08:40, so leaving soon waits until 07:55
        val short = dep(checked = at(8), driveMin = 10)
        assertNull(leaving(at(7, 50), short))
        assertTrue(leaving(at(8), short)!!.confidence >= Rules.THRESHOLD)
    }

    @Test fun fallsBackWithoutAFreshEstimate() {
        val old = Rules.leaving(Signals(event = interview), at(8, 30), zone)!!
        val stale = leaving(at(8, 30), dep(checked = at(7)))!!
        assertEquals(old.confidence, stale.confidence, 0.001f)
        assertEquals("Interview at 09:00 · 1 Park Row, Leeds", stale.why)
    }
}
