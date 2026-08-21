package it.agoldoni.reminder.platform

/** Su Linux nessun ambiente desktop conferma le copie: il messaggio lo dà l'app. */
actual fun sistemaConfermaLaCopia(): Boolean = false
