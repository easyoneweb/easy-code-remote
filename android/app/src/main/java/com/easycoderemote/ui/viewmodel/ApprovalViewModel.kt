package com.easycoderemote.ui.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.easycoderemote.data.local.PendingItemEntity
import com.easycoderemote.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ApprovalViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle,
) : RepoViewModel(app) {

    val sessionId: String = checkNotNull(savedStateHandle[Routes.ARG_SESSION_ID])

    val pending: StateFlow<List<PendingItemEntity>> = repo.observePending(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            busy.value = true
            repo.fetchPending(sessionId)
            busy.value = false
        }
    }

    fun approve(permissionId: String) {
        viewModelScope.launch {
            busy.value = true
            val outcome = repo.permissionReply(sessionId, permissionId, "allow", always = false)
            message.value = outcome.message
            busy.value = false
            refresh()
        }
    }

    fun deny(permissionId: String) {
        viewModelScope.launch {
            busy.value = true
            val outcome = repo.permissionReply(sessionId, permissionId, "deny", always = false)
            message.value = outcome.message
            busy.value = false
            refresh()
        }
    }

    fun answer(questionId: String, answers: List<String>) {
        viewModelScope.launch {
            busy.value = true
            val outcome = repo.questionReply(sessionId, questionId, answers)
            message.value = outcome.message
            busy.value = false
            refresh()
        }
    }

    fun rejectQuestion(questionId: String) {
        viewModelScope.launch {
            busy.value = true
            val outcome = repo.questionReject(sessionId, questionId)
            message.value = outcome.message
            busy.value = false
            refresh()
        }
    }
}