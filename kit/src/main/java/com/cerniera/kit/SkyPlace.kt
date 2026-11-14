package com.cerniera.kit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Where each app sees the sky from: the phone's location, so Solco, Sveglia and Meridiana put the
 * sun in the same place wherever the phone is. Each app keeps its own copy (apps can't share
 * storage), and every app falls back the same way: the last place the phone gave, else Leeds.
 *
 * Coarse location only. A few kilometres moves sunrise by seconds; a degree of longitude moves it
 * by four minutes. The place is refreshed only while an app is open in front of you (no
 * background location), which is all the sun needs: it only moves when you travel.
 */
object SkyPlace {
    /** The place in use, and when the phone gave it (0: never, so this is the Leeds fallback). */
    data class Fix(val place: Sky.Place, val at: Long) {
        val fallback get() = at == 0L
        /** For a settings row, for example "Leeds, from your location, 2 days ago". */
        fun describe(now: Long = System.currentTimeMillis()): String {
            if (fallback) return "${place.name}, until your location is used"
            val mins = (now - at) / 60_000
            val ago = when {
                mins < 2 -> "just now"
                mins < 60 -> "$mins minutes ago"
                mins < 120 -> "an hour ago"
                mins < 60 * 24 -> "${mins / 60} hours ago"
                mins < 60 * 48 -> "yesterday"
                else -> "${mins / (60 * 24)} days ago"
            }
            return "${place.name}, from your location, $ago"
        }
    }

    private const val PREFS = "cerniera.sky"
    @Volatile private var cached: Fix? = null

    fun last(context: Context): Fix {
        cached?.let { return it }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val f = if (!p.contains("lat")) Fix(Sky.Place.LEEDS, 0) else Fix(
            Sky.Place(p.getString("name", null) ?: "Your location",
                Double.fromBits(p.getLong("lat", 0)), Double.fromBits(p.getLong("lon", 0))),
            p.getLong("at", 0))
        cached = f
        return f
    }

    /** The place alone, for drawing. */
    fun place(context: Context) = last(context).place

    fun remember(context: Context, place: Sky.Place, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", place.name).putLong("lat", place.lat.toBits())
            .putLong("lon", place.lon.toBits()).putLong("at", at).apply()
        cached = Fix(place, at)
    }

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks the phone for a fresh coarse fix, names it, keeps it, and calls [done] on the main thread
     * with the place in use (the new one, or the old one if the phone gave nothing). Does nothing
     * but call [done] when location isn't allowed. Call while the app is in front.
     */
    fun refresh(context: Context, done: (Fix, Boolean) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        if (!allowed(app)) { done(last(app), false); return }
        val lm = app.getSystemService(LocationManager::class.java)
        if (lm == null) { done(last(app), false); return }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fallbackFix = {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        }
        val io = Executors.newSingleThreadExecutor()
        val take: (Location?) -> Unit = { loc ->
            io.execute {
                val l = loc ?: runCatching { fallbackFix() }.getOrNull()
                if (l == null) { main.post { done(last(app), false) }; io.shutdown(); return@execute }
                val name = nameOf(app, l.latitude, l.longitude) ?: last(app).place.takeIf {
                    // Keep the old name if we haven't moved far (about 5 km)
                    !last(app).fallback && kotlin.math.abs(it.lat - l.latitude) < .05 && kotlin.math.abs(it.lon - l.longitude) < .08
                }?.name ?: "Your location"
                remember(app, Sky.Place(name, l.latitude, l.longitude), l.time.takeIf { it > 0 } ?: System.currentTimeMillis())
                main.post { done(last(app), true) }
                io.shutdown()
            }
        }
        try {
            if (provider == null) take(null)
            else lm.getCurrentLocation(provider, null, io) { take(it) }
        } catch (_: SecurityException) {
            done(last(app), false); io.shutdown()
        } catch (_: Throwable) {
            take(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun nameOf(context: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()
}
