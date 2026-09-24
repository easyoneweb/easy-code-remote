package com.easycoderemote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.ui.components.ErrorBanner
import com.easycoderemote.ui.viewmodel.CertChangedViewModel

/** Blocking screen shown when the pinned certificate no longer matches. */
@Composable
fun CertChangedScreen(viewModel: CertChangedViewModel, onTrusted: () -> Unit) {
    val token by viewModel.token.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("Certificate changed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(
                "The server presented a certificate that does not match the fingerprint this app trusted. " +
                    "This happens after `easy-code-remote cert regen` — or it can be a man-in-the-middle attack. " +
                    "Only trust the new certificate if you initiated the change.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { viewModel.token.value = it },
                label = { Text("Re-enter bearer token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
            )
            Spacer(Modifier.height(16.dp))
            if (busy) {
                Text("Reconnecting…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(
                    onClick = viewModel::retrust,
                    enabled = token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Trust new certificate") }
                if (done) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onTrusted, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                }
            }
            error?.let { Spacer(Modifier.height(8.dp)); ErrorBanner(it) }
            Spacer(Modifier.height(16.dp))
            Text(
                "Note: live sync stays off until you re-pin. Any other profile is unaffected.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}