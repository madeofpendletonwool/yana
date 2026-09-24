package com.collinpendleton.yana

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.collinpendleton.yana.data.EncryptedSessionStore
import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the app's single client and its plain (non-secret) preferences. */
class YanaApp : Application() {
    lateinit var client: YanaClient
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        client = YanaClient(EncryptedSessionStore(this))
        prefs = Prefs(this)
    }
}

/** Device preferences that are not secrets. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("yana_prefs", Context.MODE_PRIVATE)
    private val theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.System),
    )
    val themeMode: StateFlow<ThemeMode> = theme.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        sp.edit { putString("theme", mode.name) }
        theme.value = mode
    }
}

val Context.yana: YanaApp get() = applicationContext as YanaApp
