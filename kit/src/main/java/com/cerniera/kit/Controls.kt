package com.cerniera.kit

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Marks a view as peripheral: Concentrato lets it fall away while the screen moves fast. */
const val SECONDARY = "secondary"

/** A text view in the Kit's type: [size] in sp, [weight] for Jost, or the display face. */
fun Context.text(s: CharSequence, size: Float, color: Int = Kit.INK, weight: Int = 400, displayFace: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (displayFace) Kit.display(context) else Kit.sans(context, weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.28f)
    }

/** The small tracked capitals in champagne that head a section. */
fun Context.eyebrow(s: String) = text(s.uppercase(), 11.5f, Kit.CHAMPAGNE, 500).apply {
    letterSpacing = 0.2f
    tag = SECONDARY
    setPadding(px(4), px(30), 0, px(10))
}

/** A value at the end of a row: champagne when it asks for action, muted when all is well. */
fun Context.endValue(s: String, color: Int = Kit.MUTED) = text(s, 14f, color, 500)

/**
 * A screen: a scrolling column on the ground, headed by a small line, the app's name in Italiana
 * and a status pill. The column never grows wider than a comfortable reading measure, so on the
 * main screen it sits centred rather than stretched.
 *
 * The doctrine applies to the screen itself. Membrane: flung, the cards furthest from the middle
 * lag a little behind and spring back. Concentrato: above a walking pace, eyebrows and the lines
 * under titles fall away, and return as the page settles.
 */
class Screen(private val ctx: Context, name: String, over: String, maxWidthDp: Int = 620) {
    private val measure = ctx.px(maxWidthDp)

    /** Sized to the space it is given (a pane, when the main screen is split), never the whole display. */
    val column = object : LinearLayout(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = if (MeasureSpec.getSize(widthSpec) > measure) MeasureSpec.makeMeasureSpec(measure, MeasureSpec.EXACTLY) else widthSpec
            super.onMeasure(w, heightSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        val side = ctx.px(20)
        setPadding(side, ctx.px(34), side, ctx.px(40))
    }
    val root = ScrollView(ctx).apply {
        setBackgroundColor(Kit.GROUND)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(ctx).apply {
            addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        })
    }
    val status = Pill(ctx)
    val title: TextView

    private var velocity = 0f
    private var lastScrollAt = 0L
    private var secondaries: List<View> = emptyList()
    private var ticking = false
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            velocity *= 0.86f
            stretch()
            if (abs(velocity) > 0.004f) Choreographer.getInstance().postFrameCallback(this)
            else { velocity = 0f; stretch(); ticking = false }
        }
    }

    private fun collect(v: View, out: MutableList<View>) {
        if (v.tag == SECONDARY) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    private fun onScrolled(dy: Int) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastScrollAt).coerceIn(8L, 64L)
        lastScrollAt = now
        val dpPerMs = dy / ctx.resources.displayMetrics.density / dt
        if (velocity == 0f) secondaries = mutableListOf<View>().also { collect(column, it) }
        velocity += (dpPerMs - velocity) * 0.45f
        if (!ticking) { ticking = true; Choreographer.getInstance().postFrameCallback(tick) }
    }

    private fun stretch() {
        val mid = root.scrollY + root.height / 2f
        val k = ctx.px(2.5f).toFloat()
        val cap = ctx.px(7).toFloat()
        for (i in 0 until column.childCount) {
            val c = column.getChildAt(i)
            val d = abs((c.top + c.height / 2f) - mid) / root.height.coerceAtLeast(1)
            c.translationY = (velocity * k * d * 2f).coerceIn(-cap, cap)
        }
        // Only a real fling empties the page; ordinary scrolling leaves every word in place
        val a = 1f - smooth(1.8f, 3.4f, abs(velocity))
        secondaries.forEach { it.alpha = a }
    }

    init {
        // Both screens, both ways round (12 Nov 2026): the bars and the camera cut-out are kept clear
        // on every side, so a landscape screen with the bar at its side loses nothing under it
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        root.clipToPadding = false
        root.setOnScrollChangeListener { _, _, y, _, oldY -> onScrolled(y - oldY) }
        column.addView(ctx.text(over.uppercase(), 11f, Kit.MUTED, 500).apply { letterSpacing = 0.22f; setPadding(ctx.px(4), 0, 0, ctx.px(6)) })
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = ctx.text(name, 44f, Kit.INK, displayFace = true)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(status)
        status.visibility = View.GONE
        column.addView(head)
    }

    fun add(v: View, top: Int = 0) {
        column.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top })
    }

    fun section(title: String) = column.addView(ctx.eyebrow(title))
}

