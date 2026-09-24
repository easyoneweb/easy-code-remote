package com.easycoderemote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.remote.LiveStream
import com.easycoderemote.ui.components.EmptyState
import com.easycoderemote.ui.components.StatusBadge
import com.easycoderemote.ui.viewmodel.SessionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (String) -> Unit,
    onConfig: () -> Unit,
    onSettings: () -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = onConfig) { Icon(Icons.Default.Settings, contentDescription = "Config") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LiveToggle(liveState = liveState, viewModel = viewModel)
            if (sessions.isEmpty()) {
                EmptyState("No sessions yet. Start one on your PC or from this phone.")
            } else {
                LazyColumn {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(session = session, onClick = { onOpenSession(session.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveToggle(liveState: LiveStream.StreamState, viewModel: SessionsViewModel) {
    val on = liveState != LiveStream.StreamState.Stopped
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (on) "Live updates: ${liveState.name}" else "Live updates off",
            style = MaterialTheme.typography.bodyMedium,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Switch(
            checked = on,
            onCheckedChange = { wantOn ->
                if (wantOn) viewModel.enableLive() else viewModel.disableLive()
            },
        )
    }
}

@Composable
private fun SessionCard(session: SessionDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (session.isArchived) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    session.title.ifBlank { session.slug ?: session.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(session.status, session.waitingReason)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                session.agent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                session.modelLabel().takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            session.directory?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            if (session.tokenCount > 0) {
                Text("tokens: ${session.tokenCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (session.isArchived) Text("archived", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}