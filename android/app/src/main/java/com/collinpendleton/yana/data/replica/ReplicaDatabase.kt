package com.collinpendleton.yana.data.replica

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteQuery

/** One row of a local search, the same fields the server's hits carry. */
data class LocalHitRow(
    val id: String,
    val space: String,
    @ColumnInfo(name = "rel_path") val relPath: String,
    val title: String,
    val preview: String,
    val kind: String,
    val created: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val snippet: String,
    val rank: Double,
)

/** One cached note with its tags, for building trees and note views. */
data class NoteWithTags(
    val id: String,
    val space: String,
    @ColumnInfo(name = "rel_path") val relPath: String,
    val title: String,
    val preview: String,
    val kind: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val created: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val tags: String?,
)

@Dao
interface ReplicaDao {
    @Query("SELECT * FROM spaces ORDER BY name")
    suspend fun spaces(): List<SpaceEntity>

    @Query("SELECT * FROM notes WHERE space = :space ORDER BY rel_path")
    suspend fun notesIn(space: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun noteById(id: String): NoteEntity?

    @Query(
        """
        SELECT n.*, (SELECT group_concat(t.tag, ',') FROM tags t WHERE t.note_id = n.id ORDER BY t.tag) AS tags
        FROM notes n WHERE n.id = :id
        """,
    )
    suspend fun noteWithTags(id: String): NoteWithTags?

    @Query("SELECT * FROM note_bodies WHERE note_rowid = (SELECT rowid FROM notes WHERE id = :id)")
    suspend fun bodyOf(id: String): NoteBodyEntity?

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun noteCount(): Int

    @Query("SELECT * FROM tree_nodes WHERE space = :space ORDER BY position")
    suspend fun treeOf(space: String): List<TreeNodeEntity>

    @Query("SELECT COUNT(*) FROM tree_nodes")
    suspend fun treeCount(): Int

    @Query("SELECT value FROM replica_meta WHERE key = :key")
    suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(entry: ReplicaMetaEntity)

    @Query("SELECT * FROM pending_ops ORDER BY seq")
    suspend fun pendingOps(): List<PendingOpEntity>

    @Insert
    suspend fun addPendingOp(op: PendingOpEntity): Long

    @Query("DELETE FROM pending_ops WHERE seq = :seq")
    suspend fun dropPendingOp(seq: Long)

    @Query("DELETE FROM pending_ops")
    suspend fun clearPendingOps()

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun pendingCount(): kotlinx.coroutines.flow.Flow<Int>

    // --- CRDT state and outbox ---------------------------------------------

    @Query("SELECT * FROM note_crdt WHERE note_id = :id")
    suspend fun crdtState(id: String): NoteCrdtEntity?

    @Upsert
    suspend fun upsertCrdtState(e: NoteCrdtEntity)

    @Query("UPDATE note_crdt SET opened_at = :at WHERE note_id = :id")
    suspend fun touchCrdtOpened(id: String, at: Long)

    /** The notes the person opened most recently, freshest first. */
    @Query("SELECT note_id FROM note_crdt ORDER BY opened_at DESC LIMIT :limit")
    suspend fun recentlyOpenedCrdt(limit: Int): List<String>

    @Insert
    suspend fun addOutboxRow(e: CrdtOutboxEntity): Long

    @Query("SELECT * FROM crdt_outbox WHERE note_id = :noteId ORDER BY seq")
    suspend fun outboxFor(noteId: String): List<CrdtOutboxEntity>

    /** Rows the server has confirmed; seq is the watermark a pong carries. */
    @Query("DELETE FROM crdt_outbox WHERE seq <= :seq")
    suspend fun dropOutboxTo(seq: Long)

    @Query("SELECT COUNT(*) FROM crdt_outbox")
    suspend fun outboxCount(): Int

    @Query("SELECT COUNT(*) FROM crdt_outbox")
    fun outboxCountFlow(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT DISTINCT note_id FROM crdt_outbox")
    suspend fun outboxNotes(): List<String>

    /**
     * One whole sync: notes and tags replaced, spaces replaced, bodies
     * kept for surviving notes and seeded (title only) for new ones, the
     * tree replaced. The triggers declared beside the database keep the
     * FTS index in step with note_bodies.
     */
    @Transaction
    suspend fun applySync(
        spaces: List<SpaceEntity>,
        notes: List<Pair<NoteEntity, List<String>>>,
        tree: List<TreeNodeEntity>,
    ) {
        for ((n, tags) in notes) {
            upsertNote(n)
            clearTags(n.id)
            for (t in tags) addTag(n.id, t)
        }
        for (s in spaces) upsertSpace(s)
        dropMissingSpaces(spaces.map { it.name })
        notes.map { it.first.id }.chunked(500).forEach { dropMissingNotes(it) }
        seedBodies()
        syncBodyTitles()
        replaceTree(tree)
    }

    /**
     * Insert-or-update that keeps a surviving note's rowid, so the body
     * and FTS rows keyed by it stay attached across syncs.
     */
    @Upsert
    suspend fun upsertNote(n: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpace(s: SpaceEntity)

    @Query("DELETE FROM tags WHERE note_id = :noteId")
    suspend fun clearTags(noteId: String)

    @Query("INSERT OR IGNORE INTO tags (note_id, tag) VALUES (:noteId, :tag)")
    suspend fun addTag(noteId: String, tag: String)

    @Query("DELETE FROM spaces WHERE name NOT IN (:names)")
    suspend fun dropMissingSpaces(names: List<String>)

    @Query("DELETE FROM notes WHERE id NOT IN (:ids)")
    suspend fun dropMissingNotes(ids: List<String>)

    /** A body row for every note, so every title is in the FTS index. */
    @Query(
        """
        INSERT INTO note_bodies (note_rowid, title, body, raw_body)
        SELECT n.rowid, n.title, '', '' FROM notes n
        LEFT JOIN note_bodies b ON b.note_rowid = n.rowid
        WHERE b.note_rowid IS NULL
        """,
    )
    suspend fun seedBodies()

    /** Title changes ride into the index through the update trigger. */
    @Query(
        """
        UPDATE note_bodies SET title = (SELECT n.title FROM notes n WHERE n.rowid = note_bodies.note_rowid)
        WHERE title <> (SELECT n.title FROM notes n WHERE n.rowid = note_bodies.note_rowid)
        """,
    )
    suspend fun syncBodyTitles()

    @Query("DELETE FROM tree_nodes")
    suspend fun clearTree()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTree(nodes: List<TreeNodeEntity>)

    @Transaction
    suspend fun replaceTree(nodes: List<TreeNodeEntity>) {
        clearTree()
        insertTree(nodes)
    }

    @Query(
        """
        INSERT INTO note_bodies (note_rowid, title, body, raw_body)
        VALUES (
            (SELECT rowid FROM notes WHERE id = :noteId),
            (SELECT title FROM notes WHERE id = :noteId),
            :body, :raw)
        ON CONFLICT(note_rowid) DO UPDATE SET
            title = excluded.title, body = excluded.body, raw_body = excluded.raw_body
        """,
    )
    suspend fun storeBody(noteId: String, body: String, raw: String)

    /** A whole-replica reset: what a change of account does. */
    @Transaction
    suspend fun wipe() {
        clearTagsForAll()
        clearNotes()
        clearSpaces()
        clearTree()
        clearPendingOps()
        clearBodies()
        clearMeta()
        clearCrdtState()
        clearOutbox()
    }

    @Query("DELETE FROM tags")
    suspend fun clearTagsForAll()

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Query("DELETE FROM spaces")
    suspend fun clearSpaces()

    @Query("DELETE FROM note_bodies")
    suspend fun clearBodies()

    @Query("DELETE FROM replica_meta")
    suspend fun clearMeta()

    @Query("DELETE FROM note_crdt")
    suspend fun clearCrdtState()

    @Query("DELETE FROM crdt_outbox")
    suspend fun clearOutbox()

    @RawQuery(observedEntities = [NoteEntity::class, TagEntity::class, NoteBodyEntity::class])
    suspend fun rawSearch(query: SupportSQLiteQuery): List<LocalHitRow>
}

/**
 * The offline replica: the same cache tables the server keeps, in Room,
 * with the same FTS5 trigram index over note titles and bodies. The FTS
 * table and its triggers are raw SQL declared in onCreate because Room's
 * annotations cover FTS4 only; the DDL is the server's
 * (internal/index/migrations/001_initial.sql and 006) word for word, so
 * tokenization and ranking behave identically.
 */
@Database(
    entities = [
        SpaceEntity::class,
        NoteEntity::class,
        TagEntity::class,
        NoteBodyEntity::class,
        TreeNodeEntity::class,
        PendingOpEntity::class,
        ReplicaMetaEntity::class,
        NoteCrdtEntity::class,
        CrdtOutboxEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ReplicaDatabase : RoomDatabase() {
    abstract fun dao(): ReplicaDao

    companion object {
        /**
         * Version 2 adds the CRDT tables: one note-state row per note
         * the editor has opened, and the outbox of updates the server
         * has not confirmed. The DDL matches what Room generates from
         * the entities above.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                fun sql(stmt: String) = db.prepare(stmt).use { it.step() }
                sql("CREATE TABLE IF NOT EXISTS `note_crdt` (`note_id` TEXT NOT NULL PRIMARY KEY, `state` BLOB NOT NULL, `opened_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)")
                sql("CREATE TABLE IF NOT EXISTS `crdt_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT, `note_id` TEXT NOT NULL, `payload` BLOB NOT NULL, `created_at` INTEGER NOT NULL)")
                sql("CREATE INDEX IF NOT EXISTS `index_crdt_outbox_note_id` ON `crdt_outbox` (`note_id`)")
            }
        }

        /** The FTS5 index and the triggers that keep it in step. */
        val FTS_DDL = listOf(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
                title, body,
                content = 'note_bodies',
                content_rowid = 'note_rowid',
                tokenize = 'trigram'
            )
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS note_bodies_ai AFTER INSERT ON note_bodies BEGIN
                INSERT INTO notes_fts(rowid, title, body) VALUES (new.note_rowid, new.title, new.body);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS note_bodies_ad AFTER DELETE ON note_bodies BEGIN
                INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES ('delete', old.note_rowid, old.title, old.body);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS note_bodies_au AFTER UPDATE ON note_bodies BEGIN
                INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES ('delete', old.note_rowid, old.title, old.body);
                INSERT INTO notes_fts(rowid, title, body) VALUES (new.note_rowid, new.title, new.body);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS notes_ad AFTER DELETE ON notes BEGIN
                DELETE FROM note_bodies WHERE note_rowid = old.rowid;
            END
            """.trimIndent(),
        )
    }
}
