package com.easycoderemote.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.easycoderemote.data.repo.Repository
import kotlinx.coroutines.launch

/** 401 handler: re-enter the bearer token (plan §5.8, re-auth prompt). */
@Composable
fun ReauthDialog(
    profileId: String,
    repository: Repository,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token rejected") },
        text = {
            Column {
                Text("The server rejected your bearer token (HTTP 401). Re-enter the token to continue.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank(),
                onClick = {
                    scope.launch {
                        repository.securityStore.saveToken(profileId, token)
                        onDismiss()
                    }
                },
            ) { Text("Save token") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}