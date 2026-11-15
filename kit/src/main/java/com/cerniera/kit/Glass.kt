package com.cerniera.kit

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The squircle: corners that ease into the straight edges instead of meeting them at a circle's
 * sudden change of curvature. The references' slabs, and every widget's outline here.
 */
object Shapes {
    /** A continuous-corner rectangle whose corners take about the same room as a circle of [radius]. */
    fun squircle(r: RectF, radius: Float): Path {
        val s = (radius * 1.3f).coerceAtMost(min(r.width(), r.height()) / 2f)
        val k = 0.26f * s  // handles close to the corner: a long, gentle run into each edge
        return Path().apply {
            moveTo(r.left + s, r.top)
            lineTo(r.right - s, r.top)
            cubicTo(r.right - k, r.top, r.right, r.top + k, r.right, r.top + s)
            lineTo(r.right, r.bottom - s)
            cubicTo(r.right, r.bottom - k, r.right - k, r.bottom, r.right - s, r.bottom)
            lineTo(r.left + s, r.bottom)
            cubicTo(r.left + k, r.bottom, r.left, r.bottom - k, r.left, r.bottom - s)
            lineTo(r.left, r.top + s)
            cubicTo(r.left, r.top + k, r.left + k, r.top, r.left + s, r.top)
            close()
        }
    }
}

/**
 * Cristallo's lens. A slab of glass is flat in the middle and curves away at its rim, and a curved
 * rim bends light: looking into it near an edge you see what lies just beyond that edge, drawn
 * inwards and compressed. So each pixel within the bevel takes its colour from further out, more
 * strongly the closer it is to the edge.
 *
 * The force that put it there is refraction. Real glass also splits colour at the rim; that fringe
 * is left out on purpose, because it reads as glitter (the lesson of Frame of Light).
 */
