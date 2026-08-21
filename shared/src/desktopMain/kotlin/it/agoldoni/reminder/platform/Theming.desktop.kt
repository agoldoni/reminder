package it.agoldoni.reminder.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Su Linux non esiste un equivalente dei colori dinamici: si usa la palette di default. */
@Composable
actual fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme? = null
