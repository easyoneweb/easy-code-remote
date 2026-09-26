package com.easycoderemote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easycoderemote.ui.components.ErrorBanner
import com.easycoderemote.ui.viewmodel.ProfileSetupViewModel

/** Connect wizard: base URL → probe (health + fingerprint) → token → confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: ProfileSetupViewModel,
    onDone: (savedProfileId: String?) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add server") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state.step) {
                ProfileSetupViewModel.Step.BASE_URL, ProfileSetupViewModel.Step.PROBE -> {
                    Text("Server address", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        label = { Text("https://pc.example.com:8443") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Optional display name (plan D4); blank falls back to the host.
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::setName,
                        label = { Text("Server name (optional)") },
                        placeholder = { Text("Home PC") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.busy) {
                        CircularProgressIndicator()
                    } else {
                        Button(
                            onClick = viewModel::runProbe,
                            enabled = state.baseUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Connect") }
                    }
                    state.error?.let { Spacer(Modifier.height(8.dp)); ErrorBanner(it) }
                    state.probe?.takeIf { it.success }?.let { probe ->
                        Spacer(Modifier.height(16.dp))
                        Text("Health", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "engine ${probe.health?.engine ?: "?"} · server ${probe.health?.serverVersion ?: "?"} · " +
                                "kilo ${probe.health?.kiloVersion ?: "?"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        probe.chainSize.takeIf { it > 0 }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text("Presented certificate chain: $it certs", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                ProfileSetupViewModel.Step.FINGERPRINT -> {
                    Text("Verify the server certificate", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is the first connection to this server. Confirm the fingerprint below matches the one shown by `easy-code-remote status` on your PC. " +
                            "If it does not match, do not continue.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    state.fingerprint.takeIf { it.isNotBlank() }?.let { fp ->
                        Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelLarge)
                        Text(
                            fp,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Button(
                        onClick = viewModel::confirmFingerprint,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Trust this certificate") }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                }

                ProfileSetupViewModel.Step.TOKEN, ProfileSetupViewModel.Step.CONFIRM -> {
                    Text("Enter the bearer token", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The token is in the server config (run `easy-code-remote status` on the PC).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.token,
                        onValueChange = viewModel::setToken,
                        label = { Text("Bearer token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.busy) {
                        CircularProgressIndicator()
                    } else {
                        Button(
                            onClick = viewModel::confirmAndSave,
                            enabled = state.token.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Connect") }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                    }
                    state.error?.let { Spacer(Modifier.height(8.dp)); ErrorBanner(it) }
                }

                ProfileSetupViewModel.Step.DONE -> {
                    Text("Connected!", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Live sync is enabled. You can now watch and drive your sessions.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onDone(state.savedProfileId) }, modifier = Modifier.fillMaxWidth()) { Text("Open sessions") }
                }
            }
        }
    }
}