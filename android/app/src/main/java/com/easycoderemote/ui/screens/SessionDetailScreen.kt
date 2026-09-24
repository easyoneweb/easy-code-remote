package com.easycoderemote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.local.MessageEntity
import com.easycoderemote.data.local.PartEntity
import com.easycoderemote.data.repo.TranscriptMessage
import com.easycoderemote.ui.components.StatusBadge
import com.easycoderemote.ui.viewmodel.SessionDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit,
    onOpenApproval: (String) -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(viewModel.sessionId) {
        viewModel.loadHistory()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.title?.take(48) ?: viewModel.sessionId, style = MaterialTheme.typography.titleMedium)
                        session?.let { StatusBadge(it.status, it.waitingReason) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = {
            Column {
                if (pending.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenApproval(viewModel.sessionId) },
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            "${pending.size} pending approval${if (pending.size > 1) "s" else ""} — open",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Composer(
                    text = composerText,
                    onTextChange = { viewModel.composerText.value = it },
                    queued = queued,
                    onQueuedChange = { viewModel.queued.value = it },
                    sending = sending,
                    onSend = viewModel::send,
                    onStop = viewModel::abort,
                    running = session?.status == "running",
                )
            }
        },
    ) { padding ->
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcript") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Diff") })
        }
        if (tab == 0) {
            TranscriptList(transcript, Modifier.fillMaxSize().padding(padding))
        } else {
            DiffTab(viewModel, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun TranscriptList(transcript: List<TranscriptMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = modifier) {
        if (transcript.isEmpty()) {
            item { Text("No messages yet. Send something below.", modifier = Modifier.padding(24.dp)) }
        }
        items(transcript, key = { it.message.id }) { tm ->
            MessageBubble(tm.message, tm.parts)
        }
    }
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, parts: List<PartEntity>) {
    val isUser = message.role == "user"
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Text(
                if (isUser) "you" else message.role.ifBlank { "assistant" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.82f else 1f)
                .align(if (isUser) Alignment.End else Alignment.Start),
            shape = MaterialTheme.shapes.medium,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(10.dp)) {
                parts.forEach { part -> PartView(part) }
            }
        }
    }
}

@Composable
private fun PartView(part: PartEntity) {
    if (part.type == "tool" || part.tool != null) {
        ToolChip(part)
    } else {
        Text(
            part.text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ToolChip(part: PartEntity) {
    var expanded by remember { mutableStateOf(false) }
    val label = part.tool ?: part.type
    val stateSuffix = part.state?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    Card(
        modifier = Modifier.padding(vertical = 4.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "$label$stateSuffix",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (expanded && part.text.isNotBlank()) {
                Text(part.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    queued: Boolean,
    onQueuedChange: (Boolean) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    running: Boolean,
) {
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Queued (no reply)", style = MaterialTheme.typography.labelMedium)
                Switch(checked = queued, onCheckedChange = onQueuedChange)
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f).height(56.dp),
                    placeholder = { Text(if (queued) "Message (queued — no reply)" else "Message the agent…") },
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    enabled = !sending,
                    onClick = if (running) onStop else onSend,
                ) {
                    Icon(
                        if (running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (running) "Stop" else "Send",
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffTab(viewModel: SessionDetailViewModel, modifier: Modifier = Modifier) {
    val diffJson by viewModel.diffJson.collectAsStateWithLifecycle()
    Column(modifier.padding(16.dp)) {
        OutlinedButton(onClick = viewModel::loadDiff, modifier = Modifier.fillMaxWidth()) { Text("Load diff summary") }
        diffJson?.let { raw ->
            Text(
                if (raw.isBlank()) "(empty response)" else raw,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}