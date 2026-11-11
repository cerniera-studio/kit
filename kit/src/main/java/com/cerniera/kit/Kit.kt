package com.cerniera.kit

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * Cerniera's Kit: the one source of the family's colours, type and motion. Every app pulls this
 * module in (as a git submodule), so a change to a token, a curve or a control reaches all of
 * them in their next build, and no app keeps its own copy to drift.
 *
 * Two lights. Visconti (night) is the family's ground: smoked green-black, ivory ink, champagne
 * for what is chosen. Avorio (day) is its paper: warm ivory ground, iron ink, a darker champagne
 * so it keeps its contrast. Apps follow the phone's light or dark setting unless they pin one.
 */
object Kit {
    /** True for Visconti (night), false for Avorio (day). Set by [follow] or pinned by an app. */
    @Volatile var dark = true

    /** Follows the phone's light or dark setting. Call from each Activity's onCreate. */
    fun follow(c: Context) {
        dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
    }

    private fun pick(night: Long, day: Long) = (if (dark) night else day).toInt()

    // ---- Tokens ---------------------------------------------------------------------------
    // Night values are Fold Motion's originals (Oct 2026); day values are Sipario's daylight mix.

    val GROUND get() = pick(0xFF0E1311, 0xFFE9E3D6)
    val SURFACE get() = pick(0xFF151C18, 0xFFF4EFE5)
    val RAISED get() = pick(0xFF1D2621, 0xFFFFFFFF)
    val HAIRLINE get() = pick(0x1AF6F1E6, 0x24221E1A)
    val INK get() = pick(0xFFF6F1E6, 0xFF1D1B19)
    val MUTED get() = pick(0xFF96A098, 0xFF6C655E)
    /** What is chosen. */
    val CHAMPAGNE get() = pick(0xFFD9BB86, 0xFF9A7A45)
    /** Alfa red: only where a lamp, a stop or a warning is meant. */
    val ROSSO get() = pick(0xFFD7262E, 0xFFB01E26)
    val GOOD get() = pick(0xFF7FC79A, 0xFF3F7A55)
    val WARN get() = pick(0xFFE0A15A, 0xFFA8652A)
    /** What sits behind a sheet. */
    val SCRIM get() = pick(0xB8000000, 0x66141210)

    /** Fixed values, for things that must not change with the light (icons, wallpapers). */
    object Fixed {
        const val VISCONTI_GROUND = 0xFF0E1311.toInt()
        const val IVORY = 0xFFF6F1E6.toInt()
        const val CHAMPAGNE = 0xFFD9BB86.toInt()
        const val ROSSO = 0xFFD7262E.toInt()
        /** Fold Widgets' Visconti glass green. */
        const val VISCONTI_GREEN = 0xFF1A3F31.toInt()
        /** Rosso lacquer: oxblood over bright content, rosso over dark. */
        const val OXBLOOD = 0xFF6E0A12.toInt()
        const val LACQUER = 0xFFB8141E.toInt()
    }

    // ---- Motion ---------------------------------------------------------------------------

    /** The family's one crisp curve: everything that settles, settles on this. */
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** A liquid thumb's leading edge: races ahead. */
    val lead = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** And its trailing edge: lets go a beat later. */
    val trail = PathInterpolator(0.3f, 0f, 0.1f, 1f)

    /** Durations, in ms. */
    const val QUICK = 220L
    const val SETTLE = 260L
    const val GLIDE = 320L

    /** Giulietta: controls revealed by a touch recede this long after the last one. */
    const val RECEDE_MS = 3200L

    // ---- Type -----------------------------------------------------------------------------

    private var display: Typeface? = null
    private val jost = HashMap<Int, Typeface>()

    /** Italiana: names things. */
    fun display(c: Context): Typeface = display ?: runCatching {
        Typeface.createFromAsset(c.assets, "fonts/Italiana-Regular.ttf")
    }.getOrDefault(Typeface.SERIF).also { display = it }

    /** Jost at a weight (variable font): does everything else. Falls back to the system sans. */
    fun sans(c: Context, weight: Int = 400): Typeface = synchronized(jost) {
        jost.getOrPut(weight) {
            runCatching {
                Typeface.Builder(c.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' $weight").build()
            }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
        }
    }
}

/**
 * Bellezza Necessaria (23 Oct 2026): the doctrine behind every app, shown folded at the foot of
 * each app's settings with that app's own "In this app" notes.
 */
object Doctrine {
    const val TITLE = "Bellezza Necessaria"
    const val SUBTITLE = "A doctrine of visceral motion: the synthesis of mechanical and organic life"
    const val PREAMBLE = "Cerniera draws on Bauhaus, Gaudí and Alfa Romeo. We reject the cold grids of pure digital " +
        "minimalism and the clutter of the over-ornamented screen alike. An interface is one living ecosystem, where structural " +
        "utility, natural curves and tactile tension form a single canvas."
    val PRINCIPLES = listOf(
        Triple("1", "The monolithic membrane", "Gaudí meets Cuorerosso. No element exists in isolation. The screen is one continuous, " +
            "flexible volume, the way Alfa Romeo's designers began the Cuorerosso as a sculpture before carving out a car. Move one " +
            "element and its neighbours yield, stretch and pull: spatial magnetism."),
        Triple("2", "Concentrato: the search for emptiness", "Bauhaus meets Alfa Romeo's interior detox. Absolute functional economy. " +
            "During a gesture, the interface purges its periphery: the faster the movement, the emptier and more focused the space, " +
            "until only the vital trajectory is left."),
        Triple("3", "Progressive disclosure and proximity magnetism", "The Giulietta principle. As the Giulietta hides its rear door " +
            "handles in the black sweep of the C-pillar to keep a clean two-door line, controls stay covert in the trim and " +
            "materialise only where and when the hand reaches for them, then blend back."),
    )
    const val DECREE = "An interface must not behave like a rigid computer. Balancing functional restraint with organic, fluid physics, " +
        "beauty stops being a decorative overlay. It becomes the inevitable, necessary by-product of motion."
}

fun Context.px(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

/** An RGB or ARGB colour with its alpha replaced, from 0 to 1. */
fun withAlpha(rgb: Int, a: Float): Int {
    val v = if (a.isNaN()) 0 else (a.coerceIn(0f, 1f) * 255f).roundToInt()
    return (v shl 24) or (rgb and 0xFFFFFF)
}

/** Mixes two ARGB colours, alpha included. */
fun blend(a: Int, b: Int, t: Float): Int {
    val u = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).roundToInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Smoothstep from 0 to 1 between [e0] and [e1]. */
fun smooth(e0: Float, e1: Float, x: Float): Float {
    val u = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}