/** A rounded status pill: a coloured dot and a word. Hidden until first set. */
class Pill(ctx: Context) : LinearLayout(ctx) {
    private val dot = View(ctx)
    private val label = ctx.text("", 13f, Kit.INK, 500)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(12), ctx.px(7), ctx.px(14), ctx.px(7))
        background = rounded(Kit.SURFACE, ctx.px(20).toFloat(), Kit.HAIRLINE, ctx.px(1))
        addView(dot, LayoutParams(ctx.px(7), ctx.px(7)).apply { marginEnd = ctx.px(8) })
        addView(label)
    }

    fun set(text: String, color: Int) {
        visibility = VISIBLE
        label.text = text
        dot.background = rounded(color, 100f)
    }
}

/** A card: one rounded surface holding rows divided by hairlines. */
class Card(ctx: Context) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = rounded(Kit.SURFACE, ctx.px(22).toFloat(), Kit.HAIRLINE, ctx.px(1))
        clipToOutline = true
    }

    fun row(v: View) {
        if (childCount > 0) addView(View(context).apply { setBackgroundColor(Kit.HAIRLINE) }, LayoutParams(-1, context.px(1)).apply {
            marginStart = context.px(18); marginEnd = context.px(18)
        })
        addView(v, LayoutParams(-1, -2))
    }
}

/** Dims a view a touch under the finger and lifts it back after. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable() = setOnTouchListener { v, e ->
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> v.animate().alpha(0.6f).setDuration(60).start()
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(200).start()
    }
    false
}

/** A row: a title, an optional line under it, and something at the end (a switch, a value, an arrow). */
@SuppressLint("ViewConstructor")
class Row(ctx: Context, title: String, sub: String? = null, end: View? = null, onTap: (() -> Unit)? = null) : LinearLayout(ctx) {
    val titleView = ctx.text(title, 16f, Kit.INK, 450)
    val subView = ctx.text(sub ?: "", 13f, Kit.MUTED, 400)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.px(60)
        setPadding(ctx.px(18), ctx.px(14), ctx.px(16), ctx.px(14))
        val words = LinearLayout(ctx).apply { orientation = VERTICAL }
        words.addView(titleView)
        subView.tag = SECONDARY
        words.addView(subView, LayoutParams(-2, -2).apply { topMargin = ctx.px(3) })
        if (sub == null) subView.visibility = GONE
        addView(words, LayoutParams(0, -2, 1f))
        end?.let { addView(it, LayoutParams(-2, -2).apply { marginStart = ctx.px(12) }) }
        if (onTap != null) {
            isClickable = true
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onTap() }
            pressable()
        }
    }

    fun setSub(s: String?) { subView.text = s ?: ""; subView.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE }
}

/** A pill switch, drawn: champagne track when on, smoke when off, the knob on the family's curve. */
@SuppressLint("ViewConstructor")
class Toggle(ctx: Context, on: Boolean, private val changed: (Boolean) -> Unit) : View(ctx) {
    var on = on
        private set
    private var pos = if (on) 1f else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        contentDescription = "Switch"
        setOnClickListener { set(!this.on, animate = true); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); changed(this.on) }
    }

    fun set(v: Boolean, animate: Boolean = false) {
        on = v
        if (!animate) { pos = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = Kit.SETTLE; interpolator = Kit.ease
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.px(50), context.px(30))

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        r.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = blend(Kit.RAISED, Kit.CHAMPAGNE, pos)
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (pos < 0.5f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.px(1).toFloat(); p.color = Kit.HAIRLINE
            r.inset(0.5f, 0.5f); c.drawRoundRect(r, h / 2, h / 2, p)
            p.style = Paint.Style.FILL
        }
        val d = h - context.px(6)
        val x = context.px(3) + (w - d - context.px(6)) * pos
        p.color = blend(Kit.MUTED, Kit.GROUND, pos)
        c.drawCircle(x + d / 2, h / 2, d / 2, p)
    }
}

