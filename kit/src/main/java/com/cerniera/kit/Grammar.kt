package com.cerniera.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.min

/**
 * The family's widget grammar (22 Nov 2026). Every Cerniera widget, whichever app it comes from, is
 * composed from these rules, so a widget is known as Cerniera's at a glance the way any car in a
 * range is known by its maker: the same proportions, the same type, the same details in the same
 * places, fitted to its own job.
 *
 * Why: on 22 Nov Mechack laid Fold Widgets, Dorso, Nodo and Riserva side by side on the Fold and
 * found them inconsistent. Each app had worked out its own thresholds, scale and spacing, so the same
 * size of widget had a different inset, a different type size and a different shape of layout
 * depending on who drew it; some left large empty bands, others crammed a title into an ellipsis.
 *
 * The rules:
 * 1. **One shape test.** [shape] sorts every widget into five classes by proportion alone: TILE
 *    (short side under 90 dp), STRIP (twice as wide as tall), ROW, SQUARE and COLUMN (a quarter
 *    longer one way than the other). Every widget composes each class the same way:
 *    - COLUMN: the hero across the width at the top, the words beneath it, the foot at the bottom.
 *    - ROW: the hero standing at the left as tall as the widget allows; label, words and foot beside it.
 *    - SQUARE: the hero at the top left with the label beside it; the words across the full width,
 *      resting on the foot.
 *    - STRIP: the hero at full height, one line of words, the foot.
 *    - TILE: the hero alone, edge to edge, and the one control.
 * 2. **One scale.** [scale] is 1 up to a short side of 240 dp (every cover-screen size) and grows
 *    with it to 1.5. Two widgets of the same size set the same type at the same size.
 * 3. **One inset.** 14 dp at scale 1 (10 dp for tiles), on every side, for every widget.
 * 4. **One type.** Jost for every word in the pack's roles; Italiana only for the one large figure.
 *    Label 11/600 capitals tracked 0.14 in the muted ink; heading 15/600; statement 17/600; primary
 *    14.5/600; meta 12.5/500, muted. Titles are set as large as the room allows, whole words only.
 * 5. **The label opens it.** Top left, at the inset: what the widget is showing (READING, the
 *    player's name, the place), in the label role.
 * 6. **The foot closes it.** Bottom, at the inset: the progress line (3.2 dp at scale 1, round
 *    ends, the faint ink under the finish's light) and the one control, if any.
 * 7. **No empty bands.** Room left over goes first to the hero (it grows until it reaches its own
 *    limit), then to the title (larger, or more lines), and only then is shared between the gaps.
 *    A larger widget is the same composition larger, never the same small one adrift in space.
 * 8. **Empty states rest on the foot.** A statement (17/600) and a note (meta) at the bottom left,
 *    the label at the top, the hero's quiet form between: [statement].
 */
object Grammar {

    enum class Shape { TILE, STRIP, ROW, SQUARE, COLUMN }

    fun shape(w: Float, h: Float): Shape = when {
        min(w, h) < 90f -> Shape.TILE
        w >= 2f * h -> Shape.STRIP
        w >= 1.25f * h -> Shape.ROW
        h >= 1.25f * w -> Shape.COLUMN
        else -> Shape.SQUARE
    }

    /** The one scale: 1 up to a short side of 240 dp, then growing with it, to 1.5 at 360 dp. */
    fun scale(w: Float, h: Float): Float = (min(w, h) / 240f).coerceIn(1f, 1.5f)

    /** The one inset, at scale [k]; tiles keep a tighter one. */
    fun inset(w: Float, h: Float, k: Float = scale(w, h)): Float = if (min(w, h) < 90f) 10f else 14f * k

    const val GAP = 10f
    const val LABEL = 11f
    const val HEADING = 15f
    const val STATEMENT = 17f
    const val PRIMARY = 14.5f
    const val BODY = 14f
    const val META = 12.5f
    /** Line thickness of the foot's progress line at scale 1. */
    const val LINE = 3.2f
    /** Sizes a title is tried at, largest first, at scale 1. */
    val TITLE = listOf(32f, 30f, 28f, 26f, 24f, 22f, 20.5f, 19f, 17.5f, 16f, 14.5f)
    const val LEAD = 1.2f

