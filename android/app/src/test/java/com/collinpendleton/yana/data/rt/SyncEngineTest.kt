package com.collinpendleton.yana.data.rt

import com.collinpendleton.yana.data.MemorySessionStore
import com.collinpendleton.yana.data.Session
import com.collinpendleton.yana.data.YanaClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sync engine against a scripted relay: MockWebServer speaks the
 * real WebSocket upgrade, the frames are the real msgpack wire, and
 * only the CRDT engine and the store are fakes (the AAR's native
 * libraries load on a device alone). The scenarios mirror the phase's
 * acceptance list: converge after a dead connection, merge both ways
 * after offline edits, and drain the outbox without a note open.
 */
class SyncEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeRtStore
    private lateinit var docs: FakeDocFactory
    private lateinit var scope: CoroutineScope
    private lateinit var engine: SyncEngine
    private val relays = ConcurrentLinkedQueue<FakeRelay>()
    private val liveRelays = CopyOnWriteArrayList<FakeRelay>()
    private val connections = AtomicLong()

    private val note = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                connections.incrementAndGet()
                val relay = relays.poll() ?: FakeRelay()
                relay.server = serverTextRef
                liveRelays.add(relay)
                return MockResponse.Builder().webSocketUpgrade(relay).build()
            }
        }
        server.start()
        store = FakeRtStore()
        docs = FakeDocFactory()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        engine = SyncEngine(
            client = YanaClient(MemorySessionStore(session())),
            store = store,
            transport = OkHttpRtTransport(OkHttpClient()),
            docs = docs,
            scope = scope,
            random = Random(7),
            timings = SyncEngine.Timings(
                flushMs = 10,
                backoffMinMs = 5,
                backoffMaxMs = 40,
                backoffJitterMs = 2,
                keepaliveMs = 60_000,
                persistDebounceMs = 5,
                bodyDebounceMs = 10,
                dialTimeoutMs = 5_000,
                backgroundTimeoutMs = 5_000,
            ),
        )
    }

    @After
    fun stop() {
        engine.shutdown()
        server.close()
        scope.cancel()
    }

    /** What the server would send as the note's delta; the fake doc appends it. */
    @Volatile
    private var serverTextRef: String = ""

    private fun session() = Session(
        server = server.url("/").toString(),
        userId = "u1",
        username = "ada",
        isOwner = true,
        sessionId = "s1",
        accessToken = "access-1",
        accessExpiresAt = System.currentTimeMillis() + 3_600_000,
        refreshToken = "refresh-1",
    )

    private fun waitUntil(timeoutMs: Long = 5_000, cond: suspend () -> Boolean) {
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    while (!cond()) delay(10)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "wait timed out: status=${engine.status.value} conns=${connections.get()} relays=${liveRelays.map { it.frames.map { f -> f.t } }} outbox=${runBlocking { store.outboxCount() }}",
                e,
            )
        }
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    @Test
    fun subscribesWithStateVectorAndAppliesTheDelta() {
        serverTextRef = "hello from the server"
        val handle = engine.open(note)
        waitUntil { liveRelays.any { it.frames.any { f -> f.t == Rt.SUB } } }
        val sub = liveRelays.flatMap { it.frames }.first { it.t == Rt.SUB }
        assertEquals(note, sub.n)
        assertEquals("user:ada", sub.a)
        waitUntil { handle.ready.value && handle.text.value.contains("hello from the server") }
        waitUntil { engine.status.value == RtStatus.Live }
        engine.close(note)
    }

    @Test
    fun reconnectsAfterAKillAndLosesNothing() {
        // First relay takes the edit but never confirms it, then dies —
        // the server-killed-mid-typing case.
        val killer = FakeRelay(answerPings = false, closeAfterUpdate = true)
        relays.add(killer)
        val handle = engine.open(note)
        waitUntil { liveRelays.isNotEmpty() && killer.frames.any { it.t == Rt.SUB } }
        engine.edit(note, "typed while connected")
        waitUntil { killer.frames.any { it.t == Rt.UPD } }
        // The kill happens; the outbox row must survive it.
        waitUntil { connections.get() >= 2 }
        val second = liveRelays.drop(1).first()
        // On reconnect the unconfirmed update goes out again — the
        // duplicate is harmless, the loss would not be.
        waitUntil { second.frames.any { it.t == Rt.UPD && it.u?.decodeToString() == "typed while connected" } }
        waitUntil { store.outboxCount() == 0 }
        waitUntil { engine.status.value == RtStatus.Live }
        assertTrue(handle.text.value.contains("typed while connected"))
        engine.close(note)
    }

    @Test
    fun mergesBothDirectionsAfterOfflineEdits() {
        serverTextRef = "server side"
        engine.open(note)
        waitUntil { engine.status.value == RtStatus.Live }
        engine.shutdown()
        // The closed document's state write is queued behind the
        // shutdown; let every write from the first engine land before
        // the offline edits are seeded, so they cannot be overwritten.
        runBlocking { delay(500) }
        // Edits made while the socket is gone queue in the outbox.
        blocking { store.addOutboxUpdate(note, "offline edit".toByteArray()) }
        blocking { store.storeCrdtState(note, "offline edit".toByteArray()) }
        // A fresh engine (the app reopening) picks them up.
        val reopened = SyncEngine(
            client = YanaClient(MemorySessionStore(session())),
            store = store,
            transport = OkHttpRtTransport(OkHttpClient()),
            docs = docs,
            scope = scope,
            random = Random(7),
            timings = SyncEngine.Timings(
                flushMs = 10,
                backoffMinMs = 5,
                backoffMaxMs = 40,
                backoffJitterMs = 2,
                keepaliveMs = 60_000,
                persistDebounceMs = 5,
                bodyDebounceMs = 10,
                dialTimeoutMs = 5_000,
                backgroundTimeoutMs = 5_000,
            ),
        )
        try {
            val h2 = reopened.open(note)
            // The offline edit reaches the server...
            waitUntil {
                liveRelays.flatMap { it.frames }.any { it.t == Rt.UPD && it.u?.decodeToString() == "offline edit" }
            }
            // ...and the server's side arrives through the subscribe and merges.
            waitUntil { h2.text.value.contains("offline edit") && h2.text.value.contains("server side") }
            waitUntil { store.outboxCount() == 0 }
        } finally {
            reopened.shutdown()
        }
        engine.close(note)
    }

    @Test
    fun backgroundSyncDrainsTheOutboxWithoutAnOpenNote() {
        blocking { store.storeCrdtState(note, "queued work".toByteArray()) }
        blocking { store.addOutboxUpdate(note, "queued work".toByteArray()) }
        assertTrue(blocking { engine.runBackgroundSync() })
        waitUntil { liveRelays.any { r -> r.frames.any { it.t == Rt.UPD && it.u?.decodeToString() == "queued work" } } }
        waitUntil { store.outboxCount() == 0 }
    }

    @Test
    fun keepsStateAcrossAnEngineRestart() {
        val handle = engine.open(note)
        waitUntil { engine.status.value == RtStatus.Live }
        engine.edit(note, "persisted")
        waitUntil { handle.text.value.contains("persisted") }
        engine.close(note)
        waitUntil { store.states[note]?.decodeToString()?.contains("persisted") == true }
    }


    /** The relay: answers subs with the delta, pings with pongs, and can be told to die. */
    private class FakeRelay(
        var answerPings: Boolean = true,
        var closeAfterUpdate: Boolean = false,
    ) : WebSocketListener() {
        val frames = CopyOnWriteArrayList<ClientFrame>()
        var server: String = ""
        var ws: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            ws = webSocket
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val f = decodeClientFrame(bytes.toByteArray()) ?: return
            frames.add(f)
            when (f.t) {
                Rt.SUB -> webSocket.send(
                    encodeServerFrame(ServerFrame(t = Rt.SUBD, n = f.n, u = server.toByteArray())).toByteString(),
                )
                Rt.PING -> if (answerPings) webSocket.send(encodeServerFrame(ServerFrame(t = Rt.PONG)).toByteString())
                Rt.UPD -> if (closeAfterUpdate) {
                    webSocket.close(1001, "killed")
                    closeAfterUpdate = false
                }
            }
        }
    }
}

