package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.model.ModelEntryDto
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.providerDisplayName
import com.easycoderemote.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Provider → model list for the config hierarchy (plan D2). Reads /config via
 *  the per-profile cached fetch, so switching servers within the 60 s TTL never
 *  leaks the previous server's provider/models. */
class ProviderModelsViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle,
) : RepoViewModel(app) {

    val providerID: String = checkNotNull(savedStateHandle[Routes.ARG_PROVIDER_ID])

    private val _config = MutableStateFlow<ServerConfigDto?>(null)
    val config: StateFlow<ServerConfigDto?> = _config
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Models belonging to this provider (blank provider groups under "unknown"). */
    val models: StateFlow<List<ModelEntryDto>> =
        _config.map { cfg ->
            cfg?.models?.filter { (it.providerID?.takeIf { p -> p.isNotBlank() } ?: "unknown") == providerID }
                .orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Display name of this provider (raw name/displayName, else the id). */
    val providerName: StateFlow<String> = _config.map { cfg ->
        providerDisplayName(providerID, cfg?.providers ?: emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerID)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                _config.value = repo.fetchConfig()
            } catch (e: Exception) {
                error.value = e.message ?: "Failed to load config"
            }
            loading.value = false
        }
    }
}