    fun paint(c: Context, size: Float, colour: Int, weight: Int = 400, tracking: Float = 0f): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Kit.sans(c, weight); textSize = size; color = colour; letterSpacing = tracking
        }

    fun label(c: Context, k: Float, colour: Int) = paint(c, LABEL * k, colour, 600, 0.14f)
    fun heading(c: Context, k: Float, colour: Int) = paint(c, HEADING * k, colour, 600)
    fun statement(c: Context, k: Float, colour: Int) = paint(c, STATEMENT * k, colour, 600)
    fun primary(c: Context, k: Float, colour: Int) = paint(c, PRIMARY * k, colour, 600)
    fun meta(c: Context, k: Float, colour: Int) = paint(c, META * k, colour, 500)

    /** The height of a capital in [p], from the face itself. */
    fun cap(p: Paint): Float { val r = android.graphics.Rect(); p.getTextBounds("H", 0, 1, r); return -r.top.toFloat() }

    /** Draws [s] with the top of its capitals at [top]. */
    fun Canvas.top(s: String, x: Float, top: Float, p: Paint) = drawText(s, x, top + cap(p), p)

    /** [s] cut to [width] with an ellipsis. */
    fun fit(s: CharSequence, p: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, p, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    /** [s] in at most [lines] lines of [width], breaking only between words, ending on a word and an ellipsis. */
    fun wrap(s: String, p: TextPaint, width: Float, lines: Int): List<String> {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val out = ArrayList<String>(); var i = 0
        while (i < words.size && out.size < lines) {
            var line = words[i]; i++
            if (p.measureText(line) > width) { out += fit(line, p, width); continue }
            while (i < words.size && p.measureText("$line ${words[i]}") <= width) { line += " " + words[i]; i++ }
            out += line
        }
        if (i < words.size && out.isNotEmpty()) {
            val last = out.removeAt(out.size - 1).split(" ").toMutableList()
            while (last.size > 1 && p.measureText(last.joinToString(" ") + "…") > width) last.removeAt(last.size - 1)
            out += fit(last.joinToString(" ") + "…", p, width)
        }
        return out
    }

    /** Whether every word of [s] fits [width] and the whole fits in [lines] lines. */
    /**
     * [s] on one line of [width], cut only between words, with an ellipsis if words are left out;
     * null when not even its first word fits whole (27 Nov 2026). "Whole words only" (rule 4): the
     * caller then shows something else (a count, a shorter label) rather than "Arsena…" or "UP NE…".
     */
    fun words(s: String, p: TextPaint, width: Float): String? {
        val all = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (all.isEmpty()) return ""
        if (p.measureText(s.trim()) <= width) return s.trim()
        var n = all.size - 1
        while (n >= 1) {
            val t = all.take(n).joinToString(" ").trimEnd(',', ';', ':', '-', '–', '—', '·') + "…"
            if (p.measureText(t) <= width) return t
            n--
        }
        return null
    }

    fun fitsWhole(s: String, p: TextPaint, width: Float, lines: Int): Boolean {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.any { p.measureText(it) > width }) return false
        var n = 1; var line = ""
        for (w in words) { val next = if (line.isEmpty()) w else "$line $w"; if (p.measureText(next) <= width) line = next else { n++; line = w } }
        return n <= lines
    }

    /** A title set in a block: its paint and lines, and the height they take. */
    class Title(val paint: TextPaint, val lines: List<String>) {
        val lead get() = paint.textSize * LEAD
        val height get() = lines.size * lead
        /** Draws the lines from [top] down; returns where they end. */
        fun draw(cv: Canvas, x: Float, top: Float): Float {
            var y = top
            for (l in lines) { cv.drawText(l, x, y + (lead - paint.textSize) / 2f + cap(paint), paint); y += lead }
            return y
        }
    }

    /**
     * A title as large as the room allows (rule 7): the largest size from [TITLE] (times [k], no
     * larger than [largest]) at which the whole title fits [width] in at most [lines] lines and no
     * taller than [room]. If none does, the smallest size, ending on a whole word and an ellipsis.
     */
    fun title(c: Context, s: String, colour: Int, width: Float, room: Float, k: Float, lines: Int = 4,
              largest: Float = TITLE.first(), smallest: Float = 14.5f): Title {
        val sizes = TITLE.filter { it <= largest && it >= smallest }.map { it * k }
        for (size in sizes) {
            val p = paint(c, size, colour, 600)
            val most = (room / (size * LEAD)).toInt().coerceIn(1, lines)
            if (fitsWhole(s, p, width, most)) return Title(p, wrap(s, p, width, most))
        }
        val p = paint(c, sizes.last(), colour, 600)
        return Title(p, wrap(s, p, width, (room / (p.textSize * LEAD)).toInt().coerceIn(1, lines)))
    }

    /** The label that opens a widget (rule 5), its capitals' top at [top]. Returns where the next block may begin. */
    fun eyebrow(c: Context, cv: Canvas, s: Surface, text: String, x: Float, top: Float, width: Float, k: Float): Float {
        val p = label(c, k, s.muted(x + width / 2, top + 6f * k))
        // Whole words only (27 Nov 2026): a label that doesn't fit is left out, never cut to "UP NE…"
        val t = words(text.uppercase(), p, width) ?: return top
        cv.top(t, x, top, p)
        return top + cap(p) + GAP * k
    }

    /** The foot's progress line (rule 6), centred on [cy], from [x] for [width]. */
    fun line(cv: Canvas, s: Surface, x: Float, cy: Float, width: Float, value: Float, k: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = LINE * k }
        val r = LINE * k / 2f
        p.color = s.faint(x + width / 2, cy); cv.drawLine(x + r, cy, x + width - r, cy, p)
        if (value > 0.005f) { p.color = s.accent(x + width / 2, cy); cv.drawLine(x + r, cy, x + r + (width - 2 * r) * value.coerceIn(0f, 1f), cy, p) }
    }

    /**
     * An empty or setup state (rule 8): [title] and [note] resting on the foot at [bottom], from [x],
     * [width] wide. Returns the top of the block, so the hero's quiet form can take the room above.
     */
    fun statement(c: Context, cv: Canvas, s: Surface, title: String, note: String?, x: Float, bottom: Float, width: Float, k: Float): Float {
        val np = meta(c, k, s.muted(x + width / 2, bottom - 8f * k))
        val noteLines = note?.let { wrap(it, np, width, 2) }.orEmpty()
        val noteH = noteLines.size * np.textSize * 1.3f
        val tp = statement(c, k, s.ink(x + width / 2, bottom - noteH - 12f * k))
        val tl = wrap(title, tp, width, 2)
        val titleH = tl.size * tp.textSize * LEAD
        val gap = if (noteLines.isEmpty()) 0f else 4f * k
        var y = bottom - noteH - gap - titleH
        val top = y
        for (l in tl) { cv.drawText(l, x, y + (tp.textSize * LEAD - tp.textSize) / 2f + cap(tp), tp); y += tp.textSize * LEAD }
        y += gap
        for (l in noteLines) { cv.drawText(l, x, y + cap(np), np); y += np.textSize * 1.3f }
        return top
    }
}
