package com.easycoderemote.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.data.local.PartEntity
import com.easycoderemote.data.model.ModelEntryDto
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.model.badgeLabel
import com.easycoderemote.data.model.providerModelLabel
import com.easycoderemote.data.repo.TranscriptMessage
import com.easycoderemote.render.markdown.MarkdownText
import com.easycoderemote.ui.components.LoadingRow
import com.easycoderemote.ui.components.StatusBadge
import com.easycoderemote.ui.viewmodel.ComposerOverrides
import com.easycoderemote.ui.viewmodel.SessionDetailViewModel
import com.easycoderemote.ui.viewmodel.SessionWindow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Cap for expanded tool input/output text inside a chip. */
private const val TOOL_TEXT_CAP = 16 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit,
    onOpenApproval: (String) -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val window by viewModel.transcript.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val compacting by viewModel.compacting.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val modelElem by viewModel.model.collectAsStateWithLifecycle()
    val variant by viewModel.variant.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            session?.title?.take(48) ?: viewModel.sessionId,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        session?.let { s ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusBadge(s.status, s.waitingReason)
                                Text(
                                    "agent: ${s.agent ?: "—"}",
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "model: ${providerModelLabel(s.sessionModelProvider(), s.sessionModelId()) ?: "—"}",
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
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
                    session = session,
                    config = config,
                    agent = agent,
                    onAgentChange = viewModel::selectAgent,
                    model = modelElem,
                    onModelSelect = viewModel::selectModel,
                    variant = variant,
                    onVariantChange = viewModel::selectVariant,
                )
            }
        },
    ) { padding ->
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcript") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Diff") })
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (compacting) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "Transcript rewritten — reloading…",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (tab == 0) {
                TranscriptList(window, Modifier.fillMaxSize(), onLoadOlder = viewModel::loadOlder)
            } else {
                DiffTab(viewModel, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TranscriptList(
    window: SessionWindow,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit,
) {
    val items = window.items // newest-first (index 0 = live edge)
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Live-edge autoscroll: stay pinned to the newest message while the user
    // hasn't scrolled into history (plan §5.8). Re-anchor when the newest
    // message's content grows (streaming) so the tail of the active block stays
    // visible instead of being pushed below the fold. Instant jump (never
    // animate), and only while actually pinned at the live edge — never during
    // an active scroll into history, so large transcripts never stutter.
    val newest = items.firstOrNull()
    val newestContentLen = newest?.parts?.sumOf { it.text.length } ?: 0
    LaunchedEffect(items.size, newest?.message?.id, newestContentLen) {
        if (items.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    // Load older pages when the user scrolls toward the past (reverseLayout →
    // the oldest items live at the highest indices).
    LaunchedEffect(listState.firstVisibleItemIndex, window.hasMore, window.loadingOlder, items.size) {
        if (items.isNotEmpty() && window.hasMore && !window.loadingOlder &&
            listState.firstVisibleItemIndex >= items.size - 5
        ) {
            onLoadOlder()
        }
    }

    LazyColumn(state = listState, modifier = modifier, reverseLayout = true) {
        if (items.isEmpty()) {
            item { Text("No messages yet. Send something below.", modifier = Modifier.padding(24.dp)) }
        }
        items(items, key = { it.message.id }) { tm ->
            // The newest message is the one being appended to (streaming text): only
            // it gets the debounced markdown re-render (plan §5.7).
            val isStreaming = items.firstOrNull()?.message?.id == tm.message.id
            MessageBubble(
                tm = tm,
                isStreaming = isStreaming,
                onCopy = {
                    val raw = tm.parts
                        .filter { it.type != "tool" && it.tool == null }
                        .joinToString("\n") { it.text }
                    if (raw.isNotBlank()) {
                        clipboard.setText(AnnotatedString(raw))
                        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        if (window.loadingOlder) {
            item(key = "loading-older") { LoadingRow() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(tm: TranscriptMessage, isStreaming: Boolean, onCopy: () -> Unit) {
    val message = tm.message
    val isUser = message.role == "user"
    // Skip messages with no visible content: kilo transcripts contain empty text
    // parts (parts created before text arrives), which would otherwise render as
    // phantom empty "assistant" cards.
    val visibleParts = tm.parts.filter { it.type == "tool" || it.tool != null || it.text.isNotBlank() }
    if (visibleParts.isEmpty()) return
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
        // Message-local agent/model caption on assistant bubbles (plan: badge labels).
        if (!isUser) {
            badgeLabel(message.agent, message.providerID, message.modelID)?.let { badge ->
                Text(
                    badge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 1f)
                .align(if (isUser) Alignment.End else Alignment.Start)
                .combinedClickable(onClick = {}, onLongClick = onCopy),
            shape = MaterialTheme.shapes.medium,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(10.dp)) {
                tm.parts.forEach { part -> PartView(part, isUser, isStreaming) }
            }
        }
    }
}

@Composable
private fun PartView(part: PartEntity, isUser: Boolean, isStreaming: Boolean) {
    if (part.type == "tool" || part.tool != null) {
        ToolChip(part)
    } else if (isUser) {
        // User input stays plain text (plan §5.7): no markdown, no layout spoofing.
        if (part.text.isNotBlank()) Text(part.text, style = MaterialTheme.typography.bodyMedium)
    } else {
        // Skip empty text parts (phantom cards) — kilo creates parts before text arrives.
        if (part.text.isNotBlank()) MarkdownText(part.text, isStreaming = isStreaming)
    }
}

@Composable
private fun ToolChip(part: PartEntity) {
    var expanded by remember { mutableStateOf(false) }
    val label = part.tool ?: part.type
    val state = part.state?.takeIf { it.isNotBlank() }
    val isBusy = state == "running" || state == "pending"
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                }
                Text(
                    "$label${state?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (expanded && part.text.isNotBlank()) {
                val capped = part.text.length > TOOL_TEXT_CAP
                Text(
                    if (capped) part.text.take(TOOL_TEXT_CAP) + "\n… (truncated)" else part.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
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
    session: SessionDto?,
    config: ServerConfigDto?,
    agent: String?,
    onAgentChange: (String?) -> Unit,
    model: JsonElement?,
    onModelSelect: (providerID: String?, id: String) -> Unit,
    variant: String?,
    onVariantChange: (String?) -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Slash-command autocomplete (plan §9 Phase 3).
            val commands = config?.commands?.mapNotNull { jobName(it) } ?: emptyList()
            val suggestions = remember(text, commands) {
                if (text.startsWith("/") && !text.startsWith("//")) {
                    val query = text.removePrefix("/").substringBefore(' ').trim()
                    commands
                        .filter { query.isEmpty() || it.startsWith(query, ignoreCase = true) }
                        .take(5)
                } else {
                    emptyList()
                }
            }
            if (suggestions.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth()) {
                        suggestions.forEach { name ->
                            TextButton(
                                onClick = { onTextChange("/$name ") },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(name, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // Agent / model / variant pickers (plan §9 Phase 3).
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val agentOptions = config?.agents?.mapNotNull { jobName(it) } ?: emptyList()
                PickerChip("agent", ComposerOverrides.resolveAgent(agent, session), agentOptions, onAgentChange)

                // Model: provider-grouped searchable picker sheet. The chip shows the
                // override's display name (or its id when the config entry is
                // missing), else the session's active `provider · id`, else "auto".
                val overrideModelId = (model as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                val overrideEntry = overrideModelId
                    ?.let { mid -> config?.models?.firstOrNull { it.id == mid } }
                val sessionModelEntry = session?.sessionModelId()
                    ?.let { mid -> config?.models?.firstOrNull { it.id == mid } }
                val chosenModel = overrideEntry ?: sessionModelEntry
                var showModelPicker by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showModelPicker = true }, modifier = Modifier.padding(vertical = 2.dp).widthIn(max = 200.dp)) {
                    Text(
                        "model: ${ComposerOverrides.resolveModelLabel(overrideEntry?.displayName ?: overrideModelId, session)}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showModelPicker) {
                    ModelPickerSheet(
                        models = config?.models ?: emptyList(),
                        loading = config == null,
                        initialProviderID = overrideEntry?.providerID,
                        initialSearch = overrideModelId,
                        onSelect = { providerID, id ->
                            onModelSelect(providerID, id)
                            showModelPicker = false
                        },
                        onDismiss = { showModelPicker = false },
                    )
                }

                PickerChip(
                    "variant",
                    ComposerOverrides.resolveVariant(variant, session, chosenModel),
                    ComposerOverrides.variantOptions(chosenModel),
                    onVariantChange,
                )
            }

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
private fun PickerChip(label: String, current: String?, options: List<String>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.padding(vertical = 2.dp).widthIn(max = 200.dp)) {
            Text(
                "$label: ${current ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.distinct().forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); open = false })
            }
            // "(none)" maps to a null override → the chip falls back to the session
            // active value; it is never a hard "none" state.
            DropdownMenuItem(text = { Text("(none)") }, onClick = { onSelect(null); open = false })
        }
    }
}

/**
 * Level 1 (providers) → level 2 (searchable models of one provider) model picker.
 * Opens on the override's provider+model when one is active, else on providers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<ModelEntryDto>,
    loading: Boolean,
    initialProviderID: String?,
    initialSearch: String?,
    onSelect: (providerID: String?, id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pre-group by providerID once per config for O(1) level-2 access; name is
    // only used for a missing-provider bucket (plan: "unknown").
    val byProvider = remember(models) {
        models.groupBy { it.providerID?.takeIf { p -> p.isNotBlank() } ?: "unknown" }
            .toSortedMap()
    }
    var providerID by remember { mutableStateOf(initialProviderID?.takeIf { it.isNotBlank() }) }
    var search by remember { mutableStateOf(initialSearch.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        when {
            loading -> Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            models.isEmpty() -> Text(
                "No models available",
                modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally),
            )

            providerID == null -> ProviderLevel(
                byProvider = byProvider,
                onPickProvider = { providerID = it },
            )

            else -> {
                val pid = providerID ?: "unknown"
                val providerModels = byProvider[pid].orEmpty()
                ModelLevel(
                    providerID = pid,
                    models = providerModels,
                    search = search,
                    onSearchChange = { search = it },
                    onSelect = { modelId ->
                        // Pass the model's actual providerID (null for the "unknown"
                        // bucket), never the display group key.
                        val realProvider = providerModels.firstOrNull { it.id == modelId }?.providerID
                        onSelect(realProvider, modelId)
                    },
                    onBack = { providerID = null },
                )
            }
        }
    }
}

@Composable
private fun ProviderLevel(
    byProvider: Map<String, List<ModelEntryDto>>,
    onPickProvider: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        item {
            Text(
                "Providers",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        items(byProvider.toList(), key = { it.first }) { (pid, providerModels) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPickProvider(pid) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(pid, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "· ${providerModels.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun ModelLevel(
    providerID: String,
    models: List<ModelEntryDto>,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Column(Modifier.weight(1f)) {
                Text(
                    providerID,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${models.size} models",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true,
            placeholder = { Text("Search models…") },
        )
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
        if (filtered.isEmpty()) {
            Text(
                "No models match",
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(filtered, key = { it.id }) { m ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(m.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.id != m.displayName) {
                            Text(
                                m.id,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun jobName(el: JsonElement): String? = when (el) {
    is JsonObject -> el["name"]?.jsonPrimitive?.contentOrNull
        ?: el["id"]?.jsonPrimitive?.contentOrNull
    else -> null
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