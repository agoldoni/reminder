package it.agoldoni.reminder.platform

import java.util.UUID

actual fun newUuid(): String = UUID.randomUUID().toString()
