package com.cerniera.kit

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.CalendarContract
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Partenza (24 Nov 2026), step 1: a live "leave by" for the next diary event that has a place.
 *
 * Reads the next timed event with a place you'd drive to ([PartenzaRules.eligible]), finds the place
 * (the phone's own Geocoder first, TomTom's search if it can't), takes where the phone is (coarse is
 * enough), and asks TomTom's Routing API for the drive by car in today's traffic. Leave by = start,
 * less the drive, less the buffer. Re-checks run as one-shot jobs from two hours before, tighter
 * near the end ([PartenzaRules.CHECKS]), and whenever the calendar changes.
 *
 * Everything it decides goes to a log (files/partenza.log), to live with against a week of the real
 * diary before anything is drawn. One app hosts it (Fold Widgets) and shares the estimate through
 * [PartenzaProvider] at [URI]; any app reads it with [read], as Senso does.
 *
 * The TomTom key is entered in the host app's Settings and kept on the phone, never in the code.
 */
object Partenza {
    /** The shared estimate: one row, or none. */
    const val AUTHORITY = "com.cerniera.partenza"
    val URI: Uri = Uri.parse("content://$AUTHORITY/next")

    /** Sent to the host app's own package when the estimate changes (Momento redraws on it). */
    const val CHANGED = "com.cerniera.PARTENZA_CHANGED"

    val COLUMNS = arrayOf("title", "starts_at", "place", "leave_by", "drive_s", "delay_s", "length_m", "buffer_min", "checked_at", "from_place")

    private const val JOB_CHECK = 0x50410001
    private const val JOB_CALENDAR = 0x50410002
    private const val MIN = 60_000L

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("cerniera_partenza", Context.MODE_PRIVATE)

    // ---- Settings -------------------------------------------------------------------------

    fun key(c: Context): String? = prefs(c).getString("tomtom_key", null)?.trim()?.ifEmpty { null }
    fun setKey(c: Context, k: String?) {
        prefs(c).edit().apply { if (k.isNullOrBlank()) remove("tomtom_key") else putString("tomtom_key", k.trim()) }.apply()
        log(c, if (k.isNullOrBlank()) "TomTom key removed: Partenza off" else "TomTom key set")
        schedule(c, now = true)
    }

    fun buffer(c: Context): Int = prefs(c).getInt("buffer_min", PartenzaRules.BUFFER_MIN)
    fun setBuffer(c: Context, m: Int) {
        prefs(c).edit().putInt("buffer_min", m.coerceIn(0, 60)).apply()
        log(c, "Buffer set to $m min")
        schedule(c, now = true)
    }

    fun on(c: Context) = key(c) != null

    fun calendarAllowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun locationAllowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    /** Without it, re-checks in the background start from the last place the phone was seen. */
    fun backgroundAllowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- The estimate ---------------------------------------------------------------------

    /** This app's own last estimate (the host's). */
    fun stored(c: Context): Departure? = prefs(c).getString("departure", null)?.let(::fromJson)

    /** The shared estimate, from whichever app hosts Partenza; null if none, or none is hosted. */
    fun read(c: Context): Departure? = runCatching {
        if (c.packageManager.resolveContentProvider(AUTHORITY, 0) == null) return null
        c.contentResolver.query(URI, null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null
            else Departure(
                cur.getString(0) ?: "Event", cur.getLong(1), cur.getString(2).orEmpty(), cur.getLong(3), cur.getInt(4), cur.getInt(5),
                cur.getInt(6), cur.getInt(7), cur.getLong(8), cur.getString(9).orEmpty(),
            )
        }
    }.getOrNull()

    private fun save(c: Context, d: Departure?) {
        val before = prefs(c).getString("departure", null)
        val after = d?.let(::toJson)
        prefs(c).edit().apply { if (after == null) remove("departure") else putString("departure", after) }.apply()
        if (before != after) {
            runCatching { c.contentResolver.notifyChange(URI, null) }
            runCatching { c.sendBroadcast(Intent(CHANGED).setPackage(c.packageName)) }
        }
    }

