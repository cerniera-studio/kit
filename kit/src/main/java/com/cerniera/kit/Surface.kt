package com.cerniera.kit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * The finish of one widget, painted, and the tones its marks should take. Each zone of the ground
 * gets the finish's face for that zone; the zones are drawn as a tiny grid and stretched with
 * smoothing, so they blend into one continuous gradient, as the taskbar's do.
 */
/**
 * Everything is measured in dp: the widget's canvas is scaled once, so [w], [h], [radius] and the
 * [hairline] (one physical pixel or so, in dp) are all dp.
 */
class Surface(val finish: Finish, val ground: Ground, val w: Float, val h: Float, val radius: Float, val hairline: Float) {

    /**
     * What the finish is judged and tinted against (27 Nov 2026). Glass shows what is behind it, so
     * it reads the widget's own slice of wallpaper. Every other finish takes its tone from the
     * wallpaper as a whole, the same colour in every app, so two widgets in the same finish match
     * wherever they sit and whether or not their place is known yet. Before, a solid finish took a
     * trace of the slice behind it when the widget's place was known and the wallpaper's overall
     * colour when it wasn't, so Rosso, Smoked and Ceramica came out in different tones side by side.
     */
    private val g: Ground = if (glassy) ground else Ground.flat(ground.family)

    private val faces: Array<Face> = legibleFaces(finish, g, throughBlur = finish == Finish.VETRO && g.seated && g.frost != null)

    /** The face under a point, as fractions of the widget's width and height. */
    fun faceAt(fx: Float, fy: Float): Face {
        val x = (fx * Ground.COLS).toInt().coerceIn(0, Ground.COLS - 1)
        val y = (fy * Ground.ROWS).toInt().coerceIn(0, Ground.ROWS - 1)
        return faces[y * Ground.COLS + x]
    }

    /** Ink for a mark whose centre is at ([x], [y]) px. */
    fun ink(x: Float, y: Float) = faceAt(x / w, y / h).ink
    /** Secondary ink: the same tone, quieter. */
    fun muted(x: Float, y: Float) = faceAt(x / w, y / h).let { (it.ink and 0x00FFFFFF) or (it.mutedAlpha shl 24) }
    /** Faint ink, for tracks and wells. */
    fun faint(x: Float, y: Float) = (ink(x, y) and 0x00FFFFFF) or 0x24000000
    /** The finish's light: champagne over dark faces, bronze over light ones. */
    fun accent(x: Float, y: Float) = faceAt(x / w, y / h).glow
    /** Whether the ink at a point is dark, i.e. the face there is light. */
    fun darkInk(x: Float, y: Float) = lum(ink(x, y)) < 0.5f

    val shape: Path = Shapes.squircle(RectF(0f, 0f, w, h), radius)

    /** The same glass measured in units [k] times larger: for content drawn on a canvas scaled by [k]. */
    fun scaled(k: Float) = if (k == 1f) this else Surface(finish, ground, w / k, h / k, radius / k, hairline / k)

    val glassy get() = finish == Finish.VETRO || finish == Finish.CRISTALLO || finish == Finish.GOCCIA

    fun paint(c: Canvas) {
        c.save()
        c.clipPath(shape)
        when (finish) {
            Finish.VETRO -> frost(c)
            Finish.CRISTALLO -> crystal(c)
            Finish.GOCCIA -> drop(c)
            Finish.SMOKE -> { zones(c, Face::fill); edges(c) }
            Finish.OTTONE -> brushed(c)
            else -> zones(c, Face::fill)
        }
        c.restore()
        when {
            finish == Finish.GOCCIA -> meniscus(c)
            glassy || finish == Finish.OTTONE -> bevel(c)
            else -> rim(c)
        }
    }

