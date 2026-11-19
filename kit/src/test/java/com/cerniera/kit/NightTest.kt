package com.cerniera.kit

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notturno's sky over Leeds (53.80 N, 1.55 W). Expected values come from PyEphem 4.2.1 (VSOP87 and
 * ELP/MPP02, a full-precision independent source), run on 19 Nov 2026 at sea level, 1010 hPa and
 * 15 °C, so its altitudes include refraction as ours do. A line-by-line Python port of Night.kt
 * agreed with these to 0.07° at worst (the moon); the tests allow 0.15° on the sky, 0.3 in
 * magnitude (Schlyter's Venus and Saturn run a little bright), 0.01 in the moon's lit share and
 * three minutes on setting times. Only bodies more than 3° up are compared.
 */
class NightTest {
    private val leeds = Sky.Place.LEEDS

    private data class Case(val utc: String, val body: String, val az: Double, val alt: Double, val third: Double)

    private val cases = listOf(
        Case("2026-11-19T19:00", "Saturn", 148.06, 32.80, 0.64),
        Case("2026-11-19T19:00", "Moon", 166.11, 34.52, 0.725),
        Case("2026-11-19T23:30", "Mars", 73.22, 5.57, 0.66),
        Case("2026-11-19T23:30", "Jupiter", 75.03, 5.95, -1.98),
        Case("2026-11-19T23:30", "Saturn", 227.84, 27.18, 0.64),
        Case("2026-11-19T23:30", "Moon", 240.37, 20.24, 0.742),
        Case("2026-12-21T06:00", "Venus", 136.29, 13.88, -4.36),
        Case("2026-12-21T06:00", "Mars", 204.72, 45.25, 0.12),
        Case("2026-12-21T06:00", "Jupiter", 220.41, 43.64, -2.19),
        Case("2027-03-20T21:00", "Mars", 158.16, 52.39, -0.64),
        Case("2027-03-20T21:00", "Jupiter", 165.88, 52.06, -2.26),
        Case("2027-03-20T21:00", "Moon", 143.31, 36.62, 0.967),
        Case("2026-08-10T02:00", "Mars", 66.40, 12.24, 1.29),
        Case("2026-08-10T02:00", "Saturn", 145.98, 34.84, 0.58),
        Case("2026-08-10T02:00", "Moon", 54.34, 7.08, 0.098),
        Case("2027-01-15T17:30", "Saturn", 188.33, 37.33, 0.87),
        Case("2027-01-15T17:30", "Moon", 173.49, 49.02, 0.488),
    )

    private fun onSky(c: Case, az: Double, alt: Double) {
        val dAz = ((az - c.az + 540) % 360 - 180) * cos(Math.toRadians(c.alt))
        assertTrue("${c.utc} ${c.body} azimuth $az, expected ${c.az}", abs(dAz) <= 0.15)
        assertEquals("${c.utc} ${c.body} altitude", c.alt, alt, 0.15)
    }

    @Test fun planetsAndMoonMatchPyEphem() {
        for (c in cases) {
            val at = Instant.parse(c.utc + ":00Z")
            if (c.body == "Moon") {
                val m = Sky.moonAt(leeds, at)
                onSky(c, m.azimuth, m.altitude)
                assertEquals("${c.utc} moon lit share", c.third, m.illumination, 0.01)
            } else {
                val p = Sky.planets(leeds, at).first { it.name == c.body }
                onSky(c, p.azimuth, p.altitude)
                assertEquals("${c.utc} ${c.body} magnitude", c.third, p.magnitude, 0.3)
            }
        }
    }

    @Test fun planetsComeBrightestFirst() {
        val list = Sky.planets(leeds, Instant.parse("2026-12-21T06:00:00Z"))
        assertEquals(5, list.size)
        assertEquals("Venus", list.first().name)
        assertTrue(list.zipWithNext().all { (a, b) -> a.magnitude <= b.magnitude })
    }

    @Test fun tonightsVisibleOnes() {
        // 19 Nov 2026, 23:30 UT: Jupiter and Mars just risen in the east, Saturn in the south-west; Venus and Mercury down
        val seen = Sky.planets(leeds, Instant.parse("2026-11-19T23:30:00Z")).filter { it.visible }.map { it.name }.toSet()
        assertEquals(setOf("Jupiter", "Mars", "Saturn"), seen)
    }

    @Test fun moonsLitSideFacesTheSun() {
        // A waxing evening moon in the south is lit on its right (the sun has set in the west)
        val evening = Sky.moonAt(leeds, Instant.parse("2026-11-19T19:00:00Z"))
        assertTrue(evening.waxing)
        assertTrue("limb ${evening.brightLimb}", evening.brightLimb in 70.0..150.0)
        // A waning morning moon in the east is lit on its left (the sun is below the north-east horizon)
        val morning = Sky.moonAt(leeds, Instant.parse("2026-08-10T02:00:00Z"))
        assertTrue(!morning.waxing)
        assertTrue("limb ${morning.brightLimb}", morning.brightLimb in 190.0..270.0)
    }

    @Test fun settingTimes() {
        val from = Instant.parse("2026-11-19T19:00:00Z")
        fun near(expected: String, actual: Instant?, what: String) {
            assertNotNull(what, actual)
            val off = abs(Duration.between(Instant.parse(expected), actual).seconds)
            assertTrue("$what off by ${off}s", off <= 180)
        }
        near("2026-11-20T02:06:32Z", Sky.nextSet("Moon", leeds, from), "moonset")
        near("2026-11-20T02:52:58Z", Sky.nextSet("Saturn", leeds, from), "Saturn sets")
    }
}
