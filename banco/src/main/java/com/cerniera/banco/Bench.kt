package com.cerniera.banco

import android.app.Activity
import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File

/**
 * Banco, the audit bench (27 Nov 2026).
 *
 * A home screen that hosts Cerniera's widgets for real, through AppWidgetHost, at exact sizes and
 * places, over whatever wallpaper is set: what a launcher shows, RemoteViews, lists, scaling and all.
 * The audit script drives it with `am start --es cmd …` and photographs the screen.
 *
 *  - cmd=providers: lists every widget installed (files/providers.txt).
 *  - cmd=wall, file=<name in files/>: sets that image as the home and lock wallpaper.
 *  - cmd=place, spec="label|pkg/cls|x|y|w|h;…" (dp, from the top left of the screen): removes the
 *    widgets placed before, places these, and writes where each landed, in px, to files/bounds.txt.
 *  - cmd=clear: removes every widget.
 * Each command ends by writing files/done-<seq>, so the script knows when to look.
 */
class Bench : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var stage: FrameLayout
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        host = AppWidgetHost(this, HOST_ID)
        stage = FrameLayout(this)
        setContentView(stage)
        handle(intent)
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); runCatching { host.stopListening() } }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handle(i) }

    private fun handle(i: Intent?) {
        val cmd = i?.getStringExtra("cmd") ?: return
        val seq = i.getStringExtra("seq") ?: "0"
        Log.i(TAG, "cmd $cmd seq $seq")
        runCatching {
            when (cmd) {
                "providers" -> { providers(); done(seq) }
                "wall" -> { wall(i.getStringExtra("file")!!); done(seq) }
                "clear" -> { clear(); done(seq) }
                "place" -> place(i.getStringExtra("spec")!!, seq)
            }
        }.onFailure { Log.e(TAG, "cmd $cmd failed", it); File(getExternalFilesDir(null), "error-$seq").writeText(it.stackTraceToString()); done(seq) }
    }

    private fun done(seq: String) = File(getExternalFilesDir(null), "done-$seq").writeText("ok")

    private fun providers() {
        val m = AppWidgetManager.getInstance(this)
        val d = resources.displayMetrics.density
        val lines = m.installedProviders.filter { it.provider.packageName.startsWith("com.cerniera.") || it.provider.packageName.startsWith("com.fergusonconsultancy.") }
            .map { "${it.provider.flattenToShortString()}\t${(it.minWidth / d).toInt()}x${(it.minHeight / d).toInt()}\t${it.loadLabel(packageManager)}" }
        File(getExternalFilesDir(null), "providers.txt").writeText(lines.joinToString("\n") + "\n")
    }

    private fun wall(name: String) {
        val f = File(filesDir, name).takeIf { it.exists() } ?: File(getExternalFilesDir(null), name)
        f.inputStream().use { WallpaperManager.getInstance(this).setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
    }

    private fun clear() {
        stage.removeAllViews()
        host.appWidgetIds.forEach { host.deleteAppWidgetId(it) }
    }

    private fun place(spec: String, seq: String) {
        clear()
        val m = AppWidgetManager.getInstance(this)
        val d = resources.displayMetrics.density
        val placed = ArrayList<Pair<String, View>>()
        for (entry in spec.split(";").filter { it.isNotBlank() }) {
            val p = entry.split("|")
            val label = p[0]; val cn = ComponentName.unflattenFromString(p[1])!!
            val x = p[2].toFloat(); val y = p[3].toFloat(); val w = p[4].toFloat(); val h = p[5].toFloat()
            val id = host.allocateAppWidgetId()
            val opts = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w.toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h.toInt()); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h.toInt())
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w, h)))
            }
            if (!m.bindAppWidgetIdIfAllowed(id, cn, opts)) { Log.e(TAG, "not allowed to bind $cn"); host.deleteAppWidgetId(id); continue }
            val info = m.getAppWidgetInfo(id)
            val v = host.createView(applicationContext, id, info)
            v.setPadding(0, 0, 0, 0)
            v.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
            stage.addView(v, FrameLayout.LayoutParams(Math.round(w * d), Math.round(h * d)).apply {
                leftMargin = Math.round(x * d); topMargin = Math.round(y * d)
            })
            placed += "$label\t$id\t${cn.flattenToShortString()}" to v
        }
        // Where each landed, once laid out, then done
        main.postDelayed({
            val out = placed.joinToString("\n") { (meta, v) ->
                val at = IntArray(2); v.getLocationOnScreen(at)
                "$meta\t${at[0]}\t${at[1]}\t${v.width}\t${v.height}"
            }
            File(getExternalFilesDir(null), "bounds-$seq.txt").writeText(out + "\n")
            done(seq)
        }, 600)
    }

    companion object {
        private const val TAG = "Banco"
        private const val HOST_ID = 7070
    }
}
