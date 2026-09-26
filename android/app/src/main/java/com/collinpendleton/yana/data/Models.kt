package com.collinpendleton.yana.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The server's JSON: unknown fields are ignored so newer servers stay readable. */
val YanaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class AuthState(
    @SerialName("setup_required") val setupRequired: Boolean = false,
    val user: User? = null,
)

@Serializable
data class User(
    val id: String,
    val username: String,
    @SerialName("is_owner") val isOwner: Boolean = false,
)

@Serializable
data class Tokens(
    @SerialName("access_token") val access: String,
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_token") val refresh: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String = "",
)

@Serializable
data class Credentials(val username: String, val password: String, val label: String)

@Serializable
data class SignInResponse(val user: User, val tokens: Tokens)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class RefreshResponse(val tokens: Tokens)

@Serializable
data class ApiErrorBody(
    val error: String? = null,
    @SerialName("setup_required") val setupRequired: Boolean = false,
)

@Serializable
data class SpacesResponse(val spaces: List<Space> = emptyList())

/** One space: [name] is its directory ("" for notes loose in the root). */
@Serializable
data class Space(
    val name: String,
    val label: String = "",
    val notes: Int = 0,
) {
    val displayName: String get() = if (name.isEmpty()) "/" else label.ifEmpty { name }
}

@Serializable
data class TreeResponse(val spaces: List<SpaceTree> = emptyList())

@Serializable
data class SpaceTree(
    val name: String,
    val notes: Int = 0,
    val children: List<TreeNode> = emptyList(),
)

@Serializable
data class TreeNode(
    val type: String,
    val name: String,
    val path: String,
    val id: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val tags: List<String> = emptyList(),
    val public: Boolean = false,
    val conflict: Boolean = false,
    val children: List<TreeNode> = emptyList(),
) {
    val isDir: Boolean get() = type == "dir"
    val label: String get() = if (isDir) name else title?.ifEmpty { null } ?: name
}

/** One [[wikilink]] of a note, as the server resolved it at index time. */
@Serializable
data class ServerLink(
    @SerialName("raw_target") val rawTarget: String = "",
    @SerialName("to_id") val toId: String? = null,
    val resolved: Boolean = false,
)

/** A task tick: one checkbox of one note, set open or done. */
@Serializable
data class TaskTickRequest(val note: String, val line: Int, val done: Boolean)

@Serializable
data class Note(
    val id: String,
    val space: String = "",
    val path: String,
    val title: String = "",
    val preview: String = "",
    val kind: String = "md",
    val size: Long = 0,
    val created: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val tags: List<String> = emptyList(),
    val role: String = "",
    val markdown: String? = null,
    val source: String? = null,
    val public: Boolean = false,
    val trusted: Boolean = false,
    @SerialName("content_hash") val contentHash: String = "",
    /** The note's directory, for resolving relative images and create paths. */
    val base: String = "",
    val links: List<ServerLink> = emptyList(),
)

/** The content-origin URL a rendered HTML note loads, signed for a few minutes. */
@Serializable
data class NoteView(
    val url: String,
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class SaveSourceRequest(
    val source: String,
    @SerialName("base_hash") val baseHash: String,
)

/** A source save: last-write-wins, with the diverged disk version parked at [conflictCopy] when there was one. */
@Serializable
data class SaveSourceResponse(
    val ok: Boolean = false,
    val path: String? = null,
    val hash: String? = null,
    @SerialName("conflict_copy") val conflictCopy: String? = null,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionInfo> = emptyList())

/**
 * One note's metadata: the full row the search and list endpoints
 * return, tags included. This is what the offline replica caches for
 * every visible note.
 */
@Serializable
data class NoteMeta(
    val id: String,
    val space: String = "",
    val path: String,
    val title: String = "",
    val preview: String = "",
    val kind: String = "md",
    @SerialName("content_hash") val contentHash: String = "",
    val size: Long = 0,
    val mtime: String = "",
    val created: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val order: Int? = null,
    val trusted: Boolean = false,
    @SerialName("conflict_of") val conflictOf: String? = null,
    val tags: List<String> = emptyList(),
)

/** The flat note list the replica syncs from. */
@Serializable
data class NotesResponse(val notes: List<NoteMeta> = emptyList())

/** One full-text result, the same shape online and offline. */
@Serializable
data class SearchHit(
    val note: NoteMeta,
    val snippet: String = "",
    val rank: Double = 0.0,
)

@Serializable
data class SearchResponse(
    val mode: String = "fts",
    val hits: List<SearchHit> = emptyList(),
)

@Serializable
data class CreateNoteRequest(val path: String, val content: String)

@Serializable
data class CreateNoteResponse(val id: String = "", val path: String = "")

@Serializable
data class MoveRequest(val path: String)

@Serializable
data class SessionInfo(
    val id: String,
    val label: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_used_at") val lastUsedAt: String = "",
    val current: Boolean = false,
)