object Lens {
    /**
     * [src] reaches [margin] px beyond the widget on every side; the result is the widget's own
     * area as seen through the slab. [radius] is the corner radius in [src]'s px.
     */
    fun refract(src: Bitmap, margin: Int, radius: Float): Bitmap {
        val sw = src.width; val sh = src.height
        val w = (sw - 2 * margin).coerceAtLeast(1); val h = (sh - 2 * margin).coerceAtLeast(1)
        val px = IntArray(sw * sh).also { src.getPixels(it, 0, sw, 0, 0, sw, sh) }
        val out = IntArray(w * h)
        val bevel = 0.15f * min(w, h)
        val reach = (margin - 1.5f).coerceAtLeast(0f)   // the furthest the rim looks out
        val r = radius.coerceIn(0f, min(w, h) / 2f)
        val hw = w / 2f; val hh = h / 2f

        /** The colour at a point between pixels, bilinear, clamped to the picture. */
        fun sample(x: Float, y: Float): Int {
            val fx = x.coerceIn(0f, sw - 1.001f); val fy = y.coerceIn(0f, sh - 1.001f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val tx = fx - x0; val ty = fy - y0
            val i = y0 * sw + x0
            fun mix(shift: Int): Int {
                fun c(p: Int) = (p shr shift) and 255
                val top = c(px[i]) * (1 - tx) + c(px[i + 1]) * tx
                val bottom = c(px[i + sw]) * (1 - tx) + c(px[i + sw + 1]) * tx
                return (top * (1 - ty) + bottom * ty).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        for (y in 0 until h) for (x in 0 until w) {
            val cx = x + 0.5f - hw; val cy = y + 0.5f - hh
            // Signed distance to the rounded edge, negative inside
            val qx = abs(cx) - (hw - r); val qy = abs(cy) - (hh - r)
            val ox = max(qx, 0f); val oy = max(qy, 0f)
            val outside = sqrt(ox * ox + oy * oy)
            val inside = -(outside + min(max(qx, qy), 0f) - r)
            val sx = x + margin + 0.5f; val sy = y + margin + 0.5f
            if (inside >= bevel) { out[y * w + x] = px[(y + margin) * sw + x + margin]; continue }
            // The outward normal of the nearest stretch of edge
            var nx: Float; var ny: Float
            if (outside > 1e-3f) { nx = ox / outside; ny = oy / outside } else if (qx > qy) { nx = 1f; ny = 0f } else { nx = 0f; ny = 1f }
            if (cx < 0) nx = -nx
            if (cy < 0) ny = -ny
            val t = 1f - (inside / bevel).coerceIn(0f, 1f)
            val push = reach * t * t * sqrt(t)
            out[y * w + x] = sample(sx + nx * push, sy + ny * push)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Goccia's lens (15 Nov 2026). A drop is domed, not a slab with a bevel: its surface curves all the
 * way from the middle to the rim, steepest at the rim. So the middle magnifies a little, as any
 * convex glass does, and across a wide rim each pixel looks further and further out, until at the
 * very edge it sees what lies [margin] beyond it, drawn in and compressed. Where the glass is
 * thickest at the rim it also gathers light, so the bent picture there is a touch lighter.
 *
 * As with Cristallo, no colour fringe: it reads as glitter.
 */
fun Lens.drop(src: Bitmap, margin: Int, radius: Float): Bitmap {
    val sw = src.width; val sh = src.height
    val w = (sw - 2 * margin).coerceAtLeast(1); val h = (sh - 2 * margin).coerceAtLeast(1)
    val px = IntArray(sw * sh).also { src.getPixels(it, 0, sw, 0, 0, sw, sh) }
    val out = IntArray(w * h)
    val rimW = 0.26f * min(w, h)                     // how far in from the edge the dome steepens
    val reach = (margin - 1.5f).coerceAtLeast(0f)
    val r = radius.coerceIn(0f, min(w, h) / 2f)
    val hw = w / 2f; val hh = h / 2f
    val mag = 1.05f                                   // the middle's magnification

    fun sample(x: Float, y: Float): Int {
        val fx = x.coerceIn(0f, sw - 1.001f); val fy = y.coerceIn(0f, sh - 1.001f)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val tx = fx - x0; val ty = fy - y0
        val i = y0 * sw + x0
        fun mix(shift: Int): Float {
            fun c(p: Int) = (p shr shift) and 255
            val top = c(px[i]) * (1 - tx) + c(px[i + 1]) * tx
            val bottom = c(px[i + sw]) * (1 - tx) + c(px[i + sw + 1]) * tx
            return top * (1 - ty) + bottom * ty
        }
        return (0xFF shl 24) or (mix(16).toInt() shl 16) or (mix(8).toInt() shl 8) or mix(0).toInt()
    }

    for (y in 0 until h) for (x in 0 until w) {
        val cx = x + 0.5f - hw; val cy = y + 0.5f - hh
        val qx = abs(cx) - (hw - r); val qy = abs(cy) - (hh - r)
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        val outside = sqrt(ox * ox + oy * oy)
        val inside = -(outside + min(max(qx, qy), 0f) - r)
        // The middle: magnified about the centre
        var sx = margin + hw + cx / mag; var sy = margin + hh + cy / mag
        var lift = 0f
        if (inside < rimW) {
            var nx: Float; var ny: Float
            if (outside > 1e-3f) { nx = ox / outside; ny = oy / outside } else if (qx > qy) { nx = 1f; ny = 0f } else { nx = 0f; ny = 1f }
            if (cx < 0) nx = -nx
            if (cy < 0) ny = -ny
            // The dome's fall towards the rim: a quarter circle, steepening all the way out
            val t = 1f - (inside / rimW).coerceIn(0f, 1f)
            val fall = 1f - sqrt((1f - t * t).coerceAtLeast(0f))
            // From the magnified middle outwards to the furthest the rim can see, smoothly
            val edgeX = margin + hw + cx; val edgeY = margin + hh + cy
            sx += (edgeX - sx) * t + nx * reach * fall
            sy += (edgeY - sy) * t + ny * reach * fall
            lift = 0.16f * fall
        }
        var p = sample(sx, sy)
        if (lift > 0f) {
            fun up(v: Int) = (v + (255 - v) * lift).toInt().coerceIn(0, 255)
            p = (0xFF shl 24) or (up((p shr 16) and 255) shl 16) or (up((p shr 8) and 255) shl 8) or up(p and 255)
        }
        out[y * w + x] = p
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/**
 * Light that keeps the time of day (the charter, 19 Oct): cooler and brighter by day, warmer and
 * dimmer at night, which also spares the eyes. It colours the glass's one highlight and the sheen
 * where light enters the slab. The widgets are redrawn on the hour so it follows the day.
 */
object Daylight {
    /** 1 in full day, 0 in full night, easing through dawn (6 to 8) and dusk (18 to 21). */
    fun level(hour: Float = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) + it.get(java.util.Calendar.MINUTE) / 60f }): Float = when {
        hour < 6f || hour >= 21f -> 0f
        hour < 8f -> (hour - 6f) / 2f
        hour < 18f -> 1f
        else -> 1f - (hour - 18f) / 3f
    }

    /** The highlight's colour with [alpha]: a cool ivory by day, a warm amber at night. */
    fun light(alpha: Int, day: Float = level()): Int {
        fun lerp(a: Int, b: Int) = (a + (b - a) * day).toInt()
        val a = (alpha * (0.62f + 0.38f * day)).toInt().coerceIn(0, 255)
        return (a shl 24) or (lerp(0xFF, 0xF4) shl 16) or (lerp(0xD9, 0xF6) shl 8) or lerp(0xA8, 0xFF)
    }
}
