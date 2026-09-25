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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collinpendleton.yana.data.Note
import com.collinpendleton.yana.data.NoteRepository
import com.collinpendleton.yana.data.stripFrontmatter
import com.collinpendleton.yana.ui.Loader
import com.collinpendleton.yana.ui.Placeholder
import com.collinpendleton.yana.ui.formatTime
import com.collinpendleton.yana.ui.htmlnote.HtmlNotePane

/**
 * A note: its title, where it lives, its tags, and its body — from the
 * server, or from the replica when the server is out of reach. Markdown
 * reads as text; HTML renders in a sandboxed WebView on the content
 * origin, with its source editable beside it (offline, the source
 * reads as text until the server returns). The markdown editor arrives
 * with the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(repo: NoteRepository, id: String, title: String, onBack: () -> Unit) {
    val vm: Loader<Note> = viewModel(key = "note:$id") { Loader(fetch = { repo.note(id) }) }
    val state by vm.loaded.collectAsStateWithLifecycle()
    val note = state.data

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.title?.ifEmpty { null } ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NoteHeader(note, Modifier.widthIn(max = 720.dp).fillMaxWidth())
                    NoteBody(note, Modifier.widthIn(max = 720.dp).fillMaxWidth())
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
private fun NoteBody(note: Note, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            note.kind == "md" -> SelectionContainer {
                Text(
                    note.markdown?.let(::stripFrontmatter)?.ifBlank { null } ?: "This note is empty.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> Hint("This kind of note opens on the web for now.")
        }
        Hint("Read-only on this device until the editor arrives.")
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}
