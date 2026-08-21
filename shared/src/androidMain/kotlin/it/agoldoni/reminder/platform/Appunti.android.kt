package it.agoldoni.reminder.platform

import android.os.Build

actual fun sistemaConfermaLaCopia(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
