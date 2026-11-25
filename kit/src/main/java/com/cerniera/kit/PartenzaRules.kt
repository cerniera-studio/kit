package com.cerniera.kit

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/*
 * Partenza's rules (24 Nov 2026): the arithmetic of "leave by", with no Android in it, so every
 * decision can be tested on the JVM, as Senso's rules are. Partenza.kt does the reading (the
 * calendar, the place, the phone's position, TomTom) and asks this file what to make of it.
 *
 * Leave by = when the event starts, less the drive in today's traffic, less a buffer for parking and
 * walking in.
 */

/**
 * One leave-by estimate for one event. Times are epoch milliseconds.
 * [driveS] is the drive in today's traffic, [delayS] how much of it is traffic, [bufferMin] the
 * margin for parking and walking in, [from] where the drive starts ("here", "last known place").
 */
data class Departure(
    val title: String,
    val start: Long,
    val where: String,
    val leaveBy: Long,
    val driveS: Int,
    val delayS: Int,
    val lengthM: Int,
    val bufferMin: Int,
    val checkedAt: Long,
    val from: String,
) {
    val driveMin get() = (driveS / 60f).roundToInt()
    val delayMin get() = (delayS / 60f).roundToInt()
}

object PartenzaRules {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    /** The buffer for parking and walking in, unless changed in Settings. */
    const val BUFFER_MIN = 10

    /** How far ahead Partenza looks for an event with a place. */
    const val LOOKAHEAD = 24 * HOUR

    /** Watching begins this long before the event, or an hour before the last drive estimate if that is earlier. */
    const val WATCH = 2 * HOUR

    /** An estimate older than this no longer counts: Senso falls back to the 45-minute rule. */
    const val STALE = 60 * MIN

    /** Re-checks, in minutes before leave-by: looser far out, tighter near the end. */
    val CHECKS = listOf(90, 60, 45, 30, 20, 15, 10, 5, 0)

    /** Once leave-by has passed, the drive is checked again this often until the event starts. */
    const val LATE_EVERY = 5 * MIN

    /** Further than this from leave-by, the route is planned to arrive on time (TomTom's arriveAt). Nearer, it is driven now, in live traffic. */
    const val LIVE_WITHIN = 45 * MIN

    /** An event Partenza works for: timed, somewhere you would drive to, not started, within the next day. */
    fun eligible(e: SensoEvent, now: Long): Boolean =
        !e.allDay && Rules.physical(e.where) && e.start > now && e.start - now <= LOOKAHEAD

    /** The first event that qualifies, in start order. */
    fun next(events: List<SensoEvent>, now: Long): SensoEvent? = events.filter { eligible(it, now) }.minByOrNull { it.start }

    /** When the watch on [start] begins: two hours ahead, or earlier for a long drive. */
    fun watchFrom(start: Long, lastDriveS: Int?, bufferMin: Int): Long {
        val long = lastDriveS?.let { start - it * 1000L - bufferMin * MIN - HOUR }
        return minOf(start - WATCH, long ?: Long.MAX_VALUE)
    }

    /** Leave by, from a drive of [driveS] seconds starting now. */
    fun leaveBy(start: Long, driveS: Int, bufferMin: Int): Long = start - driveS * 1000L - bufferMin * MIN

    /** When to arrive, so the buffer is left before the event starts (TomTom's arriveAt). */
    fun arriveAt(start: Long, bufferMin: Int): Long = start - bufferMin * MIN

    enum class Mode { ARRIVE_AT, DRIVE_NOW }

    /**
     * How to ask for the route. Far out, today's traffic at the time you'll drive matters (arrive
     * at the right time); within [LIVE_WITHIN] of leave-by, the roads as they are now.
     */
    fun mode(now: Long, start: Long, last: Departure?, bufferMin: Int): Mode {
        val guess = last?.takeIf { it.start == start }?.leaveBy ?: leaveBy(start, 45 * 60, bufferMin)
        return if (guess - now > LIVE_WITHIN) Mode.ARRIVE_AT else Mode.DRIVE_NOW
    }

    /** The next time to check, or null once the event has started. [leaveBy] null: no estimate yet. */
    fun nextCheck(now: Long, start: Long, leaveBy: Long?, lastDriveS: Int? = null, bufferMin: Int = BUFFER_MIN): Long? {
        if (now >= start) return null
        val from = watchFrom(start, lastDriveS, bufferMin)
        if (now < from) return from
        if (leaveBy == null) return now + 5 * MIN
        val ahead = CHECKS.map { leaveBy - it * MIN }.filter { it > now + 30_000L }.minOrNull()
        if (ahead != null) return minOf(ahead, start)
        val late = now + LATE_EVERY
        return if (late < start) late else null
    }

    /** Where we stand against leave-by: what Momento and Lume will draw from. */
    enum class Phase { AHEAD, SOON, CONCENTRATO, GO, GONE }

    fun phase(now: Long, d: Departure): Phase {
        val mins = (d.leaveBy - now) / MIN.toFloat()
        return when {
            now >= d.start -> Phase.GONE
            mins <= 0f -> Phase.GO
            mins <= 10f -> Phase.CONCENTRATO
            mins <= 45f -> Phase.SOON
            else -> Phase.AHEAD
        }
    }

    /** Whether [d] still speaks for [event]: the same start, checked recently enough. */
    fun current(d: Departure?, event: SensoEvent?, now: Long): Departure? {
        if (d == null || event == null) return null
        if (kotlin.math.abs(d.start - event.start) > MIN) return null
        if (now - d.checkedAt > STALE) return null
        if (now >= d.start) return null
        return d
    }

    /** TomTom's times ("2015-04-02T15:01:57+02:00") as epoch milliseconds. */
    fun time(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    /** For TomTom's departAt/arriveAt: RFC 3339 with the offset. */
    fun iso(t: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(t).atZone(zone).withNano(0).toOffsetDateTime())

    fun clock(t: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t).atZone(zone))

    /** The headline: "Leave 08:12 · Interview 09:00 · 34 min drive". */
    fun line(d: Departure, zone: ZoneId): String =
        "Leave ${clock(d.leaveBy, zone)} · ${d.title} ${clock(d.start, zone)} · ${d.driveMin} min drive"

    /** One line for the log: everything that went into the estimate. */
    fun logLine(d: Departure, mode: Mode, zone: ZoneId): String {
        val traffic = if (d.delayMin > 0) ", ${d.delayMin} of it traffic" else ", no traffic delay"
        val km = String.format(java.util.Locale.UK, "%.1f", d.lengthM / 1000f)
        val how = if (mode == Mode.ARRIVE_AT) "planned to arrive ${clock(d.start - d.bufferMin * MIN, zone)}" else "driven now"
        return "${d.title} ${clock(d.start, zone)} · ${d.where}: leave ${clock(d.leaveBy, zone)} · ${d.driveMin} min$traffic · $km km · " +
            "${d.bufferMin} min buffer · from ${d.from} · $how"
    }
}
