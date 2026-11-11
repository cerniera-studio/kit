package com.cerniera.kit

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.LinearLayout
import java.io.File

/**
 * Crashes, kept so they can be read and sent back from the phone. Call [install] from the app's
 * Application.onCreate and [show] at the top of the main Activity's onCreate: a report from the
 * last run is shown on the next open with Share, Copy and Try again.
 */
object Crash {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context, appName: String) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { save(app, appName, "Crash on ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** Records a failure that was caught, without ending the app. */
    fun save(c: Context, appName: String, where: String, e: Throwable) {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        val report = "$appName $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "$where\n\n" + android.util.Log.getStackTraceString(e).lines().take(60).joinToString("\n")
        file(c).writeText(report)
    }

    /** Shows the last crash, if there is one. Returns true when it did. */
    fun show(a: Activity, appName: String): Boolean {
        val f = file(a)
        if (!f.exists()) return false
        val report = runCatching { f.readText() }.getOrDefault("")
        Kit.follow(a)
        val s = Screen(a, "Closed unexpectedly", appName)
        s.add(a.text("Send this report back so it can be fixed, then tap Try again.", 15f, Kit.MUTED), a.px(10))
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(a.primary("Share report") {
            runCatching {
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "$appName crash report").putExtra(Intent.EXTRA_TEXT, report), "Share report"))
            }
        })
        buttons.addView(a.primary("Copy", filled = false) {
            runCatching { a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("$appName crash", report)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.px(10) })
        buttons.addView(a.primary("Try again", filled = false) { f.delete(); a.recreate() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.px(10) })
        s.add(buttons, a.px(20))
        s.add(a.text(report, 12f, Kit.CHAMPAGNE).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }, a.px(24))
        a.setContentView(s.root)
        return true
    }
}
