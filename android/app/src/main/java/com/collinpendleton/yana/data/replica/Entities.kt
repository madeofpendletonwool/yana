package com.collinpendleton.yana.data.replica

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The replica's tables mirror the server's SQLite cache (the tables the
 * client needs): spaces, notes, tags, note_bodies, plus a materialised
 * folder tree and the pending_ops queue. Everything here arrived in a
 * server response for the signed-in account; a note from any other space
 * never enters the replica, so offline search cannot leak it.
 */

/** One space the account belongs to. */
@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val name: String,
    val label: String,
    val notes: Int,
)

/**
 * One note row. Timestamps are epoch nanoseconds, the unit the server
 * orders by, so offline date filters and newest-first ordering sort
 * exactly as the server's do.
 */
@Entity(
    tableName = "notes",
    indices = [Index(value = ["rel_path"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val space: String,
    @ColumnInfo(name = "rel_path") val relPath: String,
    val title: String,
    val preview: String,
    val kind: String,
    val created: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** A note's tags, mirroring the server's tags table. */
@Entity(
    tableName = "tags",
    primaryKeys = ["note_id", "tag"],
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["note_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("note_id")],
)
data class TagEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    val tag: String,
)

/**
 * The text of a note the user has opened, mirroring the server's
 * note_bodies: title and body feed the FTS index (body is what search
 * reads — markdown without frontmatter, or tag-stripped HTML), raw_body
 * is what offline reading shows. Notes never opened have a body row with
 * empty text so their titles stay searchable; the CRDT sync fills the
 * bodies in.
 */
@Entity(tableName = "note_bodies")
data class NoteBodyEntity(
    @PrimaryKey @ColumnInfo(name = "note_rowid") val noteRowid: Long,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_body") val rawBody: String,
)

/**
 * One flattened node of the folder tree, as the server sent it: the
 * nesting (parent), the server's sort order (position), and every flag
 * the tree response carries (public links, conflict copies) survive a
 * round trip offline.
 */
@Entity(tableName = "tree_nodes")
data class TreeNodeEntity(
    @PrimaryKey val path: String,
    val space: String,
    /** Parent node's path; "" for a space's top level. */
    val parent: String,
    val type: String,
    val name: String,
    val id: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val order: Int? = null,
    /** Tags joined with ","; the grammar does not allow commas in tags. */
    val tags: String = "",
    val public: Boolean = false,
    val conflict: Boolean = false,
    @ColumnInfo(name = "conflict_of") val conflictOf: String? = null,
    val position: Int = 0,
)

/**
 * One offline action waiting for the network: create a note, append to
 * one, or move one. Payload is the op's JSON; replay drains the queue in
 * order.
 */
@Entity(tableName = "pending_ops")
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val type: String,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Replica bookkeeping: whose notes these are (server and user id). */
@Entity(tableName = "replica_meta")
data class ReplicaMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