/**
 * A segmented control: options side by side in a smoked track, the chosen one carried by a
 * liquid champagne thumb. Its leading edge races to the new option and its trailing edge lets go
 * a breath later, so it stretches as it travels (principle 1).
 */
@SuppressLint("ViewConstructor")
class Segmented(ctx: Context, private val options: List<String>, selected: Int, private val picked: (Int) -> Unit) : FrameLayout(ctx) {
    var index = selected
        private set
    private var thumbX = 0f
    private var thumbR = -1f
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = mutableListOf<TextView>()
    private val r = RectF()
    private val row = LinearLayout(ctx)

    init {
        setWillNotDraw(false)
        background = rounded(Kit.RAISED, ctx.px(16).toFloat())
        setPadding(ctx.px(4), ctx.px(4), ctx.px(4), ctx.px(4))
        options.forEachIndexed { i, o ->
            val t = ctx.text(o, 14f, if (i == index) Kit.GROUND else Kit.INK, 500).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.px(6), ctx.px(10), ctx.px(6), ctx.px(10))
                setOnClickListener { choose(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            labels += t
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(row, LayoutParams(-1, -2))
    }

    private fun segW() = (width - paddingLeft - paddingRight) / options.size.toFloat()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        thumbX = paddingLeft + segW() * index
        thumbR = -1f
    }

    private fun choose(i: Int) {
        if (i == index) return
        val fromL = thumbX; val fromR = if (thumbR < 0f) thumbX + segW() else thumbR
        val toL = paddingLeft + segW() * i; val toR = toL + segW()
        val forward = i > index
        labels[index].setTextColor(Kit.INK)
        index = i
        labels[i].setTextColor(Kit.GROUND)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Kit.GLIDE
            addUpdateListener {
                val t = it.animatedFraction
                val fast = Kit.lead.getInterpolation((t / 0.85f).coerceAtMost(1f))
                val slow = Kit.trail.getInterpolation(((t - 0.06f) / 0.94f).coerceIn(0f, 1f))
                thumbX = fromL + (toL - fromL) * (if (forward) slow else fast)
                thumbR = fromR + (toR - fromR) * (if (forward) fast else slow)
                invalidate()
            }
            start()
        }
        picked(i)
    }

    /** Selects without telling the listener, for when the choice is changed elsewhere. */
    fun select(i: Int) {
        if (i == index) return
        labels[index].setTextColor(Kit.INK); index = i; labels[i].setTextColor(Kit.GROUND)
        thumbX = paddingLeft + segW() * i; thumbR = -1f; invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        thumb.color = Kit.CHAMPAGNE
        val right = if (thumbR < 0f) thumbX + segW() else thumbR
        r.set(thumbX, paddingTop.toFloat(), right, (height - paddingBottom).toFloat())
        val rad = context.px(12).toFloat()
        c.drawRoundRect(r, rad, rad, thumb)
    }
}

/** A choice among a few richer options: each a row with a painted swatch, the chosen one outlined in champagne. */
@SuppressLint("ViewConstructor")
class ChoiceList(ctx: Context, items: List<Triple<String, String, (Canvas, RectF) -> Unit>>, selected: Int, private val picked: (Int) -> Unit) : LinearLayout(ctx) {
    private var index = selected
    private val rows = mutableListOf<View>()

