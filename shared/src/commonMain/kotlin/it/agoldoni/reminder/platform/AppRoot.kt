package it.agoldoni.reminder.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import it.agoldoni.reminder.di.AppContainer
import it.agoldoni.reminder.ui.navigation.ReminderNavHost
import it.agoldoni.reminder.ui.theme.ReminderTheme

/** Container delle dipendenze visibile a tutte le schermate. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer non fornito: usa ReminderRoot(container)")
}

/** Radice comune ad Android e desktop: container + tema + navigazione. */
@Composable
fun ReminderRoot(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        ReminderTheme {
            ReminderNavHost()
        }
    }
}