/** Documents where updates are appended text — enough for flow, not for CRDT math. */
private class FakeDoc(override val noteId: String, state: ByteArray) : RtDoc {
    var text = state.decodeToString()

    override fun text(): String = text

    override fun stateVector(): ByteArray = byteArrayOf(text.length.toByte())

    override fun state(): ByteArray = text.toByteArray()

    override fun apply(update: ByteArray): Boolean {
        if (update.isEmpty()) return false
        text += update.decodeToString()
        return true
    }

    override fun replaceText(want: String): ByteArray? {
        if (want.length < text.length || want == text) return null
        val update = want.substring(text.length).toByteArray()
        text = want
        return update
    }

    override fun close() {}
}

private class FakeDocFactory : RtDocFactory {
    override fun open(noteId: String, state: ByteArray): RtDoc = FakeDoc(noteId, state)

    override fun merge(a: ByteArray, b: ByteArray): ByteArray = a + b
}

/** The store in memory: state blobs, the outbox, and the cached bodies. */
private class FakeRtStore : RtStore {
    val states = ConcurrentHashMap<String, ByteArray>()
    val bodies = ConcurrentHashMap<String, String>()

    private class Row(val noteId: String, val seq: Long, val payload: ByteArray)

    private val rows = ConcurrentLinkedQueue<Row>()
    private val seq = AtomicLong()
    private val opened = ConcurrentHashMap<String, Long>()

    override suspend fun crdtState(noteId: String): ByteArray? = states[noteId]

    override suspend fun storeCrdtState(noteId: String, state: ByteArray) {
        states[noteId] = state
    }

    override suspend fun touchOpened(noteId: String) {
        opened[noteId] = System.currentTimeMillis()
    }

    override suspend fun recentlyOpened(limit: Int): List<String> =
        opened.entries.sortedByDescending { it.value }.take(limit).map { it.key }

    override suspend fun addOutboxUpdate(noteId: String, update: ByteArray) {
        rows.add(Row(noteId, seq.incrementAndGet(), update))
    }

    override suspend fun outboxFor(noteId: String): List<OutboxRow> =
        rows.filter { it.noteId == noteId }.map { OutboxRow(it.seq, it.payload) }

    override suspend fun outboxNotes(): List<String> = rows.map { it.noteId }.distinct()

    override suspend fun dropOutboxTo(watermark: Long) {
        rows.removeIf { it.seq <= watermark }
    }

    override suspend fun outboxCount(): Int = rows.size

    override suspend fun noteKind(noteId: String): String? = "md"

    override suspend fun storeBody(noteId: String, kind: String, text: String) {
        bodies[noteId] = text
    }
}
