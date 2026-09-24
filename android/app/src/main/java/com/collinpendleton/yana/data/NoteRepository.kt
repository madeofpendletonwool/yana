package com.collinpendleton.yana.data

import com.collinpendleton.yana.data.replica.AppendOp
import com.collinpendleton.yana.data.replica.CreateOp
import com.collinpendleton.yana.data.replica.LocalHit
import com.collinpendleton.yana.data.replica.MoveOp
import com.collinpendleton.yana.data.replica.OpPayload
import com.collinpendleton.yana.data.replica.ReplicaStore
import com.collinpendleton.yana.data.search.parseQuery
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

/**
 * The one door the screens go through for notes: the server when it can
 * be reached, the replica when it cannot. Nothing below the UI talks to
 * Room or REST directly — the editor and the capture features join the
 * navigation shell here.
 */
interface NoteRepository {
    /** Pulls spaces, the note list, and the tree; replays pending ops. */
    suspend fun sync()

    /** The account's spaces, from the replica after a sync when offline. */
    suspend fun spaces(): List<Space>

    /** One space's folder tree, from the replica when offline. */
    suspend fun tree(space: String): List<TreeNode>

    /**
     * One note: fetched and its body cached when online, read from the
     * cache when the server is out of reach.
     */
    suspend fun note(id: String): Note

    /**
     * Full-text search with the operator grammar: the server's index
     * when online, the replica's identical one when offline. A query the
     * replica cannot answer (author:, is:task, has:) says so instead of
     * guessing.
     */
    suspend fun search(query: String, space: String?): List<SearchResult>

    /** Queues an offline action; replay happens on the next sync. */
    suspend fun enqueueCreate(space: String, path: String, content: String)
    suspend fun enqueueAppend(noteId: String, text: String)
    suspend fun enqueueMove(noteId: String, toPath: String)

    /** How many offline actions wait for the network. */
    val pendingCount: Flow<Int>
}

/** One search result, the same shape whether the server or the replica produced it. */
data class SearchResult(
    val id: String,
    val title: String,
    val path: String,
    val space: String,
    val kind: String,
    val updatedAt: String,
    val snippet: String,
    val fromServer: Boolean,
)

/** The repository over one server and its replica. */
class YanaNoteRepository(
    private val client: YanaClient,
    private val store: ReplicaStore,
) : NoteRepository {

    override val pendingCount: Flow<Int> get() = store.pendingCount

    override suspend fun sync() {
        bind()
        pull()
        val replayed = replayPending()
        if (replayed > 0) pull()
    }

    private suspend fun pull() {
        val api = client.api()
        store.applySync(api.spaces().spaces, api.notes().notes, api.tree().spaces)
    }

    override suspend fun spaces(): List<Space> {
        bind()
        store.spaces()?.let { return it }
        pull()
        return store.spaces() ?: emptyList()
    }

    override suspend fun tree(space: String): List<TreeNode> {
        bind()
        store.tree(space)?.let { return it }
        pull()
        return store.tree(space) ?: emptyList()
    }

    override suspend fun note(id: String): Note {
        bind()
        return try {
            val n = client.api().note(id)
            val content = n.markdown ?: n.source ?: ""
            store.storeBody(id, n.kind, content)
            n
        } catch (e: YanaClient.NotSignedIn) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            cachedNote(id) ?: throw e
        }
    }

    override suspend fun search(query: String, space: String?): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        bind()
        return try {
            client.api().search(query.trim().take(512), space).hits.map { it.toResult() }
        } catch (e: YanaClient.NotSignedIn) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The server is out of reach: the replica answers, unless the
            // query asks for data only the server keeps.
            store.search(parseQuery(query), space).map { it.toResult() }
        }
    }

    override suspend fun enqueueCreate(space: String, path: String, content: String) =
        store.enqueueCreate(CreateOp(space, path, content))

    override suspend fun enqueueAppend(noteId: String, text: String) =
        store.enqueueAppend(AppendOp(noteId, text))

    override suspend fun enqueueMove(noteId: String, toPath: String) =
        store.enqueueMove(MoveOp(noteId, toPath))

    /**
     * Replays the queue oldest first. A network failure stops the pass
     * with the op kept for the next one; a server refusal drops the op,
     * because repeating the same refused request cannot start working.
     * Appending to a markdown note has no transport until the editor's
     * CRDT path lands, so those ops wait here; everything else replays
     * today.
     */
    private suspend fun replayPending(): Int {
        var replayed = 0
        for (p in store.pendingOps()) {
            val settled = when (val op = p.op) {
                is OpPayload.Create -> try {
                    client.api().createNote(CreateNoteRequest(path = op.op.path, content = op.op.content))
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    !(e is HttpException && permanent(e.code()))
                }
                is OpPayload.Move -> try {
                    client.api().moveNote(op.op.noteId, MoveRequest(op.op.toPath)).let { r ->
                        r.isSuccessful || permanent(r.code())
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    !(e is HttpException && permanent(e.code()))
                }
                is OpPayload.Append -> try {
                    val cached = store.note(op.op.noteId)
                    if (cached?.kind != "html") {
                        false // markdown appends ride with the editor
                    } else {
                        val src = store.rawBody(op.op.noteId).orEmpty()
                        val r = client.api().putSource(op.op.noteId, SourceSave(src + op.op.text))
                        r.isSuccessful || permanent(r.code())
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    !(e is HttpException && permanent(e.code()))
                }
            }
            if (settled) {
                store.dropOp(p.seq)
                replayed++
            } else {
                break
            }
        }
        return replayed
    }

    /** A refused request the server will keep refusing. */
    private fun permanent(code: Int): Boolean = code >= 400 && code < 500 && code != 408 && code != 429

    /** Ties the replica to the signed-in account, wiping it on a change. */
    private suspend fun bind() {
        val s = client.session.value ?: return
        val server = normalizeServerUrl(s.server)?.toString() ?: s.server
        store.bind(server, s.userId)
    }

    /** One note assembled from the replica for offline reading. */
    private suspend fun cachedNote(id: String): Note? {
        val row = store.note(id) ?: return null
        val raw = store.rawBody(id)
        return Note(
            id = row.id,
            space = row.space,
            path = row.relPath,
            title = row.title,
            preview = row.preview,
            kind = row.kind,
            created = formatEpoch(row.created),
            updatedAt = formatEpoch(row.updatedAt),            tags = row.tags?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
            role = "",
            markdown = if (row.kind != "html") raw else null,
            source = if (row.kind == "html") raw else null,
        )
    }

    private fun SearchHit.toResult() = SearchResult(
        id = note.id,
        title = note.title,
        path = note.path,
        space = note.space,
        kind = note.kind,
        updatedAt = note.updatedAt,
        snippet = snippet.stripMarks(),
        fromServer = true,
    )

    private fun LocalHit.toResult() = SearchResult(
        id = id,
        title = title,
        path = path,
        space = space,
        kind = kind,
        updatedAt = formatEpoch(updatedAt),
        snippet = snippet.stripMarks(),
        fromServer = false,
    )

    private fun String.stripMarks(): String =
        replace("<mark>", "").replace("</mark>", "")

    private fun formatEpoch(nanos: Long): String =
        Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L).toString()
}
