package com.collinpendleton.yana.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collinpendleton.yana.data.Space
import com.collinpendleton.yana.data.YanaClient
import com.collinpendleton.yana.ui.Loader
import com.collinpendleton.yana.ui.Placeholder
import com.collinpendleton.yana.ui.Wordmark

/** Home: the spaces this account can see. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(client: YanaClient, onSpace: (Space) -> Unit, onSettings: () -> Unit) {
    val vm: Loader<List<Space>> = viewModel { Loader { client.api().spaces().spaces } }
    val state by vm.loaded.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Wordmark() },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
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
            val spaces = state.data
            if (spaces.isNullOrEmpty()) {
                Placeholder(
                    loading = state.loading,
                    error = state.error,
                    empty = "No spaces yet. Make one in settings on the web and it shows up here.",
                    onRetry = { vm.reload() },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "Spaces",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(spaces, key = { it.name }) { space ->
                        SpaceRow(space) { onSpace(space) }
                        HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    state.error?.let { item { ErrorLine(it) } }
                }
            }
        }
    }
}

@Composable
private fun SpaceRow(space: Space, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(space.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                if (space.name.isEmpty()) notes(space.notes) + " in the root" else notes(space.notes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ErrorLine(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(20.dp),
    )
}

internal fun notes(n: Int) = if (n == 1) "1 note" else "$n notes"
