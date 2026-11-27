package com.cerniera.kit

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.SizeF

/**
 * One look for every Cerniera widget (26 Nov 2026).
 *
 * Fold Widgets is where the family's look is chosen: the finish, the corners, and the images behind
 * the glass. Every other app with a widget (Nodo, Dorso, Riserva, Settimana, and any to come) wears
 * it through this object, so the same code does the same thing in every app.
 *
 * Before this, each app had its own copy of the follower code, and the copies had drifted. Mechack
 * saw the result on his Fold on 26 Nov: Nodo, Riserva and Settimana side by side in three different
 * looks, some stuck on an old finish.
 *  - Settimana was never told when the finish changed (Fold Widgets' list of followers stopped at
 *    Riserva), so it kept whatever it last drew.
 *  - Riserva and Settimana never took up their places from Fold Widgets' live mode, and could not
 *    read the wallpaper, so an adaptive finish took a different tone over them than over Nodo.
 *  - A follower that found Fold Widgets slow to answer fell back to Vetro at 24 until its next redraw.
 *  - Nodo could be switched off following.
 *
 * Now:
 *  - **The look** is read from Fold Widgets every time a widget is drawn. If it can't answer, the last
 *    look it gave is kept ([FamilyRules.decide]); an app's own choice applies only without Fold Widgets.
 *  - **The wallpaper** comes from Fold Widgets too: it hands over the image its own glass uses (the
 *    one chosen for that screen, else a still of the system wallpaper), versioned, so every widget's
 *    glass shows the same picture without each app asking for all-files access.
 *  - **The place** each widget sits is taken from Fold Widgets' live mode ([seats]), so its glass is
 *    cut from the same slice of wallpaper, zone by zone, as the Fold widgets beside it.
 *  - **The tell:** Fold Widgets finds every app that listens for [CHANGED] and tells them all. An
 *    app joins the family by declaring a receiver for it and naming its widget's picture view `root`.
 */
object Family {
    const val HOST = "com.fergusonconsultancy.foldwidgets"
    const val AUTHORITY = "com.fergusonconsultancy.foldwidgets.look"
    const val CHANGED = "com.cerniera.LOOK_CHANGED"
    /** Fold Widgets' corners, by the same names. */
    val CORNERS = listOf("Square" to 0, "Soft" to 14, "Round" to 24, "Full" to 32)

    class Look(val finish: Finish, val radius: Int, val source: FamilyRules.Source) {
        val fromHost get() = source != FamilyRules.Source.OWN
    }

    private val KNOWN = Finish.values().map { it.name }.toSet()

    fun hostInstalled(c: Context): Boolean =
        runCatching { c.packageManager.resolveContentProvider(AUTHORITY, 0) != null }.getOrDefault(false)

    /**
     * The look in force now for this app's widgets. Also points the Kit's Backdrop (which reads
     * "image_cover" and "image_main" from [store]) at the glass's pictures: Fold Widgets' when it is
     * installed, else [ownImages].
     */
    fun look(c: Context, store: SharedPreferences, own: Finish = Finish.VETRO, ownRadius: Int = 24,
             ownImages: Map<String, String?> = emptyMap()): Look {
        val installed = hostInstalled(c)
        var answer: FamilyRules.Answer? = null
        var images: Map<String, String?>? = null
        if (installed) runCatching {
            c.contentResolver.query(Uri.parse("content://$AUTHORITY/look"), null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val stampCol = cur.getColumnIndex("stamp")
                    val stamp = if (stampCol >= 0) cur.getLong(stampCol) else 0L
                    answer = FamilyRules.Answer(cur.getString(cur.getColumnIndexOrThrow("finish")), cur.getInt(cur.getColumnIndexOrThrow("radius")), stamp)
                    images = listOf("cover", "main").associateWith { screen ->
                        if (cur.getInt(cur.getColumnIndexOrThrow("image_$screen")) == 1) FamilyRules.imageUri(AUTHORITY, screen, stamp) else null
                    }
                }
            }
        }.onFailure { android.util.Log.w("Cerniera", "family look: Fold Widgets not read", it) }
        val remembered = store.getString(KEY_FINISH, null)?.let { FamilyRules.Answer(it, store.getInt(KEY_RADIUS, 24), store.getLong(KEY_STAMP, 0L)) }
        val d = FamilyRules.decide(installed, answer, remembered, own.name, ownRadius, KNOWN)
        store.edit().apply {
            if (d.source == FamilyRules.Source.HOST) answer?.let { putString(KEY_FINISH, it.finish).putInt(KEY_RADIUS, it.radius).putLong(KEY_STAMP, it.stamp) }
            // Remembered: the pictures stay as they were last pointed
            val pics = when (d.source) { FamilyRules.Source.HOST -> images; FamilyRules.Source.OWN -> ownImages; else -> null }
            pics?.let { m -> listOf("cover", "main").forEach { s -> val u = m[s]; if (u == null) remove("image_$s") else putString("image_$s", u) } }
        }.apply()
        val look = Look(Finish.valueOf(d.finish), d.radius, d.source)
        android.util.Log.i("Cerniera", "family look for ${c.packageName}: ${look.finish.name} radius ${look.radius} (${d.source.name.lowercase()})")
        return look
    }

    /**
     * Takes up where Fold Widgets' live mode last saw this app's widgets, if that is new: each place
     * goes to the widget nearest its last known place, else the one closest in size.
     * [sizesDp] gives the sizes the launcher reports for a widget.
     */
    fun seats(c: Context, store: SharedPreferences, ids: IntArray, sizesDp: (Int) -> List<SizeF>) {
        if (ids.isEmpty() || !hostInstalled(c)) return
        class Seen(val l: Float, val t: Float, val w: Float, val h: Float, val sw: Int, val sh: Int, val live: Backdrop.LiveView?)
        val seen = ArrayList<Seen>(); var at = 0L
        runCatching {
            c.contentResolver.query(Uri.parse("content://$AUTHORITY/seats/${c.packageName}"), null, null, null, null)?.use { cur ->
                fun f(n: String) = cur.getFloat(cur.getColumnIndexOrThrow(n))
                fun i(n: String) = cur.getInt(cur.getColumnIndexOrThrow(n))
                val pk = cur.getColumnIndex("package")
                while (cur.moveToNext()) {
                    if (pk >= 0 && cur.getString(pk) != c.packageName) continue
                    val sw = i("screen_w"); val sh = i("screen_h")
                    val live = if (i("live") == 1) Backdrop.LiveView(f("ox"), f("oy"), floatArrayOf(f("gain_r"), f("gain_g"), f("gain_b")), sw, sh) else null
                    seen += Seen(f("left"), f("top"), f("width"), f("height"), sw, sh, live)
                    at = cur.getLong(cur.getColumnIndexOrThrow("at"))
                }
            }
        }.onFailure { android.util.Log.w("Cerniera", "family seats: Fold Widgets not read", it) }
        if (seen.isEmpty() || store.getLong(KEY_SEATS_AT, 0L) == at) return
        val screen = Backdrop.screenSize(c)
        // Seen on the other screen: kept for when the phone is folded or opened again
        val here = seen.filter { it.sw == screen.x && it.sh == screen.y }
        if (here.isEmpty()) return
        val d = Backdrop.density(c)
        val placed = FamilyRules.assign(here, ids.toList()) { s, id ->
            val old = Backdrop.seat(c, store, id)
            if (old != null && old.screenW == s.sw && old.screenH == s.sh)
                Math.abs(old.left - s.l) + Math.abs(old.top - s.t) + 3f * (Math.abs(old.width - s.w) + Math.abs(old.height - s.h))
            else 400f + 3f * (sizesDp(id).minOfOrNull { Math.abs(it.width * d - s.w) + Math.abs(it.height * d - s.h) } ?: 400f)
        }
        val e = store.edit()
        for ((id, s) in placed) {
            Backdrop.put(e, id, Backdrop.Seat(s.l, s.t, s.w, s.h, s.sw, s.sh, true))
            s.live?.let { v -> val g = v.gain ?: floatArrayOf(1f, 1f, 1f); e.putString("live_$id", listOf(v.ox, v.oy, g[0], g[1], g[2], v.screenW, v.screenH).joinToString(",")) }
                ?: e.remove("live_$id")
        }
        e.putLong(KEY_SEATS_AT, at).apply()
        android.util.Log.i("Cerniera", "family seats for ${c.packageName}: ${placed.size} of ${seen.size} placed by Fold Widgets' live mode")
    }

    /** The launcher's placing and tint of the wallpaper behind widget [id], when live mode saw them. */
    fun live(store: SharedPreferences, id: Int): Backdrop.LiveView? = runCatching {
        val v = store.getString("live_$id", null)!!.split(",")
        Backdrop.LiveView(v[0].toFloat(), v[1].toFloat(), floatArrayOf(v[2].toFloat(), v[3].toFloat(), v[4].toFloat()), v[5].toInt(), v[6].toInt())
    }.getOrNull()

    /** What lies behind widget [id]: the same slice of the same picture Fold Widgets' own glass would read. */
    fun ground(c: Context, store: SharedPreferences, id: Int): Ground = Backdrop.ground(c, store, id, live(store, id))

    /**
     * The usual round for a follower: take up new places, read the look once, and hand both to
     * [draw] for each widget. Call it from onUpdate, from the [CHANGED] receiver and after any change.
     */
    fun redraw(c: Context, store: SharedPreferences, ids: IntArray, sizesDp: (Int) -> List<SizeF>, own: Finish = Finish.VETRO,
               ownRadius: Int = 24, ownImages: Map<String, String?> = emptyMap(), draw: (id: Int, look: Look, ground: Ground) -> Unit) {
        if (ids.isEmpty()) return
        Backdrop.invalidate()
        seats(c, store, ids, sizesDp)
        val look = look(c, store, own, ownRadius, ownImages)
        for (id in ids) runCatching { draw(id, look, ground(c, store, id)) }
            .onFailure { android.util.Log.w("Cerniera", "widget $id not drawn", it) }
    }

    /** The sizes a launcher reports for widget [id], in dp, one per orientation and screen (up to four). */
    fun sizes(m: AppWidgetManager, id: Int, minW: Float = 110f, minH: Float = 110f): List<SizeF> {
        val o = m.getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val listed = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }.orEmpty()
        if (listed.isNotEmpty()) return listed.distinctBy { "${it.width.toInt()}x${it.height.toInt()}" }.take(4)
        val pw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val ph = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val lw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH); val lh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return listOf(SizeF(pw.toFloat().coerceAtLeast(minW), ph.toFloat().coerceAtLeast(minH)),
            SizeF(lw.toFloat().coerceAtLeast(minW), lh.toFloat().coerceAtLeast(minH))).distinct()
    }

    private const val KEY_FINISH = "family_finish"
    private const val KEY_RADIUS = "family_radius"
    private const val KEY_STAMP = "family_stamp"
    private const val KEY_SEATS_AT = "seatsAt"
}
