package it.agoldoni.reminder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.agoldoni.reminder.ui.completed.CompletedScreen
import it.agoldoni.reminder.ui.edit.EventEditScreen
import it.agoldoni.reminder.ui.list.EventListScreen

@Composable
fun ReminderNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            EventListScreen(
                onAddEvent = { navController.navigate("edit/0") },
                onEditEvent = { id -> navController.navigate("edit/$id") },
                onNavigateToCompleted = { navController.navigate("completed") }
            )
        }
        composable(
            route = "edit/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) {
            EventEditScreen(onBack = { navController.popBackStack() })
        }
        composable("completed") {
            CompletedScreen(onBack = { navController.popBackStack() })
        }
    }
}
