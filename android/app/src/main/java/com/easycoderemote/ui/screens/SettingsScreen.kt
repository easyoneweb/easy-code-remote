package com.easycoderemote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.remote.LiveStream
import com.easycoderemote.ui.components.ErrorBanner
import com.easycoderemote.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()
    val approvals by viewModel.approvalsEnabled.collectAsStateWithLifecycle()
    val completions by viewModel.completionsEnabled.collectAsStateWithLifecycle()
    val attention by viewModel.attentionEnabled.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()

    val liveOn = liveState != LiveStream.StreamState.Stopped

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingSection("Live sync") {
                SettingRow("Live updates", "Foreground service keeps SSE connected", liveOn) { want ->
                    if (want) viewModel.enableLive() else viewModel.disableLive()
                }
                Text(
                    if (liveOn) "Connected: ${liveState.name}" else "Stopped — screens show cached data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SettingSection("Notifications") {
                SettingRow("Approvals", "Permission requests and questions", approvals, viewModel::setApprovals)
                SettingRow("Completions", "Agent runs finished or failed", completions, viewModel::setCompletions)
                SettingRow("Attention", "Engine down / live-stream problems", attention, viewModel::setAttention)
            }

            SettingSection("Connection") {
                testResult?.let { ErrorBanner(it) }
                Button(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("Test connection") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { /* battery-optimization exemption hint placeholder */ },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("Battery optimization hint") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: disable battery optimization for this app so the foreground service survives OEM battery killers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    Column(content = content)
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}