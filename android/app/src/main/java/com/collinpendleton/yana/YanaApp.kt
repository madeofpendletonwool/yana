package com.collinpendleton.yana

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.collinpendleton.yana.data.EncryptedSessionStore
import com.collinpendleton.yana.data.SyncScheduler
import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.data.YanaNoteRepository
import com.collinpendleton.yana.data.replica.ReplicaStore
import com.collinpendleton.yana.data.rt.CrdtSyncScheduler
import com.collinpendleton.yana.data.rt.GoDocFactory
import com.collinpendleton.yana.data.rt.OkHttpRtTransport
import com.collinpendleton.yana.data.rt.SyncEngine
import com.collinpendleton.yana.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the app's single client, its offline replica, its realtime
 * sync engine, and its plain (non-secret) preferences. The replica
 * belongs to the signed-in account: a different account (or server)
 * arriving wipes it, and so does signing out.
 */
class YanaApp : Application() {
    lateinit var client: YanaClient
        private set
    lateinit var store: ReplicaStore
        private set
    lateinit var repo: YanaNoteRepository
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var prefs: Prefs
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override fun onCreate() {
        super.onCreate()
        client = YanaClient(EncryptedSessionStore(this))
        store = ReplicaStore.open(this)
        repo = YanaNoteRepository(client, store)
        syncEngine = SyncEngine(
            client = client,
            store = store,
            transport = OkHttpRtTransport(client.socketClient()),
            docs = GoDocFactory(),
            scope = syncScope,
        )
        prefs = Prefs(this)
        scope.launch {
            client.session.collect { s ->
                if (s == null) {
                    store.wipe()
                    syncEngine.shutdown()
                }
            }
        }
        // Edits queued by an earlier life (a crash, a force stop) head
        // out as soon as the network allows, without a note being opened.
        scope.launch {
            CrdtSyncScheduler.flushIfPending(this@YanaApp, store.outboxCount() > 0)
        }
        // Going to the background with unconfirmed edits hands them to
        // the expedited worker; the process may not come back for them.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    scope.launch {
                        CrdtSyncScheduler.flushIfPending(this@YanaApp, store.outboxCount() > 0)
                    }
                }
            },
        )
        SyncScheduler.schedule(this)
        CrdtSyncScheduler.schedule(this)
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
