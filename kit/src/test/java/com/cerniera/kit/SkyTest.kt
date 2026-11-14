package com.cerniera.kit

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leeds (53.80 N, 1.55 W), London clock. Expected values come from pysolar 0.13, an independent
 * implementation (Reda and Andreas' Solar Position Algorithm, not NOAA's), run on 14 Nov 2026:
 * positions read directly, events found by bisecting its geometric altitude to the target and
 * its azimuth to 180° for solar noon. Positions also agree with astral 3 to 0.02°. (astral's own
 * rise and set times differ from both by up to a minute and a half, so they are not used.)
 * Times must match to within 30 s and positions to within 0.1°.
 */
class SkyTest {
    private val leeds = Sky.Place.LEEDS
    private val london = ZoneId.of("Europe/London")

    private fun at(date: String, time: String) = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(london).toInstant()

    private fun near(expected: Instant, actual: Instant?, what: String, seconds: Long = 30) {
        assertNotNull(what, actual)
        val off = abs(Duration.between(expected, actual).seconds)
        assertTrue("$what off by ${off}s (expected $expected, got $actual)", off <= seconds)
    }

    private fun checkDay(date: String, dawn: String, rise: String, goldEnd: String, noon: String,
                         goldStart: String, set: String, dusk: String, noonAlt: Double) {
        val d = Sky.day(leeds, LocalDate.parse(date), london)
        near(at(date, dawn), d.civilDawn, "$date civil dawn")
        near(at(date, rise), d.sunrise, "$date sunrise")
        near(at(date, goldEnd), d.goldenMorningEnd, "$date golden morning end")
        near(at(date, noon), d.solarNoon, "$date solar noon")
        near(at(date, goldStart), d.goldenEveningStart, "$date golden evening start")
        near(at(date, set), d.sunset, "$date sunset")
        near(at(date, dusk), d.civilDusk, "$date civil dusk")
        assertEquals("$date noon altitude", noonAlt, d.noonAltitude, 0.05)
    }

    @Test fun marchEquinox() = checkDay("2026-03-20", "05:33:46", "06:08:50", "06:55:11", "12:13:36", "17:33:00", "18:19:28", "18:54:40", 36.18)
    @Test fun juneSolstice() = checkDay("2026-06-21", "03:40:58", "04:34:55", "05:33:46", "13:07:59", "20:42:12", "21:41:03", "22:35:00", 59.65)
    @Test fun septemberEquinox() = checkDay("2026-09-23", "06:18:21", "06:53:31", "07:40:01", "12:58:33", "18:16:08", "19:02:29", "19:37:32", 36.03)
    @Test fun decemberSolstice() = checkDay("2026-12-21", "07:38:20", "08:21:55", "09:31:10", "12:04:14", "14:37:18", "15:46:33", "16:30:09", 12.83)
    @Test fun midNovember() = checkDay("2026-11-13", "06:50:06", "07:29:13", "08:26:39", "11:50:27", "15:13:39", "16:11:03", "16:50:09", 18.21)

    /** The clocks go forward at 01:00 GMT on 29 Mar 2026: every time that day is on BST. */
    @Test fun clocksForward() = checkDay("2026-03-29", "06:11:12", "06:46:45", "07:33:02", "13:10:54", "18:49:46", "19:36:12", "20:11:54", 39.71)

    /** The clocks go back at 02:00 BST on 25 Oct 2026: every event that day is on GMT. */
    @Test fun clocksBack() = checkDay("2026-10-25", "06:16:05", "06:52:34", "07:43:42", "11:50:15", "15:56:01", "16:47:06", "17:23:31", 24.04)

    @Test fun positions() {
        fun check(utc: String, az: Double, alt: Double) {
            val p = Sky.sun(leeds, Instant.parse(utc))
            assertEquals("$utc azimuth", az, p.azimuth, 0.1)
            assertEquals("$utc altitude", alt, p.altitude, 0.1)
        }
        check("2026-06-21T12:00:00Z", 176.37, 59.61)
        check("2026-12-21T12:00:00Z", 179.00, 12.83)
        check("2026-11-13T09:30:00Z", 145.98, 12.19)
        check("2026-11-13T14:45:00Z", 221.62, 9.08)
    }

    /** At solar noon the sun is due south: a meridian line's shadow lies on the meridian. */
    @Test fun noonIsDueSouth() {
        for (date in listOf("2026-03-20", "2026-06-21", "2026-11-13", "2026-12-21")) {
            val d = Sky.day(leeds, LocalDate.parse(date), london)
            assertEquals("$date", 180.0, Sky.sun(leeds, d.solarNoon).azimuth, 0.5)
        }
    }

    @Test fun nightIsDown() {
        assertTrue(!Sky.sun(leeds, Instant.parse("2026-11-13T23:00:00Z")).up)
        assertTrue(Sky.sun(leeds, Instant.parse("2026-11-13T12:00:00Z")).up)
    }

    /** Tromsø in midsummer: no sunset, so the day's evening events are null, not invented. */
    @Test fun midnightSun() {
        val d = Sky.day(69.65, 18.96, LocalDate.parse("2026-06-21"), ZoneId.of("Europe/Oslo"))
        assertNull(d.sunrise); assertNull(d.sunset); assertNull(d.civilDusk)
        assertTrue(d.noonAltitude > 40)
    }

    /** Tromsø in midwinter: the sun never clears the horizon. */
    @Test fun polarNight() {
        val d = Sky.day(69.65, 18.96, LocalDate.parse("2026-12-21"), ZoneId.of("Europe/Oslo"))
        assertNull(d.sunrise); assertNull(d.goldenMorningEnd)
    }

    @Test fun moon() {
        // astral's moon.phase puts November 2026's new moon on the 9th and its full moon late on the
        // 24th. The mean month promises only to within about a day, so the thresholds allow for that.
        assertTrue(Sky.moon(Instant.parse("2026-11-24T18:00:00Z")).illumination > 0.94)
        assertTrue(Sky.moon(Instant.parse("2026-11-09T12:00:00Z")).illumination < 0.06)
        assertTrue(Sky.moon(Instant.parse("2026-11-13T22:00:00Z")).waxing)
    }
}
