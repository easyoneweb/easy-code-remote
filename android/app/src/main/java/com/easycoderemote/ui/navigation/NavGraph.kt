package com.easycoderemote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.easycoderemote.ui.screens.ApprovalScreen
import com.easycoderemote.ui.screens.CertChangedScreen
import com.easycoderemote.ui.screens.ConfigScreen
import com.easycoderemote.ui.screens.ProfileSetupScreen
import com.easycoderemote.ui.screens.ProfilesScreen
import com.easycoderemote.ui.screens.ProviderModelsScreen
import com.easycoderemote.ui.screens.SessionDetailScreen
import com.easycoderemote.ui.screens.SessionsScreen
import com.easycoderemote.ui.screens.SettingsScreen
import com.easycoderemote.ui.viewmodel.ApprovalViewModel
import com.easycoderemote.ui.viewmodel.CertChangedViewModel
import com.easycoderemote.ui.viewmodel.ConfigViewModel
import com.easycoderemote.ui.viewmodel.ProfileSetupViewModel
import com.easycoderemote.ui.viewmodel.ProfilesViewModel
import com.easycoderemote.ui.viewmodel.ProviderModelsViewModel
import com.easycoderemote.ui.viewmodel.SessionDetailViewModel
import com.easycoderemote.ui.viewmodel.SessionsViewModel
import com.easycoderemote.ui.viewmodel.SettingsViewModel

@Composable
fun AppNavHost(navController: NavHostController, certChanged: Boolean) {
    NavHost(navController = navController, startDestination = if (certChanged) Routes.ROUTE_CERT_CHANGED else Routes.ROUTE_PROFILES) {
        composable(Routes.ROUTE_PROFILES) {
            val vm: ProfilesViewModel = viewModel()
            ProfilesScreen(
                viewModel = vm,
                onAddProfile = { navController.navigate(Routes.ROUTE_PROFILE_SETUP) },
                onOpenSessions = { navController.navigate(Routes.ROUTE_SESSIONS) { launchSingleTop = true } },
                onSettings = { navController.navigate(Routes.ROUTE_SETTINGS) },
            )
        }
        composable(Routes.ROUTE_PROFILE_SETUP) {
            val vm: ProfileSetupViewModel = viewModel()
            ProfileSetupScreen(
                viewModel = vm,
                onDone = {
                    navController.popBackStack(Routes.ROUTE_PROFILES, inclusive = false)
                    navController.navigate(Routes.ROUTE_SESSIONS)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ROUTE_SESSIONS) {
            val vm: SessionsViewModel = viewModel()
            SessionsScreen(
                viewModel = vm,
                onOpenSession = { id -> navController.navigate(Routes.sessionDetail(id)) },
                onConfig = { navController.navigate(Routes.ROUTE_CONFIG) },
                onSettings = { navController.navigate(Routes.ROUTE_SETTINGS) },
            )
        }
        composable(
            route = Routes.ROUTE_SESSION_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) { entry ->
            val vm: SessionDetailViewModel = viewModel(entry)
            SessionDetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenApproval = { id -> navController.navigate(Routes.approval(id)) },
            )
        }
        composable(
            route = Routes.ROUTE_APPROVAL,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) { entry ->
            val vm: ApprovalViewModel = viewModel(entry)
            ApprovalScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.ROUTE_CONFIG) {
            val vm: ConfigViewModel = viewModel()
            ConfigScreen(
                viewModel = vm,
                onOpenProvider = { pid -> navController.navigate(Routes.providerModels(pid)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.ROUTE_PROVIDER_MODELS,
            arguments = listOf(navArgument(Routes.ARG_PROVIDER_ID) { type = NavType.StringType }),
        ) { entry ->
            val vm: ProviderModelsViewModel = viewModel(entry)
            ProviderModelsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.ROUTE_SETTINGS) {
            val vm: SettingsViewModel = viewModel()
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.ROUTE_CERT_CHANGED) {
            val vm: CertChangedViewModel = viewModel()
            CertChangedScreen(viewModel = vm, onTrusted = {
                navController.navigate(Routes.ROUTE_PROFILES) { popUpTo(Routes.ROUTE_CERT_CHANGED) { inclusive = true } }
            })
        }
    }
}