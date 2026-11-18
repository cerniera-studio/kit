package com.cerniera.kit

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Senso (Fluido, phase 0, 18 Nov 2026): the family's context engine. Gathers what the phone
 * already knows into [Signals], reads them with [Rules] into a handful of [Moment]s, each with a
 * confidence, and holds the one worth offering ([offered]). Every app that reads it asks for the
 * kinds it can act on, so Lume in Fold Taskbar only ever hears about rain, leaving and listening.
 *
 * Rules only: no model, nothing leaves the phone. It rides on events it already gets (power,
 * audio outputs, the calendar changing, Cielo's forecast changing, the screen coming on) and wakes
 * itself only at the next time a moment could begin or end, never on a fixed poll.
 *
 * What the Kit cannot see on its own, the app feeds: the app in front ([front]), what is playing
 * ([playing]), and home or away ([home]).
 *
 * Rain comes from Cielo through a small content provider (see [RAIN]); until Cielo shares it, rain
 * is unknown. In apps that allow it ([injectable]), `adb shell am broadcast -a
 * com.cerniera.senso.INJECT -p <package> --es rain_in 40 --es rain_chance 80` sets any signal by
 * hand, for the emulator; only the shell can send it (it needs android.permission.DUMP).
 */
class Senso(context: Context, val appName: String, val kinds: Set<Kind> = Kind.entries.toSet(), val injectable: Boolean = false) {
    private val ctx = context
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val store = app.getSharedPreferences("cerniera_senso", Context.MODE_PRIVATE)
    private val watchers = CopyOnWriteArrayList<(Senso) -> Unit>()
    private var started = false

    /** What a signal is worth, where it came from and when, for the debug view. */
    data class Reading(val name: String, val value: String, val source: String, val at: Long?)

    var signals = Signals(); private set
    var moments: List<Moment> = emptyList(); private set
    /** The moment to offer now, or null: silence. */
    var offered: Moment? = null; private set
    var readings: List<Reading> = emptyList(); private set
    /** The last changes, newest first. */
    val log = ArrayDeque<String>()

    fun watch(f: (Senso) -> Unit) { watchers += f }
    fun unwatch(f: (Senso) -> Unit) { watchers -= f }

    // ---- The clock (shifted only by injection) --------------------------------------------
    private var shift = 0L
    fun now() = System.currentTimeMillis() + shift
    val zone: ZoneId get() = ZoneId.systemDefault()

    // ---- What has been heard, real and injected -------------------------------------------
    private var real = Signals()
    private var fake = Signals()
    private val faked = HashSet<String>()
    private var calendarState = "not read yet"
    private var rainState = "not read yet"
    private var lightState = "not read"
    private var fedFront = false
    private var fedPlaying = false
    private var lastCalendar = 0L

    // ---- Lifecycle ------------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        val power = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_SCREEN_ON)
        }
        register(powerReceiver, power, exported = false)
        // The battery's sticky broadcast says whether it is charging now
        runCatching {
            val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            real = real.copy(charging = plugged)
        }
        runCatching { app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(audio, main) }
        runCatching { app.contentResolver.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, calendarObserver) }
        runCatching { app.contentResolver.registerContentObserver(RAIN, true, rainObserver) }
        if (injectable) {
            val f = IntentFilter(INJECT)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(injectReceiver, f, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(injectReceiver, f, "android.permission.DUMP", null)
            }
        }
        readCalendar(); readRain(); sampleLight()
        recompute("started")
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(tick)
        runCatching { app.unregisterReceiver(powerReceiver) }
        if (injectable) runCatching { app.unregisterReceiver(injectReceiver) }
        runCatching { app.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audio) }
        runCatching { app.contentResolver.unregisterContentObserver(calendarObserver) }
        runCatching { app.contentResolver.unregisterContentObserver(rainObserver) }
        stopLight()
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter, exported: Boolean) = runCatching {
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, f)
    }

    // ---- What the app feeds ---------------------------------------------------------------

    /** The app in front, from an app that can see it (Fold Taskbar's accessibility service). */
    fun front(pkg: String?) {
        fedFront = true
        if (pkg == real.front) return
        real = real.copy(front = pkg, frontSince = now())
        recompute(null)
    }

    /** What is playing, from an app with the media session (now playing in Fold Taskbar). */
    fun playing(p: SensoPlaying?) {
        fedPlaying = true
        val same = p?.pkg == real.playing?.pkg && p?.title == real.playing?.title && p?.playing == real.playing?.playing
        if (same) return
        real = real.copy(playing = p)
        recompute(null)
    }

    /** Coarse place, read only while an app is open (the family's rule). Null: not known. */
    fun home(atHome: Boolean?) { if (atHome != real.home) { real = real.copy(home = atHome); recompute(null) } }

    /** Where the sky is seen from (Solco's and Sveglia's place), Leeds until then. */
    fun place(p: Sky.Place) { if (p != real.place) { real = real.copy(place = p); recompute(null) } }

    /** Launchers, so the home screen never reads as reading. */
    fun homes(pkgs: Set<String>) { real = real.copy(homes = pkgs); recompute(null) }

    // ---- Dismissals (rule of trust 2) -----------------------------------------------------

    private fun today() = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().toString()

    /** Says nothing more about this kind of moment today. */
    fun dismiss(kind: Kind) {
        store.edit().putString("dismissed_${kind.id}", today()).apply()
        note("${kind.label} dismissed for today")
        recompute(null)
    }

    fun dismissedToday(): Set<Kind> = Kind.entries.filter { store.getString("dismissed_${it.id}", null) == today() }.toSet()

    fun undismissAll() {
        store.edit().apply { Kind.entries.forEach { remove("dismissed_${it.id}") } }.apply()
        note("Dismissals cleared")
        recompute(null)
    }

    // ---- Reading ----------------------------------------------------------------------------

    /** Reads the signals again and tells the watchers if anything they show changed. */
    fun recompute(reason: String?) {
        if (!started) return
        val now = now()
        if (now - lastCalendar > 30 * 60_000L || (real.event?.start ?: Long.MAX_VALUE) < now) readCalendar()
        if (!faked.contains("unfolded")) real = real.copy(unfolded = runCatching { Hinge.unfolded(ctx) }.getOrNull())
        signals = compose()
        moments = Rules.read(signals, now, zone, dismissedToday())
        val was = offered
        offered = Rules.pick(moments, kinds, was?.kind)
        if (offered?.kind != was?.kind) note(offered?.let { "Offered ${it.kind.label} (${it.percent}%)" } ?: "Silent")
        reason?.let { note(it) }
        readings = describe(now)
        if (injectable) writeState(now)
        scheduleNext(now)
        watchers.forEach { runCatching { it(this) } }
    }

    private fun compose(): Signals {
        fun <T> pick(name: String, r: T, f: T) = if (name in faked) f else r
        return Signals(
            unfolded = pick("unfolded", real.unfolded, fake.unfolded),
            front = pick("front", real.front, fake.front),
            frontSince = pick("front", real.frontSince, fake.frontSince),
            playing = pick("playing", real.playing, fake.playing),
            headphones = pick("headphones", real.headphones, fake.headphones),
            headphonesSince = pick("headphones", real.headphonesSince, fake.headphonesSince),
            charging = pick("charging", real.charging, fake.charging),
            chargingSince = pick("charging", real.chargingSince, fake.chargingSince),
            lux = pick("lux", real.lux, fake.lux),
            event = pick("event", real.event, fake.event),
            rain = pick("rain", real.rain, fake.rain),
            home = pick("home", real.home, fake.home),
            place = real.place,
            homes = real.homes,
        )
    }

    private fun describe(now: Long): List<Reading> {
        val s = signals
        fun src(name: String, real: String) = if (name in faked) "Injected" else real
        fun yes(b: Boolean?) = when (b) { true -> "Yes"; false -> "No"; null -> "Unknown" }
        fun time(t: Long) = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t).atZone(zone))
        fun inMin(t: Long) = ((t - now) / 60_000L).let { if (it >= 0) "in $it min" else "${-it} min ago" }
        return listOf(
            Reading("Clock", time(now), if (shift != 0L) "Injected" else "Phone", null),
            Reading("Screen", when (s.unfolded) { true -> "Main"; false -> "Cover"; null -> "Unknown" }, src("unfolded", "Screen width"), null),
            Reading("In front", s.front ?: "Unknown", src("front", if (fedFront) appName else "Not fed by $appName"), s.frontSince),
            Reading("Playing", s.playing?.let { (if (it.playing) "" else "Paused: ") + it.title } ?: "Nothing",
                src("playing", if (fedPlaying) "Media session" else "Not fed by $appName"), s.playing?.since),
            Reading("Headphones", yes(s.headphones), src("headphones", "Audio outputs"), s.headphonesSince),
            Reading("Charging", yes(s.charging), src("charging", "Battery"), s.chargingSince),
            Reading("Light", s.lux?.let { "${it.toInt()} lux" } ?: "Unknown", src("lux", lightState), null),
            Reading("Next event", s.event?.let { "${it.title} ${inMin(it.start)}" } ?: "None", src("event", calendarState), null),
            Reading("Rain", s.rain?.let { "${(it.chance * 100).toInt()}% ${inMin(it.startsAt)}" } ?: "None", src("rain", rainState), null),
            Reading("Home", yes(s.home), src("home", "Not known yet"), null),
            Reading("Sky over", s.place.name, "Kit.Sky", null),
        )
    }

    private fun note(line: String) {
        log.addFirst(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(now()).atZone(zone)) + "  " + line)
        while (log.size > 30) log.removeLast()
    }

    // ---- Waking at the next time that matters (no polling) ----------------------------------

    private val tick = Runnable { sampleLight(); recompute(null) }

    private fun scheduleNext(now: Long) {
        main.removeCallbacks(tick)
        val times = mutableListOf<Long>()
        signals.event?.let { e -> if (!e.allDay) { times += e.start - 45 * 60_000L; times += e.start } }
        signals.rain?.let { r -> times += r.startsAt - 120 * 60_000L; times += r.startsAt - 60 * 60_000L; times += r.startsAt + 15 * 60_000L }
        // A moment that changes with time is re-read every five minutes while it lasts
        if (moments.any { it.kind != Kind.LISTENING }) times += now + 5 * 60_000L
        // Late evening, so winding down can begin
        val t = Instant.ofEpochMilli(now).atZone(zone)
        listOf(LocalTime.of(21, 0), LocalTime.of(22, 0), LocalTime.of(5, 0)).forEach { lt ->
            var at = t.with(lt); if (!at.isAfter(t)) at = at.plusDays(1); times += at.toInstant().toEpochMilli()
        }
        val next = times.filter { it > now + 1000 }.minOrNull() ?: return
        main.postDelayed(tick, (next - now).coerceAtMost(6 * 3600_000L))
    }

    // ---- Sources ----------------------------------------------------------------------------

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> real = real.copy(charging = true, chargingSince = now())
                Intent.ACTION_POWER_DISCONNECTED -> real = real.copy(charging = false, chargingSince = now())
            }
            sampleLight()
            recompute(null)
        }
    }

    private val audio = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = audioChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = audioChanged()
    }

    private fun audioChanged() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
        val worn = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
        val on = outs.any { it.type in worn }
        if (on == real.headphones) return
        real = real.copy(headphones = on, headphonesSince = now())
        recompute(null)
    }

    private val calendarObserver = object : ContentObserver(main) { override fun onChange(self: Boolean) = readCalendar() }
    private val rainObserver = object : ContentObserver(main) { override fun onChange(self: Boolean) = readRain() }

    private fun calendarAllowed() = app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The next event that has not started, in the next 24 hours. Declined events are skipped. */
    fun readCalendar() {
        lastCalendar = now()
        if (!calendarAllowed()) { calendarState = "No calendar access"; real = real.copy(event = null); return }
        val from = now()
        work.execute {
            val found = runCatching {
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(from.toString()).appendPath((from + 24 * 3600_000L).toString()).build()
                val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS)
                app.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                    var out: SensoEvent? = null
                    while (cur.moveToNext() && out == null) {
                        val begin = cur.getLong(1)
                        if (begin <= from || cur.getInt(4) == 1) continue
                        if (cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        out = SensoEvent(cur.getString(0) ?: "Event", begin, cur.getLong(2), cur.getString(3))
                    }
                    out
                }
            }
            main.post {
                calendarState = if (found.isSuccess) "Calendar" else "Calendar unreadable"
                real = real.copy(event = found.getOrNull())
                recompute(null)
            }
        }
    }

    /** Cielo's next rain, if Cielo shares it. */
    fun readRain() {
        val has = runCatching { app.packageManager.resolveContentProvider(RAIN.authority!!, 0) != null }.getOrDefault(false)
        if (!has) { rainState = "Cielo not sharing yet"; return }
        work.execute {
            val r = runCatching {
                app.contentResolver.query(RAIN, null, null, null, null)?.use { cur ->
                    if (!cur.moveToFirst()) null
                    else SensoRain(cur.getLong(cur.getColumnIndexOrThrow("starts_at")), cur.getFloat(cur.getColumnIndexOrThrow("chance")),
                        cur.getColumnIndex("mm").takeIf { it >= 0 }?.let { cur.getFloat(it) })
                }
            }
            main.post {
                rainState = if (r.isSuccess) "Cielo" else "Cielo unreadable"
                real = real.copy(rain = r.getOrNull())
                recompute(null)
            }
        }
    }

    // One reading of the light sensor, only in the evening and night, when a dark room matters
    private var lightListening = false
    private val light = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            real = real.copy(lux = e.values.firstOrNull())
            lightState = "Light sensor"
            stopLight()
            recompute(null)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun sampleLight() {
        val h = Instant.ofEpochMilli(now()).atZone(zone).hour
        if (h in 7..19) { lightState = "Not read in the day"; return }
        if (lightListening) return
        val sm = app.getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: run { lightState = "No light sensor"; return }
        lightListening = runCatching { sm.registerListener(light, s, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
        main.postDelayed({ stopLight() }, 2000)
    }

    private fun stopLight() {
        if (!lightListening) return
        lightListening = false
        runCatching { app.getSystemService(SensorManager::class.java)?.unregisterListener(light) }
    }

    // ---- Injection (emulator tests) ----------------------------------------------------------

    private val injectReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { inject(i) }
    }

    /** Sets signals by hand. See [INJECT_KEYS] for the extras. */
    fun inject(i: Intent) {
        @Suppress("DEPRECATION")
        val x = i.extras?.let { b -> b.keySet().associateWith { b.get(it)?.toString().orEmpty() } } ?: emptyMap()
        fun num(k: String) = x[k]?.toFloatOrNull()
        fun bool(k: String) = x[k]?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
        fun none(k: String) = x[k]?.lowercase() == "none"
        if (bool("clear") == true) { faked.clear(); fake = Signals(); shift = 0L }
        x["at"]?.let { at ->
            runCatching {
                val t = LocalTime.parse(at)
                val wall = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
                shift = wall.with(t).toInstant().toEpochMilli() - System.currentTimeMillis()
            }
        }
        val now = now()
        fun ago(k: String) = num(k)?.let { now - (it * 60_000).toLong() } ?: now
        if (none("rain")) { faked += "rain"; fake = fake.copy(rain = null) }
        num("rain_in")?.let { m ->
            val ch = num("rain_chance") ?: 80f
            faked += "rain"; fake = fake.copy(rain = SensoRain(now + (m * 60_000).toLong(), if (ch > 1f) ch / 100f else ch, from = "Cielo (injected)"))
        }
        if (none("event")) { faked += "event"; fake = fake.copy(event = null) }
        num("event_in")?.let { m ->
            val start = now + (m * 60_000).toLong()
            faked += "event"; fake = fake.copy(event = SensoEvent(x["event_title"] ?: "Event", start, start + 3600_000L, x["event_where"]?.ifBlank { null }))
        }
        if (none("playing")) { faked += "playing"; fake = fake.copy(playing = null) }
        else x["playing"]?.let { t ->
            faked += "playing"
            fake = fake.copy(playing = SensoPlaying(x["player"] ?: "com.spotify.music", t, x["artist"].orEmpty(), bool("paused") != true, ago("paused_ago")))
        }
        bool("headphones")?.let { faked += "headphones"; fake = fake.copy(headphones = it, headphonesSince = ago("headphones_ago")) }
        bool("charging")?.let { faked += "charging"; fake = fake.copy(charging = it, chargingSince = ago("charging_ago")) }
        num("lux")?.let { faked += "lux"; fake = fake.copy(lux = it) }
        bool("unfolded")?.let { faked += "unfolded"; fake = fake.copy(unfolded = it) }
        x["front"]?.let { faked += "front"; fake = fake.copy(front = it, frontSince = ago("front_for")) }
        x["home"]?.let { faked += "home"; fake = fake.copy(home = bool("home").takeIf { _ -> it != "unknown" }) }
        Kind.of(x["dismiss"])?.let { dismiss(it) }
        if (x["undismiss"] != null) undismissAll()
        recompute("Injected: " + x.keys.sorted().joinToString())
    }

    /** The moments as JSON in files/senso.json, so an emulator test can read what Senso decided. */
    private fun writeState(now: Long) = runCatching {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val ms = moments.joinToString(",") { m ->
            "{\"kind\":${q(m.kind.id)},\"confidence\":${"%.3f".format(java.util.Locale.UK, m.confidence)},\"dismissed\":${m.dismissed},\"why\":${q(m.why)}}"
        }
        File(app.filesDir, "senso.json").writeText("{\"at\":$now,\"offered\":${offered?.let { q(it.kind.id) } ?: "null"},\"moments\":[$ms]}\n")
    }

    companion object {
        const val INJECT = "com.cerniera.senso.INJECT"
        /** Cielo's rain, one row: starts_at (epoch ms), chance (0 to 1), mm. No row: no rain in sight. */
        val RAIN: Uri = Uri.parse("content://com.cerniera.cielo.rain/next")
        const val INJECT_KEYS = "clear, at=HH:mm, rain_in, rain_chance, rain=none, event_in, event_title, event_where, event=none, " +
            "playing=<title>, artist, player, paused, paused_ago, playing=none, headphones, headphones_ago, charging, charging_ago, " +
            "lux, unfolded, front, front_for, home=true|false|unknown, dismiss=<kind>, undismiss"
    }
}
