package com.collinpendleton.yana.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collinpendleton.yana.data.Note
import com.collinpendleton.yana.data.NoteRepository
import com.collinpendleton.yana.data.rt.SyncEngine
import com.collinpendleton.yana.data.stripFrontmatter
import com.collinpendleton.yana.ui.ConnectionDot
import com.collinpendleton.yana.ui.Loader
import com.collinpendleton.yana.ui.Placeholder
import com.collinpendleton.yana.ui.formatTime
import com.collinpendleton.yana.ui.editor.MarkdownEditor
import com.collinpendleton.yana.ui.htmlnote.HtmlNotePane

/**
 * A note: its title, where it lives, its tags, and its body — from the
 * server, or from the replica when the server is out of reach. A
 * markdown note also joins the realtime document: its body is the
 * CRDT's text once the local state loads or the first handshake
 * lands, the connection dot beside the title says whether edits are
 * waiting, settling, or live, and the edit button opens the editor
 * bound to that document. HTML renders in a sandboxed WebView on the
 * content origin, with its source editable beside it (offline, the
 * source reads as text until the server returns).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(repo: NoteRepository, sync: SyncEngine, id: String, title: String, onBack: () -> Unit) {
    val vm: Loader<Note> = viewModel(key = "note:$id") { Loader(fetch = { repo.note(id) }) }
    val state by vm.loaded.collectAsStateWithLifecycle()
    val note = state.data

    // Only markdown notes have a CRDT document; HTML notes edit by
    // source and never join the relay.
    val isHtml = note?.kind == "html"
    val live = remember(id, isHtml) { if (isHtml) null else sync.open(id) }
    DisposableEffect(id, isHtml) {
        onDispose { live?.let { sync.close(id) } }
    }
    val liveText = live?.text?.collectAsStateWithLifecycle()?.value
    val liveReady = live?.ready?.collectAsStateWithLifecycle()?.value == true
    val status by sync.status.collectAsStateWithLifecycle()
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    val canEdit = !isHtml && note?.role != "viewer"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.title?.ifEmpty { null } ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (canEdit && editing) {
                        IconButton(onClick = { editing = false }) {
                            Icon(Icons.Default.Check, contentDescription = "Done editing")
                        }
                    } else if (canEdit) {
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                    if (!isHtml) ConnectionDot(status, Modifier.padding(end = 20.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.reload(pull = true) },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            if (note == null) {
                Placeholder(loading = state.loading, error = state.error, empty = null, onRetry = { vm.reload() })
            } else if (note.kind == "html") {
                // No outer scroll: the WebView and the source editor scroll themselves.
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    NoteHeader(note, Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                    HtmlNotePane(repo, note, Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f))
                }
            } else if (editing && live != null && liveReady) {
                MarkdownEditor(
                    sync = sync,
                    handle = live,
                    noteId = id,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NoteHeader(note, Modifier.widthIn(max = 720.dp).fillMaxWidth())
                    NoteBody(
                        note,
                        liveBody = if (liveReady) liveText else null,
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Where the note lives, when it changed, its tags: everything above the body. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteHeader(note: Note, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(note.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val meta = listOfNotNull(
            formatTime(note.updatedAt).ifEmpty { null }?.let { "Edited $it" },
            note.role.takeIf { it == "viewer" }?.let { "view only" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (note.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                note.tags.forEach { tag ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "#$tag",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun NoteBody(note: Note, liveBody: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            note.kind == "md" -> SelectionContainer {
                Text(
                    (
                        liveBody?.ifBlank { null }
                            ?: note.markdown?.let(::stripFrontmatter)?.ifBlank { null }
                        ) ?: "This note is empty.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> Hint("This kind of note opens on the web for now.")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}