    private fun toJson(d: Departure) = JSONObject().apply {
        put("title", d.title); put("start", d.start); put("where", d.where); put("leaveBy", d.leaveBy); put("driveS", d.driveS)
        put("delayS", d.delayS); put("lengthM", d.lengthM); put("bufferMin", d.bufferMin); put("checkedAt", d.checkedAt); put("from", d.from)
    }.toString()

    private fun fromJson(s: String) = runCatching {
        val o = JSONObject(s)
        Departure(o.getString("title"), o.getLong("start"), o.getString("where"), o.getLong("leaveBy"), o.getInt("driveS"), o.getInt("delayS"),
            o.getInt("lengthM"), o.getInt("bufferMin"), o.getLong("checkedAt"), o.getString("from"))
    }.getOrNull()

    // ---- The log ----------------------------------------------------------------------------

    private const val LOG_LINES = 400

    fun log(c: Context, line: String) = synchronized(this) {
        runCatching {
            val f = File(c.filesDir, "partenza.log")
            val stamp = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm").format(Instant.now().atZone(zone))
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "$stamp  $line"
            f.writeText(lines.takeLast(LOG_LINES).joinToString("\n") + "\n")
        }
    }

    /** Newest first. */
    fun logLines(c: Context): List<String> = runCatching { File(c.filesDir, "partenza.log").readLines().asReversed() }.getOrDefault(emptyList())

    fun logFile(c: Context) = File(c.filesDir, "partenza.log")

    // ---- Scheduling ---------------------------------------------------------------------------

