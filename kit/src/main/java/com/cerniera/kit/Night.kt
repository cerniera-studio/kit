package com.cerniera.kit

import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The night half of [Sky] (Notturno, 19 Nov 2026): where the moon and the five bright planets
 * stand over a place, how bright each is, and when each next sets.
 *
 * Paul Schlyter's "How to compute planetary positions": low-precision orbital elements with the
 * moon's twelve largest perturbations and Jupiter and Saturn's great inequality. Good to a
 * fraction of a degree for planets and about a tenth of a degree for the moon (tested against
 * PyEphem 4.2 for Leeds), far finer than any point of grain on a wallpaper. Altitudes are
 * apparent: the moon's includes its parallax (up to a degree), and every altitude includes the
 * same refraction as [Sky.sun]. Pure arithmetic: no network, no permissions, no Android.
 */

/** A point of light in the night sky. */
data class SkyBody(
    /** English name, for example "Jupiter". */
    val name: String,
    /** Degrees clockwise from true north. */
    val azimuth: Double,
    /** Degrees above the horizon (apparent), negative below. */
    val altitude: Double,
    /** Visual magnitude: smaller is brighter (Venus about −4, Saturn about +1). */
    val magnitude: Double,
    /** Angle from the sun, degrees. Under about 12° a planet is lost in twilight. */
    val elongation: Double,
) {
    val up get() = altitude > 0.0
    /** Up, clear of the sun's glare, and bright enough to see from a town: the ones worth drawing. */
    val visible get() = altitude > 2.0 && elongation > 12.0 && magnitude < 2.5
}

/** The moon over a place: where it is, how much is lit, and which way its lit side faces. */
data class MoonPosition(
    val azimuth: Double,
    /** Apparent: parallax and refraction included. */
    val altitude: Double,
    /** Distance in Earth radii (about 56 at perigee, 64 at apogee). */
    val distance: Double,
    /** 0 to 1, the lit share of the disc, from its true angle to the sun. */
    val illumination: Double,
    /** True while the lit share is growing. */
    val waxing: Boolean,
    /**
     * Which way the lit side faces as seen by someone looking at the moon: degrees from straight
     * up, clockwise (90 = lit on the right, 270 = lit on the left). A waxing moon in an English
     * evening is lit on its lower right, roughly 100° to 140°.
     */
    val brightLimb: Double,
) {
    val up get() = altitude > -0.3
}

/** The moon over [place] at [at]. */
fun Sky.moonAt(place: Sky.Place, at: Instant = Instant.now()): MoonPosition = Night.moon(place.lat, place.lon, at)

/** Mercury, Venus, Mars, Jupiter and Saturn over [place] at [at], brightest first. */
fun Sky.planets(place: Sky.Place, at: Instant = Instant.now()): List<SkyBody> =
    Night.PLANETS.map { Night.planet(it, place.lat, place.lon, at) }.sortedBy { it.magnitude }

/**
 * The next time after [from] that [name] ("Moon" or a planet's name) sinks below the horizon,
 * within [withinHours]; null if it doesn't (or isn't up). For Giulietta's trim: "sets 03:12".
 */
fun Sky.nextSet(name: String, place: Sky.Place, from: Instant = Instant.now(), withinHours: Int = 24): Instant? {
    val alt: (Instant) -> Double = { t ->
        if (name == "Moon") Night.moon(place.lat, place.lon, t).altitude
        else Night.planet(Night.PLANETS.first { it.name == name }, place.lat, place.lon, t).altitude
    }
    // Altitudes here are apparent, so a planet sets at 0° and the moon when its upper edge (0.26° above its centre) touches
    val edge = if (name == "Moon") -0.26 else 0.0
    var t0 = from
    if (alt(t0) - edge <= 0) return null
    val step = 10L * 60
    repeat(withinHours * 6) {
        val t1 = t0.plusSeconds(step)
        val a1 = alt(t1) - edge
        if (a1 <= 0) {
            var lo = t0; var hi = t1
            repeat(12) {
                val mid = lo.plusMillis((hi.toEpochMilli() - lo.toEpochMilli()) / 2)
                if (alt(mid) - edge > 0) lo = mid else hi = mid
            }
            return hi
        }
        t0 = t1
    }
    return null
}

/** Schlyter's arithmetic. Internal so the tests can reach it; apps use the [Sky] functions above. */
internal object Night {