    init {
        orientation = VERTICAL
        items.forEachIndexed { i, (name, detail, paint) ->
            val v = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(12), ctx.px(12), ctx.px(16), ctx.px(12))
                addView(Swatch(ctx, paint), LayoutParams(ctx.px(84), ctx.px(56)))
                val words = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.px(16), 0, 0, 0) }
                words.addView(ctx.text(name, 17f, Kit.INK, 500))
                words.addView(ctx.text(detail, 13f, Kit.MUTED).apply { tag = SECONDARY }, LayoutParams(-2, -2).apply { topMargin = ctx.px(3) })
                addView(words, LayoutParams(0, -2, 1f))
                setOnClickListener { choose(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            rows += v
            addView(v, LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.px(10) })
        }
        paintRows()
    }

    private fun choose(i: Int) { if (i == index) return; index = i; paintRows(); picked(i) }

    private fun paintRows() = rows.forEachIndexed { i, v ->
        v.background = rounded(Kit.SURFACE, context.px(20).toFloat(), if (i == index) Kit.CHAMPAGNE else Kit.HAIRLINE, context.px(if (i == index) 1.5f else 1f))
    }
}

/** A small painted preview tile. */
@SuppressLint("ViewConstructor")
class Swatch(ctx: Context, private val paint: (Canvas, RectF) -> Unit) : View(ctx) {
    private val r = RectF()
    private val clip = Path()
    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(r, context.px(12).toFloat(), context.px(12).toFloat(), Path.Direction.CW)
        c.save(); c.clipPath(clip); paint(c, r); c.restore()
    }
}

/** The one main action of a screen: a champagne pill, or an outlined one for the quieter choice. */
fun Context.primary(label: String, filled: Boolean = true, onTap: () -> Unit) = text(label, 16f, if (filled) Kit.GROUND else Kit.INK, 500).apply {
    gravity = Gravity.CENTER
    setPadding(px(20), px(17), px(20), px(17))
    background = if (filled) rounded(Kit.CHAMPAGNE, px(28).toFloat()) else rounded(0, px(28).toFloat(), Kit.HAIRLINE, px(1))
    isClickable = true
    setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CONFIRM); onTap() }
    pressable()
}

/** A card that folds away: tapping its title row opens or closes the body with a soft height change. */
class Fold(ctx: Context, title: String, sub: String? = null) : LinearLayout(ctx) {
    val body = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.px(18), 0, ctx.px(18), ctx.px(18)); visibility = GONE }
    private val chevron = ctx.text("›", 22f, Kit.MUTED, 300)

    init {
        orientation = VERTICAL
        background = rounded(Kit.SURFACE, ctx.px(22).toFloat(), Kit.HAIRLINE, ctx.px(1))
        addView(Row(ctx, title, sub, chevron) { toggle() })
        addView(body, LayoutParams(-1, -2))
        chevron.rotation = 90f
    }

    private fun toggle() {
        val open = body.visibility != VISIBLE
        (rootView as? ViewGroup)?.let { android.transition.TransitionManager.beginDelayedTransition(it) }
        body.visibility = if (open) VISIBLE else GONE
        chevron.animate().rotation(if (open) 270f else 90f).setDuration(240).setInterpolator(Kit.ease).start()
    }
}

/** The doctrine, folded at the foot of the settings, followed by how this app answers each principle. */
fun Context.doctrineCard(notes: List<Pair<String, String>>): View = Fold(this, Doctrine.TITLE, Doctrine.SUBTITLE).apply {
    val ctx = this@doctrineCard
    body.addView(ctx.text(Doctrine.PREAMBLE, 14.5f, Kit.INK, 400))
    Doctrine.PRINCIPLES.forEach { (n, name, text) ->
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ctx.text(n, 26f, Kit.CHAMPAGNE, displayFace = true), LinearLayout.LayoutParams(ctx.px(30), -2))
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        words.addView(ctx.text(name, 15.5f, Kit.INK, 500))
        words.addView(ctx.text(text, 13.5f, Kit.MUTED, 400), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(4) })
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(18) })
    }
    body.addView(ctx.text(Doctrine.DECREE, 15f, Kit.CHAMPAGNE, 400).apply { setPadding(0, ctx.px(18), 0, 0) })
    body.addView(ctx.eyebrow("In this app").apply { tag = null; setPadding(0, ctx.px(26), 0, ctx.px(4)) })
    notes.forEach { (name, text) ->
        body.addView(ctx.text(name, 15f, Kit.INK, 500), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(12) })
        body.addView(ctx.text(text, 13.5f, Kit.MUTED, 400), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(3) })
    }
}

