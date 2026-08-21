package it.agoldoni.reminder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import androidx.navigation.navArgument
import it.agoldoni.reminder.ui.completed.CompletedScreen
import it.agoldoni.reminder.ui.edit.EventEditScreen
import it.agoldoni.reminder.ui.list.EventListScreen
import it.agoldoni.reminder.ui.sync.SyncScreen

@Composable
fun ReminderNavHost(navigationRequests: Flow<String> = emptyFlow()) {
    val navController = rememberNavController()

    // Rotte richieste da fuori la composizione (menù della tray su desktop)
    LaunchedEffect(navigationRequests) {
        navigationRequests.collect { route -> navController.navigate(route) }
    }

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            EventListScreen(
                onAddEvent = { navController.navigate("edit/0") },
                onEditEvent = { id -> navController.navigate("edit/$id") },
                onNavigateToCompleted = { navController.navigate("completed") },
                onNavigateToSync = { navController.navigate("sync") }
            )
        }
        composable(
            route = "edit/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) { backStackEntry ->
            EventEditScreen(
                eventId = backStackEntry.arguments?.read { getLongOrNull("eventId") } ?: 0L,
                onBack = { navController.popBackStack() }
            )
        }
        composable("completed") {
            CompletedScreen(onBack = { navController.popBackStack() })
        }
        composable("sync") {
            SyncScreen(onBack = { navController.popBackStack() })
        }
    }
}
