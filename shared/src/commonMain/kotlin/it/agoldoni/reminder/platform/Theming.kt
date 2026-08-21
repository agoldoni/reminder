package it.agoldoni.reminder.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Colori dinamici del sistema: disponibili su Android 12+, assenti altrove. */
@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?
