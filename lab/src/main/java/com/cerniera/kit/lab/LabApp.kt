package com.cerniera.kit.lab

import android.app.Application
import com.cerniera.kit.Crash
import com.cerniera.kit.Senso

/** One Senso for the lab's process, listening from the start, so injected signals land at any time. */
class LabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this, "Senso lab")
        senso = Senso(this, "Senso lab", injectable = true).also { it.start() }
    }

    companion object {
        lateinit var senso: Senso
    }
}
