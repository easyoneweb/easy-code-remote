package com.easycoderemote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.model.ServerConfigDto
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
fun ConfigScreen(viewModel: ConfigViewModel, onBack: () -> Unit) {
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
            config != null -> ConfigBody(config!!, Modifier.fillMaxSize().padding(padding))
            else -> EmptyState("No config yet")
        }
    }
}

@Composable
private fun ConfigBody(config: ServerConfigDto, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        item { SectionHeader("Models (${config.models.size})") }
        items(config.models, key = { "${it.providerID}:${it.id}" }) { m ->
            ConfigLine("${m.displayName} (${m.providerID ?: "?"})", m.id)
        }
        item { SectionHeader("Agents (${config.agents.size})") }
        items(config.agents) { el -> ConfigLine(jobTitle(el), jobDetail(el)) }
        item { SectionHeader("Commands (${config.commands.size})") }
        items(config.commands) { el -> ConfigLine(jobTitle(el), jobDetail(el)) }
        item { SectionHeader("Skills (${config.skills.size})") }
        items(config.skills) { el -> ConfigLine(jobTitle(el), jobDetail(el)) }
    }
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