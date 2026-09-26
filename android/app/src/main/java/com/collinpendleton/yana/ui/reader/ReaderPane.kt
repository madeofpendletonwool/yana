package com.collinpendleton.yana.ui.reader

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.collinpendleton.yana.data.GoRender
import com.collinpendleton.yana.data.Note
import com.collinpendleton.yana.data.NoteRepository
import com.collinpendleton.yana.data.ResolvedLink
import com.collinpendleton.yana.data.TickOutcome
import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.data.YanaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer

/**
 * A markdown note, rendered to read: the shared Go engine renders on
 * the device, the reader page (an app asset built from the web's own
 * rich runtime) draws it in the Identity palette, and every tap a
 * rendered note can produce — a wikilink, a dashed link, a tag, a task
 * box — comes back as a yana:// navigation the pane answers. The body
 * re-renders through the same path when the live document changes, so
 * the read view is as current as the editor, offline included.
 */
@Composable
fun ReaderPane(
    repo: NoteRepository,
    client: YanaClient,
    note: Note,
    body: String?,
    dark: Boolean,
    onOpenNote: (String) -> Unit,
    onTag: (String) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The template ships in the APK's assets; one read serves the pane.
    val template = remember {
        context.assets.open("reader/reader.html").bufferedReader().use { it.readText() }
    }
    val fetcher = remember(client) {
        AssetFetcher(client.socketClient(), context.cacheDir, token = { client.auth.validAccessToken() })
    }

    // A tick queued offline flips the cached body; that flipped body is
    // what this pane renders until the live document says otherwise.
    var flippedBody by remember(note.id) { mutableStateOf<String?>(null) }
    val text = (flippedBody ?: body).orEmpty()

    // Render and resolve off the main thread, debounced the way the
    // web's reader debounces its live renders.
    var html by remember(note.id) { mutableStateOf("") }
    var links by remember(note.id) { mutableStateOf<List<ResolvedLink>>(emptyList()) }
    LaunchedEffect(note.id, text) {
        delay(220)
        html = withContext(Dispatchers.Default) { runCatching { GoRender.markdown(text) }.getOrDefault("") }
        links = runCatching { repo.resolveLinks(note, text) }.getOrDefault(emptyList())
    }

    // The page document embeds the first (render, resolution) pair; a
    // theme change or a reopen rebuilds it, everything else goes in
    // through the page's own setBody once it has loaded.
    val readOnly = note.role == "viewer"
    var first by remember(note.id) { mutableStateOf<Pair<String, List<ResolvedLink>>?>(null) }
    if (first == null && (html.isNotEmpty() || text.isEmpty())) first = html to links
    val page = remember(note.id, template, dark, readOnly, first) {
        val f = first ?: ("" to emptyList())
        ReaderPage.build(template, dark, note.base, note.space, readOnly, f.second, f.first)
    }

    var pageLoaded by remember(note.id) { mutableStateOf(false) }
    var loadedPage by remember(note.id) { mutableStateOf<String?>(null) }
    var pushedSeq by remember(note.id) { mutableStateOf(0) }
    var repush by remember(note.id) { mutableStateOf(0) }

    val onTap: (ReaderTap) -> Unit = { tap ->
        when (tap) {
            is ReaderTap.OpenNote -> onOpenNote(tap.id)
            is ReaderTap.CreateNote -> scope.launch {
                val id = repo.createNoteAt(tap.path)
                if (id != null) {
                    onOpenNote(id)
                } else {
                    onToast("Offline. The note is created on the next sync.")
                }
            }
            is ReaderTap.Tag -> onTag(tap.name)
            is ReaderTap.Task -> scope.launch {
                when (val outcome = repo.tickTask(note.id, tap.line, tap.done)) {
                    is TickOutcome.Done -> Unit // the live document brings the tick back
                    is TickOutcome.Queued -> flippedBody = outcome.body
                    is TickOutcome.Refused -> {
                        onToast(outcome.message)
                        repush++ // the box was tapped on; put it back as the text reads
                    }
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                applyReaderSettings(this)
                webViewClient = ReaderWebViewClient(
                    assets = assetLoader(ctx),
                    fetcher = fetcher,
                    serverUrl = { client.session.value?.server },
                    openExternal = { uri -> openInBrowser(context, uri) },
                    onTap = onTap,
                    onPageLoaded = { pageLoaded = true },
                )
            }
        },
        update = { wv ->
            if (loadedPage != page) {
                loadedPage = page
                pageLoaded = false
                pushedSeq = 0
                wv.loadDataWithBaseURL(readerBaseUrl(), page, "text/html", "utf-8", null)
            } else if (pageLoaded && pushedSeq <= repush) {
                val due = repush > 0 || html != first!!.first
                if (due) {
                    val arg = YanaJson.encodeToString(String.serializer(), html)
                    wv.evaluateJavascript("window.yanaReader && yanaReader.setBody($arg)", null)
                    pushedSeq = repush + 1
                }
            }
        },
        onRelease = { it.destroy() },
    )
}
