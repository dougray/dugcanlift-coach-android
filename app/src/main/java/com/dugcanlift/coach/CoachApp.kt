package com.dugcanlift.coach

import android.app.Application
import com.dugcanlift.coach.data.ClientRepository

/** Owns the app's single [ClientRepository], backed by this device's private files dir. */
class CoachApp : Application() {
    val repo: ClientRepository by lazy { ClientRepository(filesDir) }
}
