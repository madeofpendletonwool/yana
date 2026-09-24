package com.collinpendleton.yana

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.collinpendleton.yana.data.EncryptedSessionStore
import com.collinpendleton.yana.data.SyncScheduler
import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.data.YanaNoteRepository
import com.collinpendleton.yana.data.replica.ReplicaStore
import com.collinpendleton.yana.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the app's single client, its offline replica, and its plain
 * (non-secret) preferences. The replica belongs to the signed-in
 * account: a different account (or server) arriving wipes it, and so
 * does signing out.
 */
class YanaApp : Application() {
    lateinit var client: YanaClient
        private set
    lateinit var store: ReplicaStore
        private set
    lateinit var repo: YanaNoteRepository
        private set
    lateinit var prefs: Prefs
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        client = YanaClient(EncryptedSessionStore(this))
        store = ReplicaStore.open(this)
        repo = YanaNoteRepository(client, store)
        prefs = Prefs(this)
        scope.launch {
            client.session.collect { s ->
                if (s == null) store.wipe()
            }
        }
        SyncScheduler.schedule(this)
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
