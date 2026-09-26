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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.model.ModelEntryDto
import com.easycoderemote.ui.components.EmptyState
import com.easycoderemote.ui.components.ErrorBanner
import com.easycoderemote.ui.components.LoadingRow
import com.easycoderemote.ui.viewmodel.ProviderModelsViewModel

/** Models of one provider (plan D2): display name, mono id, variant list,
 *  optional search field, and an in-screen spinner/empty state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderModelsScreen(viewModel: ProviderModelsViewModel, onBack: () -> Unit) {
    val providerName by viewModel.providerName.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(providerName, maxLines = 1) },
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
            else -> ModelList(
                providerID = viewModel.providerID,
                models = models,
                search = search,
                onSearchChange = { search = it },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun ModelList(
    providerID: String,
    models: List<ModelEntryDto>,
    search: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(models, search) {
        val q = search.trim()
        if (q.isEmpty()) {
            models
        } else {
            models.filter { m ->
                m.id.contains(q, ignoreCase = true) || m.displayName.contains(q, ignoreCase = true)
            }
        }
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search models…") },
            )
        }
        when {
            models.isEmpty() -> EmptyState("No models for $providerID")
            filtered.isEmpty() -> EmptyState("No models match")
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(filtered, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (m.id != m.displayName) {
                            Text(
                                m.id,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                        }
                        val variants = m.variantNames()
                        Text(
                            "variants: ${variants.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}