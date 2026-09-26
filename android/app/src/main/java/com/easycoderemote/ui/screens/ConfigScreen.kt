package com.easycoderemote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.groupModelsByProvider
import com.easycoderemote.data.model.providerDisplayName
import com.easycoderemote.ui.components.EmptyState
import com.easycoderemote.ui.components.ErrorBanner
import com.easycoderemote.ui.components.LoadingRow
import com.easycoderemote.ui.components.SectionHeader
import com.easycoderemote.ui.viewmodel.ConfigViewModel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    onOpenProvider: (providerID: String) -> Unit,
    onBack: () -> Unit,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server config") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        when {
            loading && config == null -> LoadingRow(Modifier.fillMaxSize().padding(padding))
            error != null -> ErrorBanner(error!!, Modifier.padding(padding))
            config != null -> ConfigBody(config!!, onOpenProvider, Modifier.fillMaxSize().padding(padding))
            else -> EmptyState("No config yet")
        }
    }
}

@Composable
private fun ConfigBody(
    config: ServerConfigDto,
    onOpenProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byProvider = remember(config) { groupModelsByProvider(config.models) }
    LazyColumn(modifier) {
        // Providers (overview) → per-provider model list (plan D2).
        item { SectionHeader("Providers (${byProvider.size})") }
        if (byProvider.isEmpty()) {
            item {
                Text(
                    "No providers",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            items(byProvider.toList(), key = { "provider:${it.first}" }) { (pid, providerModels) ->
                ProviderRow(
                    name = providerDisplayName(pid, config.providers),
                    modelCount = providerModels.size,
                    onClick = { onOpenProvider(pid) },
                )
            }
        }
        item { SectionHeader("Agents (${config.agents.size})") }
        items(config.agents, key = { "agent:$it" }) { el -> ConfigLine(jobTitle(el), jobDetail(el)) }
        item { SectionHeader("Commands (${config.commands.size})") }
        items(config.commands, key = { "command:$it" }) { el -> ConfigLine(jobTitle(el), jobDetail(el)) }
        item { SectionHeader("Skills (${config.skills.size})") }
        items(config.skills, key = { "skill:$it" }) { el -> ConfigLine(jobTitle(el), jobDetail(el)) }
    }
}

@Composable
private fun ProviderRow(name: String, modelCount: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "· $modelCount models",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Open provider models")
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

private fun jobTitle(el: JsonElement): String = when (el) {
    is JsonObject -> el["name"]?.jsonPrimitive?.contentOrNull
        ?: el["id"]?.jsonPrimitive?.contentOrNull ?: el.toString().take(60)
    else -> el.toString().take(60)
}

private fun jobDetail(el: JsonElement): String = when (el) {
    is JsonObject -> el["description"]?.jsonPrimitive?.contentOrNull ?: ""
    else -> ""
}

@Composable
private fun ConfigLine(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}