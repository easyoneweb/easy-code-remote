package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Blocking certificate-changed screen: re-pin + re-auth. */
class CertChangedViewModel(app: Application) : RepoViewModel(app) {

    val token = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val done = MutableStateFlow(false)

    fun retrust() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val id = repo.profileStore.activeProfileId.first() ?: return@launch
            try {
                val result = repo.probe(repo.profileStore.profiles.first().firstOrNull { it.id == id }?.baseUrl ?: "")
                if (!result.success || result.fingerprint == null) {
                    error.value = result.error ?: "Probe failed"
                    return@launch
                }
                if (token.value.isBlank()) {
                    error.value = "Re-enter the bearer token to confirm"
                    return@launch
                }
                repo.retrust(id, token.value, result.fingerprint)
                done.value = true
            } catch (e: Exception) {
                error.value = e.message ?: "Failed"
            } finally {
                busy.value = false
            }
        }
    }
}