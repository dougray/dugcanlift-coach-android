package com.dugcanlift.coach.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Light, dark, or whatever the phone is set to. System is the default, decided
 * 2026-09-16: the app follows the phone unless told otherwise.
 *
 * A StateFlow rather than a plain preference so the theme recomposes the moment
 * the choice changes, without restarting the activity.
 */
enum class AppAppearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

class AppearanceStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("coach_settings", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(read())
    val appearance: StateFlow<AppAppearance> = _appearance.asStateFlow()

    fun set(value: AppAppearance) {
        prefs.edit().putString(KEY, value.name).apply()
        _appearance.value = value
    }

    private fun read(): AppAppearance =
        AppAppearance.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: AppAppearance.SYSTEM

    companion object {
        private const val KEY = "appearance"

        @Volatile
        private var instance: AppearanceStore? = null

        fun get(context: Context): AppearanceStore =
            instance ?: synchronized(this) {
                instance ?: AppearanceStore(context).also { instance = it }
            }
    }
}
