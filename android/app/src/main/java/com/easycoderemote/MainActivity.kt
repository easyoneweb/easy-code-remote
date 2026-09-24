package com.easycoderemote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.easycoderemote.data.model.UiEvent
import com.easycoderemote.service.AppForeground
import com.easycoderemote.service.LiveEventBus
import com.easycoderemote.ui.components.ReauthDialog
import com.easycoderemote.ui.navigation.AppNavHost
import com.easycoderemote.ui.navigation.Routes
import com.easycoderemote.ui.theme.EasyCodeRemoteTheme

class MainActivity : ComponentActivity() {

    private lateinit var navController: NavHostController

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            EasyCodeRemoteTheme {
                navController = rememberNavController()
                var certChanged by remember { mutableStateOf(false) }
                var reauthProfile by remember { mutableStateOf<String?>(null) }
                val repository = (application as EasyCodeRemoteApp).repository

                LaunchedEffect(Unit) {
                    handleDeepLink(intent)
                    LiveEventBus.uiEvents.collect { event ->
                        when (event) {
                            is UiEvent.CertChanged -> {
                                certChanged = true
                                navController.navigate(Routes.ROUTE_CERT_CHANGED) { launchSingleTop = true }
                            }
                            is UiEvent.ReauthRequired -> reauthProfile = event.profileId
                            else -> Unit
                        }
                    }
                }

                AppNavHost(navController = navController, certChanged = certChanged)

                reauthProfile?.let { pid ->
                    ReauthDialog(profileId = pid, repository = repository, onDismiss = { reauthProfile = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.visible = true
    }

    override fun onStop() {
        super.onStop()
        AppForeground.visible = false
    }

    private fun handleDeepLink(intent: Intent?) {
        val route = intent?.getStringExtra(Routes.EXTRA_ROUTE) ?: return
        val sessionId = intent.getStringExtra(Routes.EXTRA_SESSION_ID) ?: ""
        when (route) {
            Routes.ROUTE_APPROVAL -> {
                if (sessionId.isNotBlank()) {
                    navController.navigate(Routes.approval(sessionId)) { launchSingleTop = true }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}