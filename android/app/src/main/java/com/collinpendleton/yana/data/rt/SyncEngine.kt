package com.collinpendleton.yana.data.rt

import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.data.normalizeServerUrl
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** The connection's face in the UI: offline / syncing / live. */
enum class RtStatus { Offline, Syncing, Live }

/** What the relay tells the app beyond document bytes. */
sealed class RtEvent {
    data class Moved(val noteId: String, val path: String?) : RtEvent()

    data class Deleted(val noteId: String) : RtEvent()

    data class Error(val noteId: String?, val code: String, val reason: String) : RtEvent()

    /** A peer's awareness payload (cursor, presence), passed through unopened. */
    class Awareness(val noteId: String, val author: String?, val payload: ByteArray) : RtEvent()
}

/**
 * The realtime sync layer: one WebSocket per app, the note documents
 * subscribed over it, and the outbox that carries local edits to the
 * server no matter what happens to the process or the network.
 *
 * The shape follows the web client (web/src/sync.ts) and the protocol
 * in docs/realtime.md. Local edits queue in Room before they are sent,
 * leave the outbox only when a pong proves the server read the frame
 * that carried them, and are re-sent on reconnect when it never did —
 * CRDT idempotence makes the duplicate harmless. Reconnects back off
 * exponentially with jitter; on a healthy connection the send path
 * batches keystrokes on a 50ms timer, the same window the web client
 * uses.
 *
 * A note's document persists as one Room blob (`note_crdt.state`), the
 * compaction of everything applied or authored here; the outbox
 * (`crdt_outbox`) is the not-yet-confirmed tail. The two together mean
 * a day offline, a process death, or a server restart all converge
 * with nothing lost.
 *
 * Everything the engine owns runs on one thread (the scope it is
 * given is single-threaded); the few entry points called from elsewhere
 * touch shared state under a monitor that never suspends.
 */