    class Planet(
        val name: String,
        val n0: Double, val n1: Double, val i0: Double, val i1: Double,
        val w0: Double, val w1: Double, val a: Double, val e0: Double, val e1: Double,
        val m0: Double, val m1: Double,
    )

    private val MERCURY = Planet("Mercury", 48.3313, 3.24587E-5, 7.0047, 5.00E-8, 29.1241, 1.01444E-5, 0.387098, 0.205635, 5.59E-10, 168.6562, 4.0923344368)
    private val VENUS = Planet("Venus", 76.6799, 2.46590E-5, 3.3946, 2.75E-8, 54.8910, 1.38374E-5, 0.723330, 0.006773, -1.302E-9, 48.0052, 1.6021302244)
    private val MARS = Planet("Mars", 49.5574, 2.11081E-5, 1.8497, -1.78E-8, 286.5016, 2.92961E-5, 1.523688, 0.093405, 2.516E-9, 18.6021, 0.5240207766)
    private val JUPITER = Planet("Jupiter", 100.4542, 2.76854E-5, 1.3030, -1.557E-7, 273.8777, 1.64505E-5, 5.20256, 0.048498, 4.469E-9, 19.8950, 0.0830853001)
    private val SATURN = Planet("Saturn", 113.6634, 2.38980E-5, 2.4886, -1.081E-7, 339.3939, 2.97661E-5, 9.55475, 0.055546, -9.499E-9, 316.9670, 0.0334442282)
    val PLANETS = listOf(MERCURY, VENUS, MARS, JUPITER, SATURN)

    /** Days from 2000 Jan 0.0 UT (Schlyter's epoch). */
    fun day(at: Instant) = at.toEpochMilli() / 86_400_000.0 + 2440587.5 - 2451543.5

    private class Sun(val x: Double, val y: Double, val r: Double, val lon: Double, val meanLon: Double)

    private fun sun(d: Double): Sun {
        val w = 282.9404 + 4.70935E-5 * d
        val e = 0.016709 - 1.151E-9 * d
        val m = norm(356.0470 + 0.9856002585 * d)
        val ea = m + deg(e * sin(rad(m)) * (1.0 + e * cos(rad(m))))
        val xv = cos(rad(ea)) - e
        val yv = sqrt(1.0 - e * e) * sin(rad(ea))
        val v = deg(atan2(yv, xv))
        val r = sqrt(xv * xv + yv * yv)
        val lon = norm(v + w)
        return Sun(r * cos(rad(lon)), r * sin(rad(lon)), r, lon, norm(m + w))
    }

    private fun obliquity(d: Double) = 23.4393 - 3.563E-7 * d

    /** Local sidereal time, degrees. */
    private fun lst(d: Double, lon: Double, at: Instant): Double {
        val ut = ((at.toEpochMilli() % 86_400_000L + 86_400_000L) % 86_400_000L) / 3_600_000.0
        return norm(sun(d).meanLon + 180.0 + ut * 15.0 + lon)
    }

    /** Eccentric anomaly by Newton's method, degrees. */
    private fun kepler(mDeg: Double, e: Double): Double {
        val m = rad(mDeg)
        var ea = m + e * sin(m) * (1.0 + e * cos(m))
        repeat(8) { ea -= (ea - e * sin(ea) - m) / (1.0 - e * cos(ea)) }
        return deg(ea)
    }

    private class Ecliptic(val lon: Double, val lat: Double, val r: Double)

    /** Heliocentric ecliptic position (degrees, AU) from elements. */
    private fun orbit(n: Double, i: Double, w: Double, a: Double, e: Double, m: Double): Ecliptic {
        val ea = rad(kepler(m, e))
        val xv = a * (cos(ea) - e)
        val yv = a * sqrt(1.0 - e * e) * sin(ea)
        val v = deg(atan2(yv, xv))
        val r = sqrt(xv * xv + yv * yv)
        val nr = rad(n); val vw = rad(v + w); val ir = rad(i)
        val xh = r * (cos(nr) * cos(vw) - sin(nr) * sin(vw) * cos(ir))
        val yh = r * (sin(nr) * cos(vw) + cos(nr) * sin(vw) * cos(ir))
        val zh = r * sin(vw) * sin(ir)
        return Ecliptic(norm(deg(atan2(yh, xh))), deg(atan2(zh, sqrt(xh * xh + yh * yh))), r)
    }

