package com.cerniera.kit

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import android.widget.RemoteViews

/**
 * How much picture one widget update may carry, and what to do when Android refuses one (27 Nov 2026).
 *
 * Android caps the bitmaps in one widget update at 1.5 screens' worth, and fixes that cap once, when
 * the phone starts, from the size of the screen at that moment. Every app budgeted from the screen in
 * use now. On a Fold started while folded and then opened, the main screen is larger than the one the
 * cap was set from, so an update could be refused. Four of the five apps ignored the refusal
 * (runCatching around updateAppWidget), and the widget kept its last picture: a finish that "won't
 * pull through". The audit caught it on the emulator (Fold Widgets' "exceeds maximum bitmap memory
 * usage" errors).
 *
 * Now every app sends through [send]. A refusal names the real cap; it is kept, the pictures are
 * redrawn within it and sent again, and every later update stays within it from the start.
 */
object WidgetBudget {
    private const val TAG = "Cerniera"
    private fun prefs(c: Context) = c.getSharedPreferences("cerniera_widget_budget", Context.MODE_PRIVATE)

    /** Bytes of bitmap one update may carry: 85% of 1.5 screens, or of Android's real cap once it is known. */
    fun bytes(c: Context): Long {
        val screen = Backdrop.screenSize(c)
        val guess = screen.x.toLong() * screen.y * 4 * 3 / 2 * 85 / 100
        val cap = prefs(c).getLong("max", 0L)
        return if (cap > 0) minOf(guess, cap * 85 / 100) else guess
    }

    /** A factor (at most 1) for apps that size pictures by a pixel count rather than [bytes]. */
    fun scale(c: Context): Float = prefs(c).getFloat("scale", 1f)

    /**
     * Sends the views [build] makes to widget [id]. If Android refuses them for their size, learns its
     * cap, and builds and sends again (up to twice), so the widget is never left on an old picture.
     * Returns whether an update went through.
     */
    fun send(c: Context, m: AppWidgetManager, id: Int, build: () -> RemoteViews): Boolean {
        repeat(3) { attempt ->
            val rv = runCatching(build).getOrElse { Log.w(TAG, "widget $id not drawn", it); return false }
            try {
                m.updateAppWidget(id, rv)
                if (attempt > 0) Log.i(TAG, "widget $id sent within Android's cap after ${attempt} refusal(s)")
                return true
            } catch (e: IllegalArgumentException) {
                val (used, max) = WidgetBudgetRules.parse(e.message) ?: (0L to 0L)
                val s = WidgetBudgetRules.nextScale(scale(c), used, max)
                prefs(c).edit().apply { if (max > 0) putLong("max", max) }.putFloat("scale", s).apply()
                Log.w(TAG, "widget $id refused by Android (used $used, max $max): redrawing smaller (scale $s)")
            } catch (e: RuntimeException) {
                Log.w(TAG, "widget $id not sent", e); return false
            }
        }
        return false
    }
}

/** The pure part of [WidgetBudget], tested on the JVM. */
object WidgetBudgetRules {
    private val NUMBERS = Regex("""used:\s*(\d+),\s*max:\s*(\d+)""")

    /** Android's refusal: "…exceeds maximum bitmap memory usage (used: 15913120, max: 1228800)". */
    fun parse(message: String?): Pair<Long, Long>? =
        message?.let { NUMBERS.find(it) }?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }

    /** The next pixel scale after a refusal: within the cap with room to spare, or half when the cap isn't named. */
    fun nextScale(current: Float, used: Long, max: Long): Float =
        if (used > 0 && max > 0) (current * 0.85f * max / used).coerceIn(0.05f, 1f) else (current * 0.5f).coerceAtLeast(0.05f)
}