class SyncEngine(
    private val client: YanaClient,
    private val store: RtStore,
    private val transport: RtTransport,
    private val docs: RtDocFactory,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val timings: Timings = Timings(),
) {
    /**
     * The clockwork, overridable so the JVM tests run in milliseconds
     * instead of the production windows.
     */
    data class Timings(
        val flushMs: Long = 50,
        val backoffMinMs: Long = 500,
        val backoffMaxMs: Long = 8_000,
        val backoffJitterMs: Long = 250,
        val keepaliveMs: Long = 25_000,
        val persistDebounceMs: Long = 500,
        val bodyDebounceMs: Long = 1_000,
        val dialTimeoutMs: Long = 20_000,
        val backgroundTimeoutMs: Long = 90_000,
    )

    private val _status = MutableStateFlow(RtStatus.Offline)
    val status: StateFlow<RtStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RtEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RtEvent> = _events.asSharedFlow()

    // Engine state, confined to the scope's single thread (or the monitor).
    private val sessions = LinkedHashMap<String, NoteSession>()
    private var sessionJob: Job? = null
    private var attempts = 0
    private var conn: RtTransport.Connection? = null
    private var connected = false
    private var lastSentAt = 0L
    private var maxSentSeq = -1L
    private val pingAcks = ArrayDeque<Long>()
    private val dirty = LinkedHashSet<String>()
    private var flushJob: Job? = null
    private var keepaliveJob: Job? = null

    /**
     * Opens (or joins) a note's live document. The handle's flows start
     * cold and fill in as the local state loads and the first
     * subscribe lands; `ready` says when the document's text — not a
     * cached REST copy — is what the screen should show.
     */
    fun open(noteId: String): NoteHandle {
        val s = sync {
            sessions[noteId]?.also { it.subscribers++ }
                ?: NoteSession(noteId).also { it.subscribers = 1; sessions[noteId] = it }
        }
        scope.launch {
            store.touchOpened(noteId)
            val loadedSession = loadSession(s)
            loadedSession?.let {
                maybeSubscribe(it)
                ensureSession()
            }
        }
        return NoteHandle(noteId, s.text, s.ready, s.everSynced)
    }

    /** Drops one reference to a note; the document closes when the last is gone. */
    fun close(noteId: String) {
        val s = sync { sessions[noteId] } ?: return
        s.subscribers--
        reap(noteId)
    }

    /** The editor's edit path: mutate the body, queue the update, batch the send. */
    fun edit(noteId: String, want: String) {
        val s = sync { sessions[noteId] } ?: return
        scope.launch {
            val update = s.withDoc { it.replaceText(want) } ?: return@launch
            store.addOutboxUpdate(noteId, update)
            s.afterChange()
            dirty.add(noteId)
            scheduleFlush()
        }
    }

    /** Sends an awareness payload (presence, cursors) for a subscribed note. */
    fun sendAwareness(noteId: String, payload: ByteArray) {
        val s = sync { sessions[noteId] } ?: return
        scope.launch {
            if (!connected || !s.subdThisConnection || !s.isLoaded()) return@launch
            send(ClientFrame(Rt.AW, n = noteId, p = payload))
        }
    }

    /**
     * The background half: flush every unconfirmed update, then pull
     * deltas for the notes the person opened lately, updating the
     * replica and its search index. Returns false when it could not
     * finish in time; the caller retries on its own schedule.
     */
    suspend fun runBackgroundSync(timeoutMs: Long = timings.backgroundTimeoutMs): Boolean {
        val notes = (store.outboxNotes() + store.recentlyOpened(20)).distinct()
        if (notes.isEmpty()) return true
        val held = sync {
            notes.map { id ->
                val s = sessions[id] ?: NoteSession(id).also { sessions[id] = it }
                s.backgroundUse = true
                s
            }
        }
        held.forEach { s ->
            scope.launch {
                loadSession(s)?.let { maybeSubscribe(it) }
            }
        }
        try {
            ensureSession()
            return withTimeout(timeoutMs) {
                while (!(held.all { it.subdThisConnection } && store.outboxCount() == 0 && dirty.isEmpty())) {
                    if (!wanted()) break
                    delay(250)
                }
                true
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            return false
        } finally {
            sync { held.forEach { it.backgroundUse = false } }
            held.forEach { reap(it.noteId) }
        }
    }

    /** Stops everything; documents persist on their way out. */
    fun shutdown() {
        val all = sync {
            val list = sessions.values.toList()
            sessions.clear()
            list
        }
        // The socket drops now, not on the engine's thread, so a
        // shutdown is not racing the process's exit. The next connect
        // resumes from state vectors, which is the design anyway.
        conn?.let { runCatching { it.cancel() } }
        conn = null
        connected = false
        keepaliveJob?.cancel()
        _status.value = RtStatus.Offline
        scope.launch { all.forEach { it.closeDoc() } }
    }

    // --- sessions ------------------------------------------------------------

    /**
     * One note: its document, its flows, and who wants it open. The
     * document loads from Room before anything else touches it, so the
     * first subscribe carries the state vector of everything this
     * device already holds — offline edits included.
     */
    private inner class NoteSession(val noteId: String) {
        @Volatile
        var subscribers = 0

        @Volatile
        var backgroundUse = false
        private var doc: RtDoc? = null
        private var loaded = false

        /** The document's text, for the screen. */
        val text = MutableStateFlow("")

        /** True once the text is this device's document: loaded state or a synced subscribe. */
        val ready = MutableStateFlow(false)

        /** True once any connection completed this note's handshake. */
        val everSynced = MutableStateFlow(false)

        @Volatile
        var subdThisConnection = false

        private var persistJob: Job? = null
        private var bodyJob: Job? = null

        fun current(): Boolean = subscribers > 0 || backgroundUse

        fun isLoaded(): Boolean = loaded

        /** Loads (creating) the document, then publishes what it holds. */
        suspend fun load() {
            val state = store.crdtState(noteId)
            val d = docs.open(noteId, state ?: ByteArray(0))
            if (!sync { if (sessions[noteId] === this) { doc = d; loaded = true; true } else { false } }) {
                d.close()
                return
            }
            if (state != null && state.size > 0) {
                text.value = d.text()
                ready.value = true
            }
        }

        /** Runs [block] with the document when there is one and the session lives. */
        fun <T> withDoc(block: (RtDoc) -> T): T? {
            val d = sync { if (current()) doc else null } ?: return null
            return block(d)
        }

        /** Publishes text and schedules persistence after any document change. */
        fun afterChange() {
            val d = sync { doc } ?: return
            text.value = d.text()
            ready.value = true
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(timings.persistDebounceMs)
                runCatching { store.storeCrdtState(noteId, d.state()) }
            }
            bodyJob?.cancel()
            bodyJob = scope.launch {
                delay(timings.bodyDebounceMs)
                refreshReplicaBody(noteId, d.text())
            }
        }

        fun markSynced() {
            subdThisConnection = true
            everSynced.value = true
            afterChange()
        }

        fun closeDoc() {
            val d = sync {
                val had = doc
                doc = null
                loaded = false
                had
            } ?: return
            persistJob?.cancel()
            persistJob = scope.launch {
                runCatching { store.storeCrdtState(noteId, d.state()) }
                d.close()
            }
            bodyJob?.cancel()
            bodyJob = scope.launch {
                delay(timings.bodyDebounceMs)
                refreshReplicaBody(noteId, d.text())
            }
        }

        /** Releases a document nothing could use, without writing state back. */
        fun discard() {
            val d = sync {
                val had = doc
                doc = null
                loaded = false
                had
            } ?: return
            persistJob?.cancel()
            bodyJob?.cancel()
            d.close()
        }
    }

    /** What [open] hands the screen. */
    class NoteHandle(
        val noteId: String,
        val text: StateFlow<String>,
        val ready: StateFlow<Boolean>,
        val everSynced: StateFlow<Boolean>,
    )

    /** Loads a session's document, dropping it when the state will not load. */
    private suspend fun loadSession(s: NoteSession): NoteSession? = try {
        s.load()
        s
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A state that will not load drops the session rather than
        // wedging the note; the next open starts over.
        sync { if (sessions[s.noteId] === s) sessions.remove(s.noteId) }
        s.discard()
        null
    }

    /** Drops sessions nobody wants, flushing first whatever was never confirmed. */
    private fun reap(noteId: String) {
        val s = sync { sessions[noteId] } ?: return
        if (s.current()) return
        scope.launch {
            flushOne(noteId)
            if (connected) send(ClientFrame(Rt.UNSUB, n = noteId))
            sync { if (!s.current() && sessions[noteId] === s) sessions.remove(noteId) }
            refreshStatus()
            s.closeDoc()
        }
    }

    // --- the connection ------------------------------------------------------

    private fun wanted(): Boolean = sync { sessions.values.any { it.current() } }

    private fun ensureSession() {
        sync {
            if (sessionJob?.isActive == true) return
            sessionJob = scope.launch { sessionLoop() }
        }
    }

    private suspend fun sessionLoop() {
        while (true) {
            if (!wanted()) break
            val session = client.session.value ?: break
            _status.value = RtStatus.Syncing
            resetConnection()

            val token = try {
                client.auth.validAccessToken()
            } catch (_: IOException) {
                null
            }
            val base = normalizeServerUrl(session.server)
            if (token == null || base == null) {
                if (!backoff()) break
                continue
            }

            val frames = Channel<ByteArray>(Channel.UNLIMITED)
            val open = Channel<Unit>(1)
            val closed = Channel<CloseReason>(1)
            val c = transport.connect(
                wsUrl(base, token),
                listener = object : RtTransport.Listener {
                    override fun onOpen() {
                        open.trySend(Unit)
                    }

                    override fun onFrame(bytes: ByteArray) {
                        frames.trySend(bytes)
                    }

                    override fun onClosed(code: Int, reason: String) {
                        settle(CloseReason(code, reason, null))
                    }

                    override fun onFailure(t: Throwable?, httpCode: Int?) {
                        settle(CloseReason(1006, t?.message ?: "connection failed", httpCode))
                    }

                    fun settle(r: CloseReason) {
                        frames.close()
                        open.close()
                        closed.trySend(r)
                    }
                },
            )
            conn = c
            val opened = withTimeoutOrNull(timings.dialTimeoutMs) {
                try {
                    open.receive()
                    true
                } catch (_: ClosedReceiveChannelException) {
                    false
                }
            }
            if (opened == true) {
                onConnected()
                for (bytes in frames) {
                    handleFrame(bytes)
                }
            } else {
                c.close(1000, "dial timeout")
            }
            val reason = closed.maybeReceive() ?: CloseReason(1006, "connection ended", null)
            conn = null
            onDisconnected(reason)
            if (!wanted()) break
            if (!backoff()) break
        }
        resetConnection()
        val rest = sync {
            val list = sessions.values.toList()
            sessions.clear()
            list
        }
        rest.forEach { it.closeDoc() }
        _status.value = RtStatus.Offline
    }

    private class CloseReason(val code: Int, val message: String?, val httpCode: Int?)

    /** One connection's fresh start: nothing sent, nothing confirmed. */
    private fun resetConnection() {
        conn?.let { runCatching { it.close(1000, "reset") } }
        conn = null
        connected = false
        lastSentAt = 0L
        maxSentSeq = -1L
        pingAcks.clear()
        dirty.clear()
        keepaliveJob?.cancel()
        sync { sessions.values.forEach { it.subdThisConnection = false } }
    }

    private suspend fun onConnected() {
        connected = true
        refreshStatus()
        for (s in sync { sessions.values.filter { it.current() } }) {
            maybeSubscribe(s)
        }
        keepaliveJob = scope.launch {
            while (connected) {
                delay(timings.keepaliveMs)
                if (connected && now() - lastSentAt >= timings.keepaliveMs) sendPing()
            }
        }
    }

    /** Subscribes a loaded session on the live connection, once per connection. */
    private suspend fun maybeSubscribe(s: NoteSession) {
        if (!connected || s.subdThisConnection || !s.current() || !s.isLoaded()) return
        val sv = s.withDoc { it.stateVector() }
        if (!send(ClientFrame(Rt.SUB, n = s.noteId, sv = sv?.takeIf { it.isNotEmpty() }, a = author()))) return
        if (store.outboxFor(s.noteId).isNotEmpty()) {
            dirty.add(s.noteId)
            scheduleFlush()
        }
    }

    private fun onDisconnected(reason: CloseReason) {
        connected = false
        keepaliveJob?.cancel()
        _status.value = RtStatus.Offline
        // A rejection before any handshake is often an expired token;
        // minting a fresh one arms the next attempt. A refresh the
        // server refuses clears the session and the shell signs out.
        if (reason.httpCode == 401 || (!everSubdThisConnection() && reason.code != 1000)) {
            try {
                client.auth.validAccessToken()
            } catch (_: IOException) {
                // Unreachable; the reconnect loop keeps trying.
            } catch (_: Exception) {
            }
        }
    }

    /** Handles one inbound frame. Malformed frames are dropped, not fatal: the server closes those itself. */
    private suspend fun handleFrame(bytes: ByteArray) {
        val frame = decodeServerFrame(bytes) ?: return
        when (frame.t) {
            Rt.SUBD -> {
                val n = frame.n ?: return
                val s = sync { sessions[n] } ?: return
                frame.u?.let { u -> if (u.isNotEmpty()) s.withDoc { it.apply(u) } }
                attempts = 0
                s.markSynced()
                refreshStatus()
                dirty.add(n)
                flushOne(n)
            }
            Rt.UPD -> {
                val n = frame.n ?: return
                val u = frame.u ?: return
                if (u.isEmpty()) return
                val s = sync { sessions[n] } ?: return
                if (s.withDoc { it.apply(u) } == true) s.afterChange()
            }
            Rt.AW -> {
                val n = frame.n ?: return
                val p = frame.p ?: return
                _events.tryEmit(RtEvent.Awareness(n, frame.a, p))
            }
            Rt.PONG -> {
                val watermark = if (pingAcks.isEmpty()) null else pingAcks.removeFirst()
                if (watermark != null && watermark >= 0) store.dropOutboxTo(watermark)
                refreshStatus()
            }
            Rt.MOVED -> _events.tryEmit(RtEvent.Moved(frame.n ?: "", frame.path))
            Rt.DELETED -> _events.tryEmit(RtEvent.Deleted(frame.n ?: ""))
            Rt.ERR -> _events.tryEmit(RtEvent.Error(frame.n, frame.c ?: "error", frame.r ?: ""))
            // chg and watchd belong to the watch flow, which the listing
            // screens do not use yet; the periodic replica sync covers them.
            Rt.CHANGED, Rt.WATCHED -> {}
        }
    }

    // --- sending -------------------------------------------------------------

    private fun send(f: ClientFrame): Boolean {
        val c = conn ?: return false
        val ok = c.send(encodeClientFrame(f))
        if (ok) lastSentAt = now()
        return ok
    }

    /**
     * Sends a ping and records the watermark it confirms: the server
     * reads frames in order, so its pong proves it applied every
     * update sent before the ping.
     */
    private fun sendPing() {
        val watermark = maxSentSeq
        if (send(ClientFrame(Rt.PING))) pingAcks.addLast(watermark)
    }

    /** Queues the 50ms batch window; one engine-wide timer serves every note. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(timings.flushMs)
            val notes = dirty.toList()
            for (n in notes) flushOne(n)
        }
    }

    /**
     * Sends one note's unconfirmed updates as a single merged payload,
     * then the ping that confirms them. The rows stay until the pong;
     * a connection that dies before then leaves them for the next one.
     */
    private suspend fun flushOne(noteId: String) {
        if (!connected) return
        val s = sync { sessions[noteId] } ?: return
        if (!s.subdThisConnection) return
        val rows = store.outboxFor(noteId)
        if (rows.isEmpty()) {
            dirty.remove(noteId)
            return
        }
        val merged = docs.mergeAll(rows.map { it.payload }) ?: return
        if (send(ClientFrame(Rt.UPD, n = noteId, u = merged, a = author()))) {
            maxSentSeq = maxOf(maxSentSeq, rows.maxOf { it.seq })
            dirty.remove(noteId)
            sendPing()
            refreshStatus()
        }
    }

    /** Live when everything this connection wants has settled. */
    private suspend fun refreshStatus() {
        if (!connected) return
        val quiet = sync { sessions.values.all { !it.current() || it.subdThisConnection } } &&
            dirty.isEmpty() &&
            store.outboxCount() == 0
        _status.value = if (quiet) RtStatus.Live else RtStatus.Syncing
    }

    private fun everSubdThisConnection(): Boolean = sync { sessions.values.any { it.subdThisConnection } }

    private fun author(): String? = client.session.value?.let { "user:" + it.username }

    /** Waits out the backoff window. */
    private suspend fun backoff(): Boolean {
        _status.value = RtStatus.Offline
        delay(backoffDelay(attempts, random, timings.backoffMinMs, timings.backoffMaxMs, timings.backoffJitterMs))
        attempts++
        return true
    }

    // --- replica refresh -------------------------------------------------------

    /** Keeps the offline replica and its search index in step with the document. */
    private suspend fun refreshReplicaBody(noteId: String, text: String) {
        val kind = if (store.noteKind(noteId) == "html") "html" else "md"
        store.storeBody(noteId, kind, text)
    }

    // --- small helpers --------------------------------------------------------

    private inline fun <T> sync(block: () -> T): T = synchronized(this, block)

    private suspend fun <T> Channel<T>.maybeReceive(): T? = try {
        receive()
    } catch (_: ClosedReceiveChannelException) {
        null
    }
}

/** The reconnect delay: 500ms doubling to 8s, plus jitter, matching the web client. */
fun backoffDelay(attempts: Int, random: Random, minMs: Long = 500, maxMs: Long = 8_000, jitterMs: Long = 250): Long =
    minOf(minMs shl attempts.coerceAtMost(10), maxMs) + (random.nextDouble() * jitterMs).toLong()
