package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.remote.CertProbe
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connect wizard state machine: BaseUrl → Probe → Token → Fingerprint confirm → save. */
class ProfileSetupViewModel(app: Application) : RepoViewModel(app) {

    enum class Step { BASE_URL, PROBE, FINGERPRINT, TOKEN, CONFIRM, DONE }

    data class UiState(
        val step: Step = Step.BASE_URL,
        val baseUrl: String = "",
        val name: String = "",
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

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun runProbe() {
        val url = _state.value.baseUrl.trim().ifBlank { return@runProbe }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) { repo.probe(url) }
            val next = _state.value.copy(busy = false, probe = result)
            if (result.success) {
                _state.value = next.copy(
                    // A collected fingerprint gates on an explicit TOFU confirmation;
                    // a non-TLS endpoint (http, debug builds) skips straight to token.
                    step = if (result.fingerprint == null) Step.TOKEN else Step.FINGERPRINT,
                    fingerprint = result.fingerprint ?: "",
                )
            } else {
                _state.value = next.copy(error = result.error ?: "Connection failed")
            }
        }
    }

    /** TOFU gate: the user explicitly accepts the presented fingerprint. */
    fun confirmFingerprint() {
        _state.value = _state.value.copy(step = Step.TOKEN)
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
            // Optional display name falls back to the host (then baseUrl) so the
            // profile is never blank-named.
            val host = runCatching { URL(s.baseUrl).host }.getOrDefault(s.baseUrl)
            val profile = repo.saveProfile(
                name = s.name.trim().ifBlank { host },
                baseUrl = s.baseUrl,
                token = s.token,
                fingerprint = s.fingerprint,
            )
            repo.startLiveSync()
            _state.value = _state.value.copy(busy = false, step = Step.DONE, savedProfileId = profile.id)
        }
    }
}