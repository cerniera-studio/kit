package com.cerniera.kit

import android.util.SizeF

/**
 * The sizes to draw a widget's pictures at (27 Nov 2026).
 *
 * A launcher lists the sizes a widget may be shown at, and Android shows the picture for the largest
 * listed size that fits the widget's real box, or, if none fits, the smallest one shrunk to fit. In
 * Smart Launcher's widget stacks none of the listed sizes fitted, so widgets sat shrunk inside their
 * space with empty bands beside them (Mechack's recording; the audit's "stack" scene reproduced it for
 * the weather, the music and Dorso). A picture at what every listed size has in common, the narrowest
 * width by the shortest height, always fits and is nearest the real shape. It is drawn denser by how
 * much it may be enlarged, so it stays sharp.
 */
object WidgetSizes {
    /** [sizes] with the shared size added when it differs from them all; each with its density factor. */
    fun withShared(sizes: List<SizeF>): List<Pair<SizeF, Float>> {
        val out = sizes.map { it to 1f }.toMutableList()
        if (sizes.size < 2) return out
        val shared = SizeF(sizes.minOf { it.width }, sizes.minOf { it.height })
        if (sizes.any { Math.abs(it.width - shared.width) < 1f && Math.abs(it.height - shared.height) < 1f }) return out
        val up = minOf(sizes.maxOf { it.width } / shared.width, sizes.maxOf { it.height } / shared.height).coerceIn(1f, 1.5f)
        out += shared to up
        return out
    }
}
