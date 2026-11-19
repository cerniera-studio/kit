package com.cerniera.kit

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.view.Display
import android.view.WindowManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * The hinge, as every app hears it. Listens to the hinge angle sensor while started and reports
 * the angle, the fold's speed and the moment the phone passes fully open or fully closed.
 *
 * Fast or slow (Concentrato) is judged from the last 700 ms of readings: a one-handed flick is
 * well above 320 °/s, a deliberate fold well below. 320 is Solco's estimate, still to be tuned on
 * the Fold 8 Ultra; change it here and every app follows.
 *
 * No hinge sensor (an emulator, another phone): [present] is false and nothing is reported, so
 * apps fall back to [unfolded], read from the whole screen.
 */
class Hinge(context: Context, private val listener: Listener) : SensorEventListener {
    interface Listener {
        /** Degrees: about 0 closed, 180 flat open. */
        fun onAngle(angle: Float) {}
        /** The phone passed through to open (true) or closed (false); [fast] from the swing before it. */
        fun onFold(opened: Boolean, fast: Boolean) {}
    }

    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = runCatching { sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }.getOrNull()
    val present get() = sensor != null

    private val times = LongArray(64)
    private val angles = FloatArray(64)
    private var count = 0
    private var head = 0
    private var open: Boolean? = null
    var angle = Float.NaN
        private set

    fun start() { sensor?.let { runCatching { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } } }
    fun stop() { runCatching { sm?.unregisterListener(this) } }

    override fun onSensorChanged(e: SensorEvent) {
        val a = e.values.firstOrNull() ?: return
        angle = a
        times[head] = SystemClock.elapsedRealtime(); angles[head] = a
        head = (head + 1) % times.size
        if (count < times.size) count++
        listener.onAngle(a)
        // Hysteresis so a hand resting near a threshold never flickers
        val nowOpen = when {
            a >= OPEN_AT -> true
            a <= CLOSED_AT -> false
            else -> open
        }
        if (nowOpen != null && open != null && nowOpen != open) listener.onFold(nowOpen, speed() > FAST)
        if (nowOpen != null) open = nowOpen
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Degrees a second over the last 700 ms; 0 with too few readings. */
    fun speed(): Float {
        val now = SystemClock.elapsedRealtime()
        var firstT = 0L; var firstA = 0f; var lastT = 0L; var lastA = 0f; var seen = 0
        for (j in 0 until count) {
            val i = (head - count + j + times.size) % times.size
            if (now - times[i] > 700) continue
            if (seen == 0) { firstT = times[i]; firstA = angles[i] }
            lastT = times[i]; lastA = angles[i]; seen++
        }
        if (seen < 2 || lastT <= firstT) return 0f
        return abs(lastA - firstA) / ((lastT - firstT) / 1000f)
    }

    /**
     * How fast the last fold was, from 0 (slow) to 1 (fast): the family's definition, from Fold
     * Motion. Opening is timed from the hinge passing 90° to now (the screen switching): 140 ms or
     * less is fast, 520 ms or more is slow. Closing reads the same crossing downwards. 0 when the
     * hinge saw no crossing in the last 1.5 s.
     */
    fun foldSpeed(opening: Boolean): Float {
        val now = SystemClock.elapsedRealtime()
        for (k in 1 until count) {
            val a = (head - k + times.size) % times.size
            val b = (head - k - 1 + times.size) % times.size
            if (now - times[a] > 1500) break
            val crossed = if (opening) angles[b] < 90f && angles[a] >= 90f else angles[b] > 90f && angles[a] <= 90f
            if (crossed) {
                val ms = (now - times[a]).toFloat()
                return ((1f - (ms - FAST_MS) / (SLOW_MS - FAST_MS))).coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    companion object {
        /** foldSpeed's thresholds: the 90° crossing to the screen switching. */
        const val FAST_MS = 140f
        const val SLOW_MS = 520f
        const val FAST = 320f
        const val OPEN_AT = 150f
        const val CLOSED_AT = 20f

        /**
         * Folded or open, from the whole screen the app is on, never from the app's own window: the
         * main screen is at least 600dp at its narrowest. A half of the main screen in split screen,
         * or a pop-up window, is still the main screen (adaptive quality, 19 Nov 2026). Turning the
         * phone never reads as a fold, because the narrowest side is used.
         */
        fun unfolded(c: Context): Boolean {
            val (w, h) = screenPx(c) ?: return c.resources.configuration.smallestScreenWidthDp >= 600
            return minOf(w, h) / c.resources.displayMetrics.density >= 600f
        }

        /** The full screen in pixels, whatever size the app's window is; null if it can't be read. */
        @Suppress("DEPRECATION")
        fun screenPx(c: Context): Pair<Int, Int>? {
            if (runCatching { c.isUiContext }.getOrDefault(false)) {
                runCatching { c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }
                    .getOrNull()?.takeIf { it.width() > 0 }?.let { return it.width() to it.height() }
            }
            val p = Point()
            runCatching { c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p) }
            return if (p.x > 0 && p.y > 0) p.x to p.y else null
        }
    }
}