    /**
     * Arms the re-check and the calendar watch. [now]: check straight away (Settings, a fresh key).
     * Host apps call this when opened and after boot.
     */
    fun schedule(c: Context, now: Boolean = false) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (!on(c)) { js.cancel(JOB_CHECK); js.cancel(JOB_CALENDAR); save(c, null); return }
        watchCalendar(c)
        if (now) at(c, System.currentTimeMillis())
        else if (js.getPendingJob(JOB_CHECK) == null) at(c, prefs(c).getLong("next_check", System.currentTimeMillis()))
    }

    private fun at(c: Context, t: Long) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        prefs(c).edit().putLong("next_check", t).apply()
        val delay = (t - System.currentTimeMillis()).coerceAtLeast(0L)
        js.schedule(JobInfo.Builder(JOB_CHECK, ComponentName(c, PartenzaJob::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + 2 * MIN)
            // Survives a restart where the app may (Fold Widgets can: it already starts at boot)
            .setPersisted(c.checkSelfPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED) == PackageManager.PERMISSION_GRANTED)
            .build())
    }

    /** Any change to the calendar: look again (a new event, a moved one, a place added). */
    private fun watchCalendar(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (!calendarAllowed(c)) return
        if (js.getPendingJob(JOB_CALENDAR) != null) return
        js.schedule(JobInfo.Builder(JOB_CALENDAR, ComponentName(c, PartenzaJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(10_000L)
            .setTriggerContentMaxDelay(60_000L)
            .build())
    }

    internal fun ran(c: Context, jobId: Int) {
        if (jobId == JOB_CALENDAR) watchCalendar(c)
        check(c, if (jobId == JOB_CALENDAR) "calendar changed" else "re-check")
    }

    // ---- One check -----------------------------------------------------------------------------

    /** Reads, routes, logs and saves. Blocking: call off the main thread. */
    fun check(c: Context, why: String) = synchronized(this) {
        val now = System.currentTimeMillis()
        val key = key(c) ?: return
        val buffer = buffer(c)
        if (!calendarAllowed(c)) { log(c, "No calendar access"); save(c, null); at(c, now + 6 * 60 * MIN); return }
        val event = PartenzaRules.next(events(c, now), now)
        if (event == null) {
            if (stored(c) != null || prefs(c).getString("last_none", null) != today(now)) log(c, "No event with a place in the next 24 h ($why)")
            prefs(c).edit().putString("last_none", today(now)).apply()
            save(c, null)
            at(c, now + 6 * 60 * MIN)
            return
        }
        val last = stored(c)?.takeIf { it.start == event.start }
        val from = PartenzaRules.watchFrom(event.start, last?.driveS, buffer)
        if (now < from && last == null) {
            // Too early to route: a first look three hours out, then watch from two hours (sooner for a long drive)
            val first = event.start - 3 * 60 * MIN
            if (now < first) {
                if (prefs(c).getLong("noted_start", 0L) != event.start) log(c, "Next: ${event.title} ${PartenzaRules.clock(event.start, zone)} · ${event.where} ($why). First look at ${PartenzaRules.clock(first, zone)}")
                prefs(c).edit().putLong("noted_start", event.start).apply()
                at(c, first)
                return
            }
        }
        val mode = PartenzaRules.mode(now, event.start, last, buffer)
        val d = runCatching { route(c, key, event, buffer, mode, now) }
        d.onFailure { e ->
            log(c, "${event.title} ${PartenzaRules.clock(event.start, zone)}: ${e.message ?: e.javaClass.simpleName} ($why)")
            // Keep the last good estimate until it goes stale; try again in ten minutes
            at(c, minOf(now + 10 * MIN, event.start))
            return
        }
        val dep = d.getOrThrow()
        val moved = last?.let { (dep.leaveBy - it.leaveBy) / MIN }
        log(c, PartenzaRules.logLine(dep, mode, zone) + (moved?.takeIf { it != 0L }?.let { " · ${if (it > 0) "+" else ""}$it min on last" } ?: "") + " ($why)")
        save(c, dep)
        val next = PartenzaRules.nextCheck(now, event.start, dep.leaveBy, dep.driveS, buffer) ?: (event.start + MIN)
        at(c, next)
    }

    private fun today(now: Long) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    /** Timed events in the next 24 hours, declined ones left out. */
    private fun events(c: Context, now: Long): List<SensoEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath((now + PartenzaRules.LOOKAHEAD).toString()).build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS)
        val out = mutableListOf<SensoEvent>()
        c.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                out += SensoEvent(cur.getString(0) ?: "Event", cur.getLong(1), cur.getLong(2), cur.getString(3), cur.getInt(4) == 1)
            }
        }
        return out
    }

    // ---- The route -----------------------------------------------------------------------------

    private val fixer = Executors.newSingleThreadExecutor()

    private class Fix(val lat: Double, val lon: Double, val label: String)

    private fun route(c: Context, key: String, e: SensoEvent, buffer: Int, mode: PartenzaRules.Mode, now: Long): Departure {
        val where = e.where!!.trim()
        val origin = origin(c, now) ?: error("No position: allow location for Partenza in Settings")
        val dest = place(c, key, where, origin) ?: error("Couldn't find \"$where\" on the map")
        val time = when (mode) {
            PartenzaRules.Mode.ARRIVE_AT -> "&arriveAt=" + enc(PartenzaRules.iso(PartenzaRules.arriveAt(e.start, buffer), zone))
            PartenzaRules.Mode.DRIVE_NOW -> "&departAt=now"
        }
        val url = "https://api.tomtom.com/routing/1/calculateRoute/${origin.lat},${origin.lon}:${dest.first},${dest.second}/json" +
            "?key=${enc(key)}&travelMode=car&traffic=true&routeType=fastest&routeRepresentation=summaryOnly$time"
        val o = JSONObject(get(url, "route"))
        val s = o.getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        val drive = s.getInt("travelTimeInSeconds")
        return Departure(
            title = e.title, start = e.start, where = where,
            leaveBy = PartenzaRules.leaveBy(e.start, drive, buffer),
            driveS = drive, delayS = s.optInt("trafficDelayInSeconds", 0), lengthM = s.optInt("lengthInMeters", 0),
            bufferMin = buffer, checkedAt = now, from = origin.label,
        )
    }

    /** Where the phone is (coarse is enough), or the last place it was seen. */
    @Suppress("MissingPermission")
    private fun origin(c: Context, now: Long): Fix? {
        val p = prefs(c)
        if (locationAllowed(c)) {
            val lm = c.getSystemService(LocationManager::class.java)
            val fresh: Location? = runCatching {
                val f = CompletableFuture<Location?>()
                lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, null, fixer) { f.complete(it) }
                f.get(20, TimeUnit.SECONDS)
            }.getOrNull() ?: runCatching { lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }.getOrNull()
                ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            if (fresh != null) {
                p.edit().putString("last_fix", "${fresh.latitude},${fresh.longitude},${fresh.time}").apply()
                val age = (now - fresh.time) / MIN
                return Fix(fresh.latitude, fresh.longitude, if (age <= 15) "here" else "here, $age min ago")
            }
        }
        val last = p.getString("last_fix", null)?.split(",") ?: return null
        val age = (now - (last.getOrNull(2)?.toLongOrNull() ?: now)) / MIN
        val ago = if (age < 90) "$age min" else "${age / 60} h"
        return Fix(last[0].toDouble(), last[1].toDouble(), "last known place, $ago ago")
    }

    /** The event's place: cached, then the phone's Geocoder, then TomTom's search near [near]. */
    private fun place(c: Context, key: String, where: String, near: Fix): Pair<Double, Double>? {
        val p = prefs(c)
        val k = "geo_" + where.lowercase()
        p.getString(k, null)?.split(",")?.let { return it[0].toDouble() to it[1].toDouble() }
        val found = runCatching {
            if (!Geocoder.isPresent()) null
            else @Suppress("DEPRECATION") Geocoder(c, java.util.Locale.UK).getFromLocationName(where, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
        }.getOrNull()?.also { log(c, "Found \"$where\" with the phone's geocoder") }
            ?: runCatching {
                val url = "https://api.tomtom.com/search/2/search/${enc(where).replace("+", "%20")}.json?key=${enc(key)}&limit=1&countrySet=GB&lat=${near.lat}&lon=${near.lon}"
                val r = JSONObject(get(url, "search")).getJSONArray("results")
                if (r.length() == 0) null else r.getJSONObject(0).getJSONObject("position").let { it.getDouble("lat") to it.getDouble("lon") }
            }.getOrNull()?.also { log(c, "Found \"$where\" with TomTom search") }
        found?.let { p.edit().putString(k, "${it.first},${it.second}").apply() }
        return found
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, what: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        try {
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }
            val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val detail = runCatching { JSONObject(body).getJSONObject("detailedError").getString("message") }.getOrNull()
            error(when (code) {
                403 -> "TomTom refused the key (403): check it in Settings"
                429 -> "TomTom's daily allowance is used up (429)"
                400 -> "TomTom $what: ${detail ?: "no route between here and there"} (400)"
                else -> "TomTom $what: HTTP $code" + (detail?.let { ", $it" } ?: "")
            })
        } finally { conn.disconnect() }
    }
}

