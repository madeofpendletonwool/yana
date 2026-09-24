package com.collinpendleton.yana.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collinpendleton.yana.data.TreeNode
import com.collinpendleton.yana.data.TreeRow
import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.data.noteCount
import com.collinpendleton.yana.data.visibleRows
import com.collinpendleton.yana.ui.Loader
import com.collinpendleton.yana.ui.Placeholder

/** One space: its folder tree, folders opening in place, notes listed under them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(
    client: YanaClient,
    space: String,
    label: String,
    onBack: () -> Unit,
    onNote: (id: String, title: String) -> Unit,
) {
    val vm: Loader<List<TreeNode>> = viewModel(key = "space:$space") {
        Loader {
            // The root ("") has no directory to filter on: take it from the full tree.
            val tree = client.api().tree(space.ifEmpty { null }).spaces
            tree.firstOrNull { it.name == space }?.children ?: emptyList()
        }
    }
    val state by vm.loaded.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            val nodes = state.data
            if (nodes.isNullOrEmpty()) {
                Placeholder(
                    loading = state.loading,
                    error = state.error,
                    empty = "This space holds no notes yet.",
                    onRetry = { vm.reload() },
                )
            } else {
                val rows = visibleRows(nodes, expanded)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.node.path }) { row ->
                        TreeRowItem(row) {
                            val n = row.node
                            if (n.isDir) expanded = if (row.expanded) expanded - n.path else expanded + n.path
                            else n.id?.let { onNote(it, n.label) }
                        }
                    }
                    state.error?.let { item { ErrorLine(it) } }
                }
            }
        }
    }
}

@Composable
private fun TreeRowItem(row: TreeRow, onClick: () -> Unit) {
    val n = row.node
    val turn by animateFloatAsState(if (row.expanded) 90f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp + 20.dp * row.depth, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (n.isDir) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (row.expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(turn),
                )
            } else {
                Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (n.isDir) n.name + "/" else n.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (n.isDir) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (n.isDir) {
            Text(
                noteCount(n).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
