package com.collinpendleton.yana.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collinpendleton.yana.BuildConfig
import com.collinpendleton.yana.YanaApp
import com.collinpendleton.yana.data.SessionInfo
import com.collinpendleton.yana.data.normalizeServerUrl
import com.collinpendleton.yana.data.display
import com.collinpendleton.yana.ui.Loader
import com.collinpendleton.yana.ui.Wordmark
import com.collinpendleton.yana.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Account, appearance, and about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: YanaApp, onBack: () -> Unit) {
    val client = app.client
    val session by client.session.collectAsStateWithLifecycle()
    val mode by app.prefs.themeMode.collectAsStateWithLifecycle()
    val sessions: Loader<List<SessionInfo>> = viewModel { Loader(fetch = { client.api().sessions().sessions }) }
    val device by sessions.loaded.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Account")
                session?.let { s ->
                    Field("Signed in as", s.username + if (s.isOwner) ", the server's owner" else "")
                    Field("Server", normalizeServerUrl(s.server)?.display() ?: s.server)
                }
                device.data?.firstOrNull { it.current }?.let {
                    Field("This device", it.label.ifEmpty { "unnamed" })
                }
                Text(
                    "Signing out ends this device's session on the server. Other devices stay signed in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirming = true }, enabled = !signingOut) { Text("Sign out") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Section("Appearance")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { app.prefs.setThemeMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(m.name) }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Section("About")
                About()
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out of this device?") },
            text = { Text("Notes stay on the server. Sign in again to see them here.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    signingOut = true
                    scope.launch { client.signOut() }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun About() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Wordmark(size = 30.sp)
            Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Plain files on your server, edited from any device. Version ${BuildConfig.VERSION_NAME}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 16.dp).width(48.dp), color = MaterialTheme.colorScheme.outline)
        Text(
            "1. Yet Another Notes App.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
