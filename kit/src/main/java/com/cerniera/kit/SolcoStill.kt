package com.cerniera.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Solco's still (27 Nov 2026). Android gives no app a picture of a live wallpaper, so over Solco the
 * glass finishes had nothing to frost or read: Vetro, Cristallo and Goccia looked alike, and words could
 * lose their contrast where a ring of light grain passed behind them. Solco now saves a still of what it
 * draws, one per screen (cover and main), and shares it with Cerniera's apps through its provider
 * (content://com.fergusonconsultancy.solco.still). Fold Widgets reads it as its glass's picture and hands
 * it on to the family as it does a still wallpaper.
 */
object SolcoStill {
    const val PACKAGE = "com.fergusonconsultancy.solco"
    const val AUTHORITY = "com.fergusonconsultancy.solco.still"

    /** The version of the still for one screen, or null when Solco has none to give. */
    fun stamp(c: Context, cover: Boolean): Long? = runCatching {
        c.contentResolver.query(Uri.parse("content://$AUTHORITY/still"), null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) return null
            cur.getLong(cur.getColumnIndexOrThrow(if (cover) "cover" else "main")).takeIf { it > 0 }
        }
    }.getOrNull()

    fun read(c: Context, cover: Boolean): Bitmap? = runCatching {
        c.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/still/${if (cover) "cover" else "main"}"))?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
