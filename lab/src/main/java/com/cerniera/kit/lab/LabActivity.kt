package com.cerniera.kit.lab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Screen
import com.cerniera.kit.SensoView
import com.cerniera.kit.endValue
import com.cerniera.kit.px

/** Senso's debug view, whole, plus the calendar permission leaving soon needs. */
class LabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Crash.show(this, "Senso lab")) return
        build()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
    }

    private fun build() {
        Kit.follow(this)
        val s = Screen(this, "Senso", "Kit lab · Fluido phase 0")
        val senso = LabApp.senso
        senso.homes(homes())
        senso.recompute(null)
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            s.add(Card(this).apply {
                row(Row(this@LabActivity, "Calendar", "Leaving soon reads your next event", endValue("Allow", Kit.CHAMPAGNE)) {
                    requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1)
                })
            }, px(18))
        }
        s.add(SensoView(this, senso), px(8))
        setContentView(s.root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        LabApp.senso.readCalendar()
        build()
    }

    private fun homes(): Set<String> = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
}