    /** The zones' [part] as a smooth field over the whole widget. */
    private fun zones(c: Canvas, part: (Face) -> Int) {
        val grid = Bitmap.createBitmap(Ground.COLS, Ground.ROWS, Bitmap.Config.ARGB_8888)
        for (z in faces.indices) grid.setPixel(z % Ground.COLS, z / Ground.COLS, part(faces[z]))
        c.drawBitmap(stretch(grid), null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * Scaling a 4 x 4 grid straight up smears its outer zones into hard bands; scaling it in two
     * steps through a middle size gives an even, soft field.
     */
    private fun stretch(grid: Bitmap): Bitmap {
        val mid = Bitmap.createScaledBitmap(grid, Ground.COLS * 4, Ground.ROWS * 4, true)
        return Bitmap.createScaledBitmap(mid, Ground.COLS * 16, Ground.ROWS * 16, true)
    }

    /**
     * Vetro, frosted. Seated, the wallpaper behind the widget heavily blurred and a touch richer,
     * under a veil tinted by the ground itself (green glass over a green wallpaper), and a soft
     * sheen falling from the top where the light enters. Not yet seated, the veil alone, which is
     * translucent: clear glass rather than frosted.
     */
    private fun frost(c: Canvas) {
        ground.frost?.takeIf { ground.seated }?.let { pic ->
            val rich = ColorMatrix().apply { setSaturation(1.2f) }
            c.drawBitmap(pic, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(rich) })
        }
        zones(c, Face::fill)
        sheen(c, 0x1F)
    }

    /**
     * Cristallo, clear. The wallpaper barely softened, seen through a slab whose rim bends in what
     * lies beyond the edge, under a light veil. Not yet seated it has nothing to bend, so it is the
     * veil over the real wallpaper.
     */
    private fun crystal(c: Canvas) {
        val clear = ground.clear?.takeIf { ground.seated }
        if (clear != null) {
            val innerW = (clear.width - 2 * ground.margin).coerceAtLeast(1)
            val lens = Lens.refract(clear, ground.margin, radius * 1.3f * innerW / w)
            c.drawBitmap(lens, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        zones(c, Face::fill)
        sheen(c, 0x14)
    }

    /**
     * Goccia, the drop. The wallpaper through a thick domed lens: magnified a touch in the middle,
     * drawn in from beyond the edge across a wide rim, and a little lighter where the glass is
     * thickest at the rim. Not yet seated there is nothing to bend: the veil over the real wallpaper.
     */
    private fun drop(c: Canvas) {
        val clear = (ground.sharp ?: ground.clear)?.takeIf { ground.seated }
        if (clear != null) {
            val innerW = (clear.width - 2 * ground.margin).coerceAtLeast(1)
            val lens = Lens.drop(clear, ground.margin, radius * 1.3f * innerW / w)
            c.drawBitmap(lens, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        zones(c, Face::fill)
    }

    /**
     * Goccia's rim. Light runs right round a drop: a bright line all along the edge, brightest at
     * the upper left where the light enters, fading round the sides, and returning faintly at the
     * lower right, where the light that crossed the drop leaves it. Inside the line, a broad soft
     * glow where the dome's curve catches it, and just within that a faint shade, the curve turning
     * away, which is what makes it read as thick rather than flat. Its colour keeps the time of day.
     */
    private fun meniscus(c: Canvas) {
        val st = maxOf(hairline * 2.2f, 1.5f)
        val inset = st / 2f
        val rim = Shapes.squircle(RectF(inset, inset, w - inset, h - inset), radius - inset)
        val m = minOf(w, h)
        // Entering at the upper left, gone across the middle, back faintly at the lower right
        val sweep = { enter: Int, exit: Int, floor: Int ->
            LinearGradient(0f, 0f, w, h, intArrayOf(Daylight.light(enter), Daylight.light(floor), Daylight.light(floor), Daylight.light(exit)),
                floatArrayOf(0f, 0.42f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }
        c.save(); c.clipPath(shape)
        // The shade just inside the glow: the dome turning away from the light
        c.drawPath(Shapes.squircle(RectF(m * 0.07f, m * 0.07f, w - m * 0.07f, h - m * 0.07f), (radius - m * 0.07f).coerceAtLeast(0f)), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = m * 0.05f
            maskFilter = android.graphics.BlurMaskFilter(m * 0.05f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            color = 0x14000000
        })
        // The glow inside the rim, broad and soft
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = m * 0.09f
            maskFilter = android.graphics.BlurMaskFilter(m * 0.05f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            shader = sweep(0x8C, 0x47, 0x1A)
        })
        c.restore()
        // The rim itself, never quite gone, so the drop keeps its outline on any ground
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = st
            shader = sweep(0xF2, 0xA6, 0x4D)
        })
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = hairline; color = 0x26FFFFFF
        })
    }

    /**
     * Ottone, brushed brass. The brass itself, then light falling across the grain in soft upright
     * bands (brushed metal throws its light at right angles to the grain), brightest towards the
     * upper left and keeping the time of day, then the grain: hairlines drawn straight across,
     * each a little longer or shorter, lighter or darker, than the last. The bands only lighten,
     * so the ink keeps the contrast the legibility rule proved on the plain brass; the dark grain
     * is a hairline, never a field. The grain is seeded, so the same widget always shows the same
     * metal and a redraw never shimmers.
     */
    private fun brushed(c: Canvas) {
        zones(c, Face::fill)
        val stops = intArrayOf(0x30, 0x0C, 0x24, 0x04, 0x1A, 0x00, 0x12)
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w, 0f, IntArray(stops.size) { Daylight.light(stops[it]) },
                floatArrayOf(0f, 0.16f, 0.3f, 0.47f, 0.66f, 0.82f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, Daylight.light(0x1C), Daylight.light(0), Shader.TileMode.CLAMP)
        })
        val rnd = java.util.Random(0x0770E)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = hairline }
        var y = hairline / 2f
        while (y < h) {
            line.color = if (rnd.nextInt(3) > 0) ((0x08 + rnd.nextInt(0x18)) shl 24) or 0xFFF4DE
                else ((0x05 + rnd.nextInt(0x0C)) shl 24) or 0x3A2810
            val x0 = (rnd.nextFloat() * 0.4f - 0.15f) * w
            c.drawLine(x0, y, x0 + w * (0.45f + rnd.nextFloat() * 0.9f), y, line)
            y += hairline * (0.9f + rnd.nextFloat() * 1.5f)
        }
    }

    /**
     * Smoked glass is darker towards its edges, where light crosses more of it: a soft fall of
     * shade from the middle outwards. The force is the glass's thickness.
     */
    private fun edges(c: Canvas) {
        val r = maxOf(w, h) * 0.75f
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = android.graphics.RadialGradient(w / 2f, h * 0.45f, r, intArrayOf(0x00000000, 0x00000000, 0x47000000),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** Light entering the top of the slab, fading by half way down. */
    private fun sheen(c: Canvas, alpha: Int) {
        val top = Daylight.light(alpha)
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.5f, top, top and 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    /**
     * The glass's edge, lit from the upper left as every icon in the family is. One highlight: a
     * warm line along the rim, strongest at the upper-left corner and gone by the far corner, with
     * a soft glow inside it where the bevel catches the light. Its colour keeps the time of day.
     */
    private fun bevel(c: Canvas) {
        val st = maxOf(hairline * 1.4f, 0.9f)
        val inset = st / 2f
        val rim = Shapes.squircle(RectF(inset, inset, w - inset, h - inset), radius - inset)
        val diag = { a: Int, b: Int ->
            LinearGradient(0f, 0f, w, h, intArrayOf(Daylight.light(a), Daylight.light(b), Daylight.light(0), Daylight.light(0)),
                floatArrayOf(0f, 0.34f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        // The glow inside the bevel: a broad, blurred stroke, kept inside the glass
        c.save(); c.clipPath(shape)
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = minOf(w, h) * 0.05f
            maskFilter = android.graphics.BlurMaskFilter(minOf(w, h) * 0.035f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            shader = diag(0x4D, 0x0F)
        })
        c.restore()
        // The rim itself: lit at the upper left, and where the light has gone, the faintest edge
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = st
            shader = diag(0xE6, 0x40)
        })
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = hairline; color = 0x1AFFFFFF
        })
    }

    /**
     * The edge of the solid finishes: a hairline in each zone's edge tone.
     */
    private fun rim(c: Canvas) {
        val stroke = hairline
        val inset = stroke / 2f
        val r = RectF(inset, inset, w - inset, h - inset)
        val grid = Bitmap.createBitmap(Ground.COLS, Ground.ROWS, Bitmap.Config.ARGB_8888)
        for (z in faces.indices) grid.setPixel(z % Ground.COLS, z / Ground.COLS, faces[z].edge)
        val field = stretch(grid)
        val shader = BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / field.width, h / field.height) })
        }
        c.drawPath(Shapes.squircle(r, radius - inset), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; this.shader = shader
        })
    }
}
