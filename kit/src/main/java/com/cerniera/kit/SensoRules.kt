package com.cerniera.kit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/*
 * Senso's rules (Fluido, phase 0, 18 Nov 2026). Pure arithmetic over a snapshot of signals: no
 * Android, no model, nothing remembered between calls, so every moment can be tested on the JVM
 * and every confidence can explain itself, line by line. Senso.kt gathers the signals; this file
 * only reads them.
 */

/** The moments Senso can recognise. [id] is what tests, injection and dismissals use. */
enum class Kind(val id: String, val label: String) {
    LEAVING_SOON("leaving_soon", "Leaving soon"),
    RAIN_COMING("rain_coming", "Rain coming"),
    LISTENING("listening", "Listening"),
    WINDING_DOWN("winding_down", "Winding down"),
    WAKING("waking", "Waking"),
    READING("reading", "Reading");

    companion object {
        fun of(id: String?): Kind? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** The next calendar event. Times are epoch milliseconds. */
data class SensoEvent(val title: String, val start: Long, val end: Long, val where: String?, val allDay: Boolean = false)

/** Rain on its way, as Cielo forecasts it. [chance] 0 to 1. */
data class SensoRain(val startsAt: Long, val chance: Float, val mm: Float? = null, val from: String = "Cielo")

/** What is playing, or was until it paused at [since]. */
data class SensoPlaying(val pkg: String, val title: String, val artist: String, val playing: Boolean, val since: Long)

/**
 * Everything Senso knows at one moment. Null means unknown: a rule never treats unknown as no.
 * Times are epoch milliseconds.
 */
data class Signals(
    val unfolded: Boolean? = null,
    val front: String? = null,
    val frontSince: Long? = null,
    val playing: SensoPlaying? = null,
    val headphones: Boolean? = null,
    val headphonesSince: Long? = null,
    val charging: Boolean? = null,
    val chargingSince: Long? = null,
    /** Ambient light, lux. */
    val lux: Float? = null,
    val event: SensoEvent? = null,
    val rain: SensoRain? = null,
    /** Coarse place: at home or not. */
    val home: Boolean? = null,
    val place: Sky.Place = Sky.Place.LEEDS,
    /** Launchers, so time on the home screen never counts as reading. */
    val homes: Set<String> = emptySet(),
)

/**
 * One recognised moment. [why] is the headline a person reads on a long press ("Rain from 16:00
 * · Cielo"); [reasons] are the lines that built [confidence], for the debug view.
 */
data class Moment(
    val kind: Kind,
    val confidence: Float,
    val why: String,
    val reasons: List<String>,
    val dismissed: Boolean = false,
) {
    val percent get() = (confidence * 100).roundToInt()
}

object Rules {
    /** Below this, Senso keeps silent (rule of trust 3: silence is the default). */
    const val THRESHOLD = 0.6f
    /** A moment already offered holds until it falls this far under the threshold, so it never flickers. */
    const val HOLD = 0.1f
    /** Another moment takes over only when it is this much more certain. */
    const val OVERTAKE = 0.1f

    private const val MIN = 60_000L

    /** Every moment the signals support, most certain first. Dismissed kinds are kept, marked. */
    fun read(s: Signals, now: Long, zone: ZoneId = ZoneId.systemDefault(), dismissed: Set<Kind> = emptySet()): List<Moment> =
        listOfNotNull(leaving(s, now, zone), rain(s, now, zone), listening(s, now), windingDown(s, now, zone), waking(s, now, zone), reading(s, now))
            .map { if (it.kind in dismissed) it.copy(dismissed = true) else it }
            .sortedByDescending { it.confidence }

    /**
     * The one moment to offer, or none. Only [kinds] are eligible (an app asks for what it can act
     * on). [held] is what is offered now: it stays while it is within [HOLD] of the threshold,
     * and gives way only to a moment [OVERTAKE] more certain.
     */
    fun pick(moments: List<Moment>, kinds: Set<Kind> = Kind.entries.toSet(), held: Kind? = null, threshold: Float = THRESHOLD): Moment? {
        val open = moments.filter { it.kind in kinds && !it.dismissed }
        val best = open.filter { it.confidence >= threshold }.maxByOrNull { it.confidence }
        val current = held?.let { h -> open.firstOrNull { it.kind == h && it.confidence >= threshold - HOLD } }
        return when {
            current == null -> best
            best != null && best.kind != current.kind && best.confidence > current.confidence + OVERTAKE -> best
            else -> current
        }
    }

    // ---- The rules ------------------------------------------------------------------------

    private val ONLINE = listOf("http", "teams", "zoom", "meet.google", "google meet", "webex", "skype", "facetime", "online", "call", "virtual")

    /** A place you would have to travel to: something written, and not a link or a call. */
    fun physical(where: String?): Boolean {
        val w = where?.trim()?.lowercase().orEmpty()
        return w.isNotEmpty() && ONLINE.none { it in w }
    }

    private fun clock(t: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t).atZone(zone))
    private fun f(v: Float) = String.format(java.util.Locale.UK, "%.2f", v)

    /** Leaving soon: an event within 45 minutes, somewhere you would have to go. */
    fun leaving(s: Signals, now: Long, zone: ZoneId): Moment? {
        val e = s.event ?: return null
        if (e.allDay) return null
        val mins = (e.start - now) / MIN.toFloat()
        if (mins <= 0f || mins > 45f) return null
        val where = e.where?.trim().orEmpty()
        // A call or a link: nobody leaves for it
        if (where.isNotEmpty() && !physical(where)) return null
        val reasons = mutableListOf<String>()
        var c = 0.5f + 0.4f * (1f - mins / 45f)
        reasons += "${e.title} at ${clock(e.start, zone)}, in ${mins.roundToInt()} min · base ${f(c)}"
        if (where.isEmpty()) { c *= 0.6f; reasons += "no place given · ×0.60" }
        else { c += 0.08f; reasons += "at $where · +0.08" }
        if (s.home == true) { c += 0.05f; reasons += "at home, so you'll leave · +0.05" }
        val line = "${e.title} at ${clock(e.start, zone)}" + if (where.isNotEmpty()) " · $where" else ""
        return Moment(Kind.LEAVING_SOON, c.coerceIn(0f, 1f), line, reasons)
    }

    /** Rain coming: Cielo's next rain within two hours, surer the sooner and likelier it is. */
    fun rain(s: Signals, now: Long, zone: ZoneId): Moment? {
        val r = s.rain ?: return null
        val mins = (r.startsAt - now) / MIN.toFloat()
        if (mins < -15f || mins > 120f) return null
        val reasons = mutableListOf<String>()
        var c = r.chance.coerceIn(0f, 1f)
        reasons += "${(r.chance * 100).roundToInt()}% chance · base ${f(c)}"
        if (mins > 60f) { val k = 1f - 0.4f * (mins - 60f) / 60f; c *= k; reasons += "over an hour away · ×${f(k)}" }
        if (mins < 0f) { c *= 0.9f; reasons += "already raining · ×0.90" }
        val e = s.event
        if (e != null && !e.allDay && physical(e.where) && e.start > now && e.start - now <= 120 * MIN) {
            c += 0.15f; reasons += "going out to ${e.title} · +0.15"
        }
        if (s.home == false) { c += 0.1f; reasons += "out and about · +0.10" }
        val line = (if (mins < 0f) "Raining since " else "Rain from ") + clock(r.startsAt, zone) + " · ${r.from}"
        return Moment(Kind.RAIN_COMING, c.coerceIn(0f, 1f), line, reasons)
    }

    /** Listening: music playing (surer in headphones), or headphones just put in with a track paused. */
    fun listening(s: Signals, now: Long): Moment? {
        val p = s.playing ?: return null
        val hp = s.headphones == true
        val fresh = hp && s.headphonesSince != null && now - s.headphonesSince < 2 * MIN
        val track = if (p.artist.isBlank()) p.title else "${p.title} · ${p.artist}"
        return when {
            p.playing && hp -> Moment(Kind.LISTENING, 0.9f, track, listOf("playing · in headphones · 0.90"))
            p.playing -> Moment(Kind.LISTENING, 0.62f, track, listOf("playing aloud · 0.62"))
            fresh && now - p.since < 30 * MIN -> Moment(Kind.LISTENING, 0.75f, "Resume $track",
                listOf("headphones just in · paused ${((now - p.since) / MIN)} min ago · 0.75"))
            else -> null
        }
    }

    /** Winding down: late, dark, on the charger. */
    fun windingDown(s: Signals, now: Long, zone: ZoneId): Moment? {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val h = t.hour + t.minute / 60f
        val reasons = mutableListOf<String>()
        var c = when {
            h >= 22f || h < 2f -> 0.4f.also { reasons += "late (${clock(now, zone)}) · base 0.40" }
            h >= 21f -> 0.25f.also { reasons += "evening (${clock(now, zone)}) · base 0.25" }
            else -> return null
        }
        if (Sky.sun(s.place, Instant.ofEpochMilli(now)).altitude < Sky.CIVIL) { c += 0.1f; reasons += "night over ${s.place.name} · +0.10" }
        s.lux?.let { if (it < 10f) { c += 0.25f; reasons += "dark room (${it.roundToInt()} lux) · +0.25" } }
        if (s.charging == true) { c += 0.2f; reasons += "on the charger · +0.20" }
        return Moment(Kind.WINDING_DOWN, c.coerceIn(0f, 1f), "Late, ${clock(now, zone)}", reasons)
    }

    /** Waking: the morning, surer just after the phone comes off its charger. */
    fun waking(s: Signals, now: Long, zone: ZoneId): Moment? {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        if (t.hour < 5 || t.hour >= 10) return null
        val reasons = mutableListOf("morning (${clock(now, zone)}) · base 0.30")
        var c = 0.3f
        val since = s.chargingSince
        if (s.charging == false && since != null && now - since < 20 * MIN) { c += 0.4f; reasons += "just off the charger · +0.40" }
        if (Sky.sun(s.place, Instant.ofEpochMilli(now)).up) { c += 0.1f; reasons += "the sun is up · +0.10" }
        return Moment(Kind.WAKING, c.coerceIn(0f, 1f), "Morning, ${clock(now, zone)}", reasons)
    }

    /** Reading: open on the main screen, one app for a long time. */
    fun reading(s: Signals, now: Long): Moment? {
        if (s.unfolded != true) return null
        val app = s.front ?: return null
        if (app in s.homes) return null
        val since = s.frontSince ?: return null
        val mins = (now - since) / MIN.toFloat()
        if (mins < 8f) return null
        val c = 0.45f + 0.35f * ((mins - 8f) / 22f).coerceIn(0f, 1f)
        return Moment(Kind.READING, c, "One app for ${mins.roundToInt()} min", listOf("open · $app for ${mins.roundToInt()} min · ${f(c)}"))
    }
}
