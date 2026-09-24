package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.remote.CertProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connect wizard state machine: BaseUrl → Probe → Token → Fingerprint confirm → save. */
class ProfileSetupViewModel(app: Application) : RepoViewModel(app) {

    enum class Step { BASE_URL, PROBE, TOKEN, CONFIRM, DONE }

    data class UiState(
        val step: Step = Step.BASE_URL,
        val baseUrl: String = "",
        val probe: CertProbe.Result? = null,
        val token: String = "",
        val fingerprint: String = "",
        val error: String? = null,
        val busy: Boolean = false,
        val savedProfileId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setBaseUrl(value: String) {
        _state.value = _state.value.copy(baseUrl = value)
    }

    fun runProbe() {
        val url = _state.value.baseUrl.trim().ifBlank { return@runProbe }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) { repo.probe(url) }
            val next = _state.value.copy(busy = false, probe = result)
            if (result.success) {
                _state.value = next.copy(
                    step = Step.TOKEN,
                    fingerprint = result.fingerprint ?: "",
                )
            } else {
                _state.value = next.copy(error = result.error ?: "Connection failed")
            }
        }
    }

    fun setToken(value: String) {
        _state.value = _state.value.copy(token = value)
    }

    fun confirmAndSave() {
        val s = _state.value
        if (s.fingerprint.isBlank() || s.token.isBlank()) {
            _state.value = s.copy(error = "A bearer token is required")
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(busy = true, error = null)
            val profile = repo.saveProfile(
                name = s.baseUrl,
                baseUrl = s.baseUrl,
                token = s.token,
                fingerprint = s.fingerprint,
            )
            repo.startLiveSync()
            _state.value = _state.value.copy(busy = false, step = Step.DONE, savedProfileId = profile.id)
        }
    }
}