/** Partenza's re-checks and its calendar watch. Declared in the Kit's manifest, so every host has it. */
class PartenzaJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Partenza.ran(applicationContext, params.jobId) }
            catch (e: Exception) { Partenza.log(applicationContext, "Check failed: ${e.message ?: e.javaClass.simpleName}") }
            finally { jobFinished(params, false) }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/**
 * The estimate, shared read-only with the family's own apps. Declared only by the host app (Fold
 * Widgets), at [Partenza.AUTHORITY]. Only the apps in [READERS] may read it: it carries a diary event.
 */
class PartenzaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val caller = callingPackage
        if (caller != c.packageName && caller !in READERS) throw SecurityException("Partenza is shared only with Cerniera's apps")
        val out = MatrixCursor(Partenza.COLUMNS)
        val now = System.currentTimeMillis()
        Partenza.stored(c)?.takeIf { now < it.start && now - it.checkedAt <= PartenzaRules.STALE }?.let { d ->
            out.addRow(arrayOf<Any>(d.title, d.start, d.where, d.leaveBy, d.driveS, d.delayS, d.lengthM, d.bufferMin, d.checkedAt, d.from))
        }
        out.setNotificationUri(c.contentResolver, Partenza.URI)
        return out
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.cerniera.partenza"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        val READERS = setOf("com.fergusonconsultancy.foldtaskbar", "com.fergusonconsultancy.foldwidgets")
    }
}