/**
 * A slider: a slim smoked track filled in champagne up to the knob, its value at the end of the
 * label. A light tick marks the designed value, and the knob settles on it when released close by.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Slider(
    ctx: Context, label: String, private val min: Float, private val max: Float, value: Float,
    private val centre: Float = (min + max) / 2f, private val step: Float = 0.05f,
    private val format: (Float) -> String, private val changed: (Float) -> Unit,
) : LinearLayout(ctx) {
    var value = value.coerceIn(min, max)
        private set
    private val readout = ctx.text("", 13.5f, Kit.CHAMPAGNE, 500)
    private val track = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(36))
        override fun onDraw(c: Canvas) {
            val pad = context.px(11).toFloat()
            val cy = height / 2f
            val th = context.px(4).toFloat()
            val x = pad + (width - 2 * pad) * fraction()
            p.color = Kit.RAISED; r.set(pad, cy - th / 2, width - pad, cy + th / 2); c.drawRoundRect(r, th, th, p)
            p.color = Kit.CHAMPAGNE; r.set(pad, cy - th / 2, x, cy + th / 2); c.drawRoundRect(r, th, th, p)
            val cx = pad + (width - 2 * pad) * ((centre - min) / (max - min))
            p.color = Kit.MUTED; c.drawCircle(cx, cy + context.px(10), context.px(1.5f).toFloat(), p)
            p.color = Kit.INK; c.drawCircle(x, cy, context.px(10).toFloat(), p)
            p.color = Kit.GROUND; c.drawCircle(x, cy, context.px(3.5f).toFloat(), p)
        }
    }

    private fun fraction() = (value - min) / (max - min)

    init {
        orientation = VERTICAL
        setPadding(ctx.px(18), ctx.px(14), ctx.px(18), ctx.px(8))
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        head.addView(ctx.text(label, 14.5f, Kit.INK, 450), LayoutParams(0, -2, 1f))
        head.addView(readout)
        addView(head)
        addView(track, LayoutParams(-1, -2).apply { topMargin = ctx.px(4) })
        readout.text = format(this.value)
        track.setOnTouchListener { v, e ->
            val pad = context.px(11).toFloat()
            val f = ((e.x - pad) / (v.width - 2 * pad)).coerceIn(0f, 1f)
            var nv = min + (max - min) * f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP -> if (abs(nv - centre) < (max - min) * 0.04f) { nv = centre; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            val stepped = (Math.round(nv / step) * step).coerceIn(min, max)
            if (stepped != this.value) { this.value = stepped; readout.text = format(stepped); v.invalidate() }
            if (e.actionMasked == MotionEvent.ACTION_UP) changed(this.value)
            true
        }
    }
}

/** A row of chips that scrolls sideways: for choices with too many options for a segmented control. */
@SuppressLint("ViewConstructor")
class Chips(ctx: Context, options: List<String>, selected: Int, private val picked: (Int) -> Unit) : HorizontalScrollView(ctx) {
    private var index = selected
    private val chips = mutableListOf<TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, o ->
            val c = ctx.text(o, 14f, Kit.INK, 500).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.px(16), ctx.px(10), ctx.px(16), ctx.px(10))
                setOnClickListener { choose(i); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            chips += c
            row.addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = ctx.px(8) })
        }
        addView(row)
        paint()
    }

    private fun paint() = chips.forEachIndexed { i, c ->
        val on = i == index
        c.setTextColor(if (on) Kit.GROUND else Kit.INK)
        c.background = rounded(if (on) Kit.CHAMPAGNE else Kit.RAISED, context.px(20).toFloat())
    }

    private fun choose(i: Int) { if (i == index) return; index = i; paint(); picked(i) }

    /** Selects without telling the listener; -1 for none. */
    fun select(i: Int) { index = i; paint() }
}

