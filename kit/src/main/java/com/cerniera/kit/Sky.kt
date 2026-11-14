package com.cerniera.kit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The family's one sky. Solco's disc, Sipario's light, Sveglia's dawn and Meridiana's shadow all
 * read the sun and moon from here, so wherever the phone is they put the sun in the same place.
 *
 * Pure arithmetic: no network, no permissions, no Android. The caller supplies the place (each
 * app reads the phone's location and falls back to [Place.LEEDS]).
 *
 * The sun follows NOAA's solar calculator equations (after Meeus). Positions are good to a small
 * fraction of a degree and rise and set times to about a minute at UK latitudes, far finer than
 * any disc or shadow can show. Altitude is apparent: it includes NOAA's allowance for
 * atmospheric refraction, which lifts the sun about half a degree at the horizon.
 */
object Sky {

    /** Where the sky is seen from. Latitude north and longitude east are positive. */
    data class Place(val name: String, val lat: Double, val lon: Double) {
        companion object {
            /** The fallback before the phone has ever given a location. */
            val LEEDS = Place("Leeds", 53.80, -1.55)
        }
    }

    /**
     * The sun at one moment. [azimuth] in degrees clockwise from true north (90 east, 180 south,
     * 270 west). [altitude] in degrees above the horizon, negative below it.
     */
    data class SunPosition(val azimuth: Double, val altitude: Double) {
        val up get() = altitude > HORIZON
    }

    /**
     * One local day's sun. Any event the sun never reaches that day (no sunset in a polar summer,
     * no golden hour in a polar winter) is null.
     *
     * - [civilDawn], [civilDusk]: the sun 6° below the horizon. First and last usable light.
     * - [sunrise], [sunset]: the sun's upper edge on the horizon (−0.833°, refraction included).
     * - [goldenMorningEnd], [goldenEveningStart]: the sun 6° above the horizon. Golden hour runs
     *   from sunrise to [goldenMorningEnd], and from [goldenEveningStart] to sunset.
     * - [solarNoon]: the sun due south (due north south of the equator's subsolar point), the
     *   moment a meridian line's shadow lies on the meridian.
     */
    data class SunDay(
        val civilDawn: Instant?, val sunrise: Instant?, val goldenMorningEnd: Instant?,
        val solarNoon: Instant,
        val goldenEveningStart: Instant?, val sunset: Instant?, val civilDusk: Instant?,
        /** Sun's altitude at solar noon, degrees (apparent). */
        val noonAltitude: Double,
    )

    /** [fraction] 0 new, 0.5 full, cycling to 1. [illumination] 0 to 1, the lit share of the disc. */
    data class MoonPhase(val fraction: Double, val illumination: Double) {
        val waxing get() = fraction < 0.5
        val name: String get() = when {
            fraction < 0.03 || fraction > 0.97 -> "New moon"
            fraction < 0.22 -> "Waxing crescent"
            fraction < 0.28 -> "First quarter"
            fraction < 0.47 -> "Waxing gibbous"
            fraction < 0.53 -> "Full moon"
            fraction < 0.72 -> "Waning gibbous"
            fraction < 0.78 -> "Last quarter"
            else -> "Waning crescent"
        }
        /** For example "Waxing gibbous, 82% lit". */
        val label get() = "$name, ${(illumination * 100).roundToInt()}% lit"
    }

    /** Sunrise and sunset: the upper limb on the horizon, refraction included. */
    const val HORIZON = -0.833
    const val CIVIL = -6.0
    const val GOLDEN = 6.0

    // ---- Sun --------------------------------------------------------------------------------

    fun sun(place: Place, at: Instant = Instant.now()) = sun(place.lat, place.lon, at)

    fun sun(lat: Double, lon: Double, at: Instant = Instant.now()): SunPosition {
        val s = solar(julianCentury(at))
        val utcMinutes = minutesOfUtcDay(at)
        val trueSolar = ((utcMinutes + s.eqTime + 4 * lon) % 1440 + 1440) % 1440
        val hourAngle = rad(trueSolar / 4 - 180)
        val la = rad(lat)
        val zenith = acos((sin(la) * sin(s.decl) + cos(la) * cos(s.decl) * cos(hourAngle)).coerceIn(-1.0, 1.0))
        val az = deg(atan2(sin(hourAngle), cos(hourAngle) * sin(la) - tan(s.decl) * cos(la))) + 180
        val alt = 90 - deg(zenith)
        return SunPosition(norm(az), alt + refraction(alt))
    }

    fun day(place: Place, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = day(place.lat, place.lon, date, zone)

    /** The sun's day for [date] as the clock in [zone] counts it. */
    fun day(lat: Double, lon: Double, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SunDay {
        // Start at the local clock's noon and refine; each step re-reads the sun at the new time.
        var noon = date.atTime(12, 0).atZone(zone).toInstant()
        repeat(3) { noon = solarNoonNear(noon, lon) }
        fun event(altitude: Double, morning: Boolean): Instant? {
            var t = noon
            repeat(4) {
                val s = solar(julianCentury(t))
                val h = hourAngle(lat, s.decl, altitude) ?: return null
                val noonHere = solarNoonNear(t, lon)
                t = noonHere.plusMillis(((if (morning) -h else h) * 4 * 60_000).toLong())
            }
            return t
        }
        val noonAlt = sun(lat, lon, noon).altitude
        return SunDay(
            civilDawn = event(CIVIL, true), sunrise = event(HORIZON, true), goldenMorningEnd = event(GOLDEN, true),
            solarNoon = noon,
            goldenEveningStart = event(GOLDEN, false), sunset = event(HORIZON, false), civilDusk = event(CIVIL, false),
            noonAltitude = noonAlt,
        )
    }

    // ---- Moon -------------------------------------------------------------------------------

    private const val SYNODIC = 29.530588853
    /** A known new moon: 6 Jan 2000, 18:14 UTC. */
    private const val NEW_MOON_MS = 947182440000L

    /**
     * The moon's phase from the mean synodic month (moved from Solco's Luna). Good to within
     * about a day of the true phase, which no disc this size can show.
     */
    fun moon(at: Instant = Instant.now()): MoonPhase {
        val age = (((at.toEpochMilli() - NEW_MOON_MS) / 86_400_000.0) % SYNODIC + SYNODIC) % SYNODIC
        val f = age / SYNODIC
        return MoonPhase(f, (1 - cos(2 * Math.PI * f)) / 2)
    }

    // ---- NOAA arithmetic --------------------------------------------------------------------

    private class Solar(val decl: Double, val eqTime: Double)

    private fun julianCentury(at: Instant): Double {
        val jd = at.toEpochMilli() / 86_400_000.0 + 2440587.5
        return (jd - 2451545.0) / 36525.0
    }

    private fun minutesOfUtcDay(at: Instant): Double {
        val ms = at.toEpochMilli()
        val day = floor(ms / 86_400_000.0) * 86_400_000.0
        return (ms - day) / 60_000.0
    }

    /** Declination (radians) and the equation of time (minutes) at [t] Julian centuries. */
    private fun solar(t: Double): Solar {
        val l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = rad(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = rad(125.04 - 1934.136 * t)
        val lambda = rad(trueLong - 0.00569 - 0.00478 * sin(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = rad(eps0 + 0.00256 * cos(omega))
        val decl = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2).let { it * it }
        val l0r = rad(l0)
        val eq = y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
            0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr)
        return Solar(decl, 4 * deg(eq))
    }

    /** The solar noon of the UTC day containing [near]'s solar day, read with the sun at [near]. */
    private fun solarNoonNear(near: Instant, lon: Double): Instant {
        val s = solar(julianCentury(near))
        val noonMinutes = 720 - 4 * lon - s.eqTime
        // The UTC midnight that puts this noon closest to [near].
        val ms = near.toEpochMilli()
        val target = ms - (noonMinutes * 60_000).toLong()
        val midnight = Math.floorDiv(target + 43_200_000L, 86_400_000L) * 86_400_000L
        return Instant.ofEpochMilli(midnight + (noonMinutes * 60_000).toLong())
    }

    /** Hour angle in degrees at which the sun stands at [altitude], or null if it never does. */
    private fun hourAngle(lat: Double, decl: Double, altitude: Double): Double? {
        val la = rad(lat)
        val c = (sin(rad(altitude)) - sin(la) * sin(decl)) / (cos(la) * cos(decl))
        if (c < -1 || c > 1) return null
        return deg(acos(c))
    }

    /** NOAA's refraction allowance, degrees, for a true (geometric) altitude in degrees. */
    private fun refraction(alt: Double): Double {
        if (alt > 85) return 0.0
        val te = tan(rad(alt))
        val seconds = when {
            alt > 5 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            alt > -0.575 -> 1735 + alt * (-518.2 + alt * (103.4 + alt * (-12.79 + alt * 0.711)))
            else -> -20.772 / te
        }
        return seconds / 3600
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(d: Double) = (d % 360 + 360) % 360
}
