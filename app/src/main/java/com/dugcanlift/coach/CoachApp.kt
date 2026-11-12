package com.dugcanlift.coach

import android.app.Application
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.liftmap.MapNetworkGuard

/** Owns the app's single [ClientRepository], backed by this device's private files dir. */
open class CoachApp : Application() {
    val repo: ClientRepository by lazy { ClientRepository(filesDir) }

    override fun onCreate() {
        super.onCreate()
        installMapGuard()
    }

    /**
     * Before any map is made: MapLibre is told it is offline and its HTTP client refuses every
     * call, so only the kit's own street-map download can reach a server. Open only so the JVM
     * unit tests' application can skip it (MapLibre's native library does not load there).
     */
    protected open fun installMapGuard() {
        MapNetworkGuard.install(this)
    }
}
