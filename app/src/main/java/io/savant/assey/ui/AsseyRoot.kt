package io.savant.assey.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.savant.assey.ui.screens.HomeScreen
import io.savant.assey.ui.screens.SettingsScreen
import io.savant.assey.ui.screens.TaskScreen

@Composable
fun AsseyRoot(controller: AppController) {
    val route by controller.route.collectAsState()
    val tasks by controller.tasks.collectAsState()
    val settings by controller.settings.collectAsState()
    val toast by controller.toast.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        val message = toast
        if (message != null) {
            snackbarHost.showSnackbar(message)
            controller.consumeToast()
        }
    }

    BackHandler(enabled = route != "home") { controller.back() }

    when {
        route.startsWith("task/") -> {
            val id = route.removePrefix("task/")
            TaskScreen(
                task = tasks.firstOrNull { it.id == id },
                controller = controller,
                snackbarHost = snackbarHost
            )
        }

        route == "settings" -> SettingsScreen(
            settings = settings,
            controller = controller,
            snackbarHost = snackbarHost
        )

        else -> HomeScreen(
            tasks = tasks,
            settings = settings,
            controller = controller,
            snackbarHost = snackbarHost
        )
    }
}
