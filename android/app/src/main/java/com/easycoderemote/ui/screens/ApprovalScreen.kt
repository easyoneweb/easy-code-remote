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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.easycoderemote.data.local.PendingItemEntity
import com.easycoderemote.data.model.QuestionOption
import com.easycoderemote.data.model.permissionSummary
import com.easycoderemote.data.model.questionHeader
import com.easycoderemote.data.model.questionOptions
import com.easycoderemote.data.model.questionSummary
import com.easycoderemote.data.model.questionText
import com.easycoderemote.ui.components.EmptyState
import com.easycoderemote.ui.components.ErrorBanner
import com.easycoderemote.util.APP_JSON
import com.easycoderemote.ui.viewmodel.ApprovalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(viewModel: ApprovalViewModel, onBack: () -> Unit) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approvals") },
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
            message?.let { Spacer(Modifier.height(4.dp)); ErrorBanner(it) }
            if (pending.isEmpty()) {
                EmptyState("Nothing pending. This screen also recovers requests that arrived while the app was closed.")
            }
            pending.forEach { item -> PendingCard(item, busy, viewModel) }
        }
    }
}

@Composable
private fun PendingCard(item: PendingItemEntity, busy: Boolean, viewModel: ApprovalViewModel) {
    val payload = runCatching { APP_JSON.parseToJsonElement(item.rawJson) }.getOrNull()
    val title = if (item.kind == "permission") payload.permissionSummary() else payload.questionSummary()
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (item.kind == "permission") "Permission request" else "Question",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (item.kind == "question") {
                // Render the question header + body properly (kilo wraps questions
                // in an array with `options`), with selectable variants (plan §9).
                val header = payload.questionHeader()
                val body = payload.questionText()
                if (header != title) {
                    Text(header, style = MaterialTheme.typography.titleSmall)
                }
                if (body.isNotBlank() && body != header) {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
                QuestionOptions(item, payload.questionOptions(), busy, viewModel)
            } else {
                Text(
                    item.rawJson.take(600),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.approve(item.id) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Approve") }
                    OutlinedButton(
                        onClick = { viewModel.deny(item.id) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Deny") }
                }
            }
        }
    }
}

/** Renders the answer options of a kilo question and submits the chosen one. */
@Composable
private fun QuestionOptions(item: PendingItemEntity, options: List<QuestionOption>, busy: Boolean, viewModel: ApprovalViewModel) {
    var selected by remember(item.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (options.isEmpty()) {
            // No parseable options: fall back to the raw payload + a plain answer.
            Text(
                item.rawJson.take(600),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.answer(item.id, listOf("yes")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Answer") }
                OutlinedButton(
                    onClick = { viewModel.rejectQuestion(item.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Reject") }
            }
            return
        }
        options.forEach { opt ->
            val chosen = selected == opt.label
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { selected = opt.label },
                colors = CardDefaults.cardColors(
                    containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (chosen) "●" else "○",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(opt.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (opt.description.isNotBlank()) {
                        Text(
                            opt.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { selected?.let { viewModel.answer(item.id, listOf(it)) } },
                enabled = !busy && selected != null,
                modifier = Modifier.weight(1f),
            ) { Text("Submit") }
            OutlinedButton(
                onClick = { viewModel.rejectQuestion(item.id) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Reject") }
        }
    }
}