    private class Horizontal(val az: Double, val alt: Double, val ra: Double, val dec: Double, val ha: Double)

    /** Geocentric ecliptic (lon, lat degrees) to the place's horizon (geometric altitude). */
    private fun toHorizon(lonE: Double, latE: Double, d: Double, lat: Double, lon: Double, at: Instant): Horizontal {
        val ecl = rad(obliquity(d))
        val xg = cos(rad(lonE)) * cos(rad(latE))
        val yg = sin(rad(lonE)) * cos(rad(latE))
        val zg = sin(rad(latE))
        val xe = xg
        val ye = yg * cos(ecl) - zg * sin(ecl)
        val ze = yg * sin(ecl) + zg * cos(ecl)
        val ra = norm(deg(atan2(ye, xe)))
        val dec = deg(atan2(ze, sqrt(xe * xe + ye * ye)))
        val ha = rad(lst(d, lon, at) - ra)
        val la = rad(lat); val de = rad(dec)
        val x = cos(ha) * cos(de)
        val y = sin(ha) * cos(de)
        val z = sin(de)
        val xhor = x * sin(la) - z * cos(la)
        val zhor = x * cos(la) + z * sin(la)
        val az = norm(deg(atan2(y, xhor)) + 180.0)
        val alt = deg(asin(zhor.coerceIn(-1.0, 1.0)))
        return Horizontal(az, alt, ra, dec, ha)
    }

    fun planet(p: Planet, lat: Double, lon: Double, at: Instant): SkyBody {
        val d = day(at)
        val h = orbit(p.n0 + p.n1 * d, p.i0 + p.i1 * d, p.w0 + p.w1 * d, p.a, p.e0 + p.e1 * d, norm(p.m0 + p.m1 * d))
        var hl = h.lon; var hb = h.lat
        // Jupiter and Saturn pull on each other (the great inequality)
        val mj = norm(JUPITER.m0 + JUPITER.m1 * d)
        val ms = norm(SATURN.m0 + SATURN.m1 * d)
        if (p === JUPITER) {
            hl += -0.332 * sind(2 * mj - 5 * ms - 67.6) - 0.056 * sind(2 * mj - 2 * ms + 21) + 0.042 * sind(3 * mj - 5 * ms + 21) -
                0.036 * sind(mj - 2 * ms) + 0.022 * cosd(mj - ms) + 0.023 * sind(2 * mj - 3 * ms + 52) - 0.016 * sind(mj - 5 * ms - 69)
        } else if (p === SATURN) {
            hl += 0.812 * sind(2 * mj - 5 * ms - 67.6) - 0.229 * cosd(2 * mj - 4 * ms - 2) + 0.119 * sind(mj - 2 * ms - 3) +
                0.046 * sind(2 * mj - 6 * ms - 69) + 0.014 * sind(mj - 3 * ms + 32)
            hb += -0.020 * cosd(2 * mj - 4 * ms - 2) + 0.018 * sind(2 * mj - 6 * ms - 49)
        }
        val xh = h.r * cosd(hl) * cosd(hb)
        val yh = h.r * sind(hl) * cosd(hb)
        val zh = h.r * sind(hb)
        val s = sun(d)
        val xg = xh + s.x; val yg = yh + s.y; val zg = zh
        val gr = sqrt(xg * xg + yg * yg + zg * zg)
        val glon = norm(deg(atan2(yg, xg)))
        val glat = deg(atan2(zg, sqrt(xg * xg + yg * yg)))
        val hz = toHorizon(glon, glat, d, lat, lon, at)
        // Brightness: distances and the phase angle seen from the planet
        val r = h.r
        val fv = deg(acos(((r * r + gr * gr - s.r * s.r) / (2 * r * gr)).coerceIn(-1.0, 1.0)))
        val base = 5 * log10(r * gr)
        val mag = when (p) {
            MERCURY -> -0.36 + base + 0.027 * fv + 2.2E-13 * Math.pow(fv, 6.0)
            VENUS -> -4.34 + base + 0.013 * fv + 4.2E-7 * fv * fv * fv
            MARS -> -1.51 + base + 0.016 * fv
            JUPITER -> -9.25 + base + 0.014 * fv
            else -> {
                // Saturn's rings, by their tilt to us
                val ir = 28.06; val nr = 169.51 + 3.82E-5 * d
                val b = asin((sind(glat) * cosd(ir) - cosd(glat) * sind(ir) * sind(glon - nr)).coerceIn(-1.0, 1.0))
                -9.0 + base + 0.044 * fv - 2.6 * abs(sin(b)) + 1.2 * sin(b) * sin(b)
            }
        }
        // Angle from the sun, as seen from Earth
        val elong = deg(acos(((s.r * s.r + gr * gr - r * r) / (2 * s.r * gr)).coerceIn(-1.0, 1.0)))
        return SkyBody(p.name, hz.az, hz.alt + refraction(hz.alt), mag, elong)
    }

    fun moon(lat: Double, lon: Double, at: Instant): MoonPosition {
        val d = day(at)
        val n = norm(125.1228 - 0.0529538083 * d)
        val i = 5.1454
        val w = norm(318.0634 + 0.1643573223 * d)
        val e = 0.054900
        val m = norm(115.3654 + 13.0649929509 * d)
        val o = orbit(n, i, w, 60.2666, e, m)
        // The twelve largest perturbations
        val s = sun(d)
        val ms = norm(356.0470 + 0.9856002585 * d)
        val ls = s.meanLon
        val lm = norm(m + w + n)
        val dd = lm - ls
        val f = lm - n
        val lonM = o.lon - 1.274 * sind(m - 2 * dd) + 0.658 * sind(2 * dd) - 0.186 * sind(ms) - 0.059 * sind(2 * m - 2 * dd) -
            0.057 * sind(m - 2 * dd + ms) + 0.053 * sind(m + 2 * dd) + 0.046 * sind(2 * dd - ms) + 0.041 * sind(m - ms) -
            0.035 * sind(dd) - 0.031 * sind(m + ms) - 0.015 * sind(2 * f - 2 * dd) + 0.011 * sind(m - 4 * dd)
        val latM = o.lat - 0.173 * sind(f - 2 * dd) - 0.055 * sind(m - f - 2 * dd) - 0.046 * sind(m + f - 2 * dd) +
            0.033 * sind(f + 2 * dd) + 0.017 * sind(2 * m + f)
        val dist = o.r - 0.58 * cosd(m - 2 * dd) - 0.46 * cosd(2 * dd)
        val hz = toHorizon(norm(lonM), latM, d, lat, lon, at)
        // Parallax: the moon is close enough to sit lower for us than for the Earth's centre
        val par = deg(asin(1.0 / dist))
        val topo = hz.alt - par * cosd(hz.alt)
        // Phase from the true angle between moon and sun
        val elong = deg(acos((cosd(s.lon - lonM) * cosd(latM)).coerceIn(-1.0, 1.0)))
        val illum = (1 - cosd(elong)) / 2
        val waxing = norm(lonM - s.lon) < 180.0
        // Which way the lit side faces: the sun's direction from the moon, less the parallactic angle
        val sunEq = toHorizon(s.lon, 0.0, d, lat, lon, at)
        val da = rad(sunEq.ra - hz.ra)
        val chi = deg(atan2(cosd(sunEq.dec) * sin(da), sind(sunEq.dec) * cosd(hz.dec) - cosd(sunEq.dec) * sind(hz.dec) * cos(da)))
        val q = deg(atan2(sin(hz.ha), tan(rad(lat)) * cosd(hz.dec) - sind(hz.dec) * cos(hz.ha)))
        // chi and q are measured from north towards east on the sky; seen facing the moon, east is left
        val limb = norm(-(chi - q))
        return MoonPosition(hz.az, topo + refraction(topo), dist, illum, waxing, limb)
    }

    /** NOAA's refraction allowance, as in [Sky.sun], degrees. */
    private fun refraction(alt: Double): Double {
        if (alt > 85.0) return 0.0
        val te = tan(rad(alt))
        val arcsec = when {
            alt > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / Math.pow(te, 5.0)
            alt > -0.575 -> 1735.0 + alt * (-518.2 + alt * (103.4 + alt * (-12.79 + alt * 0.711)))
            else -> -20.772 / te
        }
        return arcsec / 3600.0
    }

    private fun sind(x: Double) = sin(rad(x))
    private fun cosd(x: Double) = cos(rad(x))
    private fun rad(x: Double) = Math.toRadians(x)
    private fun deg(x: Double) = Math.toDegrees(x)
    private fun norm(x: Double) = (x % 360 + 360) % 360
}
