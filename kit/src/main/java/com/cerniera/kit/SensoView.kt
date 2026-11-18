package com.cerniera.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * What Senso sees (Fluido, phase 0): the debug view any app can show. What is offered now, every
 * moment with its confidence against the threshold and the lines that built it, every signal
 * with where it came from, and the last changes. Live while it is on screen.
 */
@SuppressLint("ViewConstructor")
class SensoView(ctx: Context, private val senso: Senso) : LinearLayout(ctx) {
    private val watcher: (Senso) -> Unit = { render() }

    init { orientation = VERTICAL }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); senso.watch(watcher); render() }
    override fun onDetachedFromWindow() { senso.unwatch(watcher); super.onDetachedFromWindow() }

    private fun render() {
        removeAllViews()
        val c = context
        val o = senso.offered

        // Offering: the one thing Senso would say now, or silence
        addView(c.eyebrow("Offering").apply { setPadding(c.px(4), c.px(8), 0, c.px(10)) })
        addView(Card(c).apply {
            row(Row(c, o?.kind?.label ?: "Silent", o?.why ?: "Nothing above ${(Rules.THRESHOLD * 100).toInt()}%: Senso keeps quiet",
                c.endValue(o?.let { "${it.percent}%" } ?: "—", if (o != null) Kit.CHAMPAGNE else Kit.MUTED)))
        })

        // Moments: all six, the ones this app can act on first
        addView(c.eyebrow("Moments"))
        val byKind = senso.moments.associateBy { it.kind }
        val order = senso.kinds.toList() + Kind.entries.filter { it !in senso.kinds }
        addView(Card(c).apply {
            order.forEach { k ->
                val m = byKind[k]
                val sub = when {
                    m == null -> "Not seen" + if (k !in senso.kinds) " · not used by ${senso.appName}" else ""
                    else -> (listOf(m.why) + m.reasons).joinToString("\n") +
                        (if (m.dismissed) "\nDismissed for today" else "") +
                        (if (k !in senso.kinds) "\nNot used by ${senso.appName}" else "")
                }
                val end = LinearLayout(c).apply {
                    orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Meter(c, m?.confidence ?: 0f, m != null && m.confidence >= Rules.THRESHOLD && !m.dismissed), LayoutParams(c.px(64), c.px(12)))
                    addView(c.endValue(m?.let { "${it.percent}%" } ?: "0%", if (o?.kind == k) Kit.CHAMPAGNE else Kit.MUTED).apply {
                        minWidth = c.px(40); gravity = Gravity.END
                    })
                }
                row(Row(c, k.label + if (o?.kind == k) "  ·  offered" else "", sub, end).apply {
                    titleView.setTextColor(if (m == null) Kit.MUTED else Kit.INK)
                })
            }
        }, LayoutParams(-1, -2))

        // Signals, each with its source and age
        addView(c.eyebrow("Signals"))
        val now = senso.now()
        addView(Card(c).apply {
            senso.readings.forEach { r ->
                val age = r.at?.let { val m = (now - it) / 60_000L; if (m < 1) " · just now" else " · $m min ago" } ?: ""
                row(Row(c, r.name, r.source + age, c.endValue(r.value, if (r.source == "Injected") Kit.CHAMPAGNE else Kit.MUTED)))
            }
        }, LayoutParams(-1, -2))

        // The last changes, and the one way to undo dismissals
        addView(c.eyebrow("Recent"))
        addView(Card(c).apply {
            val lines = senso.log.take(12).ifEmpty { listOf("Nothing yet") }
            row(c.text(lines.joinToString("\n"), 12.5f, Kit.MUTED).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(c.px(18), c.px(14), c.px(16), c.px(14))
            })
            val dismissed = senso.dismissedToday()
            if (dismissed.isNotEmpty()) row(Row(c, "Clear today's dismissals", dismissed.joinToString { it.label }, c.endValue("Clear", Kit.CHAMPAGNE)) {
                senso.undismissAll()
            })
        }, LayoutParams(-1, -2))
    }

    /** A confidence meter: a hairline track, the threshold as a tick, champagne once it passes. */
    private class Meter(ctx: Context, private val value: Float, private val lit: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val h = context.px(4).toFloat()
            val y = height / 2f
            r.set(0f, y - h / 2, width.toFloat(), y + h / 2)
            p.color = Kit.HAIRLINE; canvas.drawRoundRect(r, h / 2, h / 2, p)
            if (value > 0f) {
                r.right = width * value.coerceIn(0f, 1f)
                p.color = if (lit) Kit.CHAMPAGNE else Kit.MUTED
                canvas.drawRoundRect(r, h / 2, h / 2, p)
            }
            val x = width * Rules.THRESHOLD
            p.color = Kit.INK; p.strokeWidth = context.px(1).toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), p)
        }
    }
}
