package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.model.ServerConfigDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConfigViewModel(app: Application) : RepoViewModel(app) {

    private val _config = MutableStateFlow<ServerConfigDto?>(null)
    val config: StateFlow<ServerConfigDto?> = _config
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

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