/** A large figure with a small label beneath: for the one number a screen exists to show. */
fun Context.figure(value: String, label: String, color: Int = Kit.INK): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(text(value, 34f, color, displayFace = true))
    addView(text(label.uppercase(), 10.5f, Kit.MUTED, 500).apply { letterSpacing = 0.18f; tag = SECONDARY; setPadding(0, px(4), 0, 0) })
}

/** A text field in the Kit's type on a raised surface; [numeric] brings up the number pad. */
fun Context.field(hint: String, value: String = "", numeric: Boolean = false, decimal: Boolean = false): android.widget.EditText =
    android.widget.EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 16f
        setTextColor(Kit.INK)
        setHintTextColor(Kit.MUTED)
        typeface = Kit.sans(context, 450)
        background = rounded(Kit.RAISED, px(14).toFloat(), Kit.HAIRLINE, px(1))
        setPadding(px(16), px(14), px(16), px(14))
        isSingleLine = true
        inputType = when {
            decimal -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            numeric -> android.text.InputType.TYPE_CLASS_NUMBER
            else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
    }

/**
 * A sheet that rises from the foot of the screen over a scrim, on the family's curve. Tapping the
 * scrim or calling [dismiss] lowers it. Laid over the Activity's content, so no stock dialog frame.
 */
class Sheet private constructor(private val host: ViewGroup, private val scrim: View, private val panel: LinearLayout, val body: LinearLayout, private val onClose: (() -> Unit)?) {

    fun dismiss() {
        scrim.animate().alpha(0f).setDuration(Kit.QUICK).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(Kit.QUICK).setInterpolator(Kit.ease).withEndAction {
            host.removeView(scrim); host.removeView(panel); onClose?.invoke()
        }.start()
    }

    companion object {
        /**
         * [action], when given, is the sheet's main button, set in the header beside the title so it
         * is always in reach however long the sheet grows.
         */
        fun show(a: android.app.Activity, title: String, onClose: (() -> Unit)? = null, action: Pair<String, (Sheet) -> Unit>? = null, build: (Sheet) -> Unit): Sheet {
            val host = a.findViewById<ViewGroup>(android.R.id.content)
            val scrim = View(a).apply { setBackgroundColor(Kit.SCRIM); alpha = 0f; isClickable = true }
            val panel = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Kit.SURFACE, a.px(28).toFloat(), Kit.HAIRLINE, a.px(1))
                setPadding(a.px(22), a.px(14), a.px(22), 0)
                isClickable = true
                addView(View(a).apply { background = rounded(Kit.HAIRLINE, 100f) }, LinearLayout.LayoutParams(a.px(36), a.px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.px(14), 0, a.px(10)) }
            head.addView(a.text(title, 28f, Kit.INK, displayFace = true), LinearLayout.LayoutParams(0, -2, 1f))
            val actionView = action?.let { (label, _) ->
                a.text(label, 15f, Kit.GROUND, 500).apply {
                    setPadding(a.px(20), a.px(11), a.px(20), a.px(11))
                    background = rounded(Kit.CHAMPAGNE, a.px(22).toFloat())
                    isClickable = true
                    pressable()
                }.also { head.addView(it) }
            }
            panel.addView(head)
            // The body scrolls, so a tall sheet never pushes its own Save off the screen
            val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, a.px(28)) }
            val room = host.height.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels
            val maxH = (room * 0.9f).toInt() - a.px(96)
            val scroll = object : ScrollView(a) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
            val sheet = Sheet(host, scrim, panel, body, onClose)
            scrim.setOnClickListener { sheet.dismiss() }
            actionView?.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); action?.second?.invoke(sheet) }
            build(sheet)
            host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            val w = minOf(a.resources.displayMetrics.widthPixels, a.px(620))
            host.addView(panel, FrameLayout.LayoutParams(w, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            panel.translationY = a.resources.displayMetrics.heightPixels.toFloat()
            panel.post {
                panel.translationY = panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(Kit.GLIDE).setInterpolator(Kit.ease).start()
                scrim.animate().alpha(1f).setDuration(Kit.SETTLE).start()
            }
            return sheet
        }
    }
}
