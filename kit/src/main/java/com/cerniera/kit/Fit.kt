package com.cerniera.kit

import android.app.Activity
import android.content.res.Configuration

/**
 * How big the app's own window is, for laying out (adaptive quality, 19 Nov 2026).
 *
 * Read the window, never the device: in split screen or a pop-up window the app may have a
 * narrow, tall slice of the main screen, and must lay out for that slice. Folded or open is a
 * different question, answered by [Hinge.unfolded] from the whole screen.
 *
 * The thresholds are Android's window size classes (width: 600 and 840 dp; height: 480 and
 * 900 dp). Call from onCreate and again from onConfigurationChanged: a resize arrives as one.
 */
object Fit {
    enum class Size { COMPACT, MEDIUM, EXPANDED }

    /** The window's width and height in dp. */
    fun dp(a: Activity): Pair<Float, Float> {
        val d = a.resources.displayMetrics.density
        val b = runCatching { a.windowManager.currentWindowMetrics.bounds }.getOrNull()
        if (b != null && b.width() > 0) return b.width() / d to b.height() / d
        val c: Configuration = a.resources.configuration
        return c.screenWidthDp.toFloat() to c.screenHeightDp.toFloat()
    }

    fun width(a: Activity): Size = dp(a).first.let { if (it < 600f) Size.COMPACT else if (it < 840f) Size.MEDIUM else Size.EXPANDED }
    fun height(a: Activity): Size = dp(a).second.let { if (it < 480f) Size.COMPACT else if (it < 900f) Size.MEDIUM else Size.EXPANDED }

    /**
     * The family's two-pane test: the window is at least 600 dp wide. True on the main screen and
     * the turned cover; false on the upright cover and on half of the main screen in split screen.
     */
    fun twoPanes(a: Activity) = dp(a).first >= 600f

    /** Short and wide (the turned cover, a landscape split): the layout gets its own arrangement. */
    fun shortAndWide(a: Activity) = dp(a).let { (w, h) -> h < 480f && w > h }

    /** In split screen or a pop-up window. */
    fun sharing(a: Activity) = a.isInMultiWindowMode
}
