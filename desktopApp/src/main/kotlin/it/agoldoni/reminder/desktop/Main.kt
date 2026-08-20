package it.agoldoni.reminder.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import it.agoldoni.reminder.di.AppContainer
import it.agoldoni.reminder.export.DesktopExportTarget
import it.agoldoni.reminder.export.OdsExporter
import it.agoldoni.reminder.platform.AppInfo
import it.agoldoni.reminder.platform.DesktopAlarmScheduler
import it.agoldoni.reminder.platform.ReminderRoot
import it.agoldoni.reminder.platform.createAppDatabase
import it.agoldoni.reminder.platform.formatDateTime
import it.agoldoni.reminder.platform.nowMillis

fun main() {
    val database = createAppDatabase()
    val eventDao = database.eventDao()
    val alarmScheduler = DesktopAlarmScheduler(eventDao)
    alarmScheduler.bootstrap()

    val container = AppContainer(
        eventDao = eventDao,
        alarmScheduler = alarmScheduler,
        appInfo = AppInfo(
            author = "Alberto Goldoni",
            version = "1.0",
            build = "desktop",
            buildDate = formatDateTime(nowMillis())
        ),
        exporter = OdsExporter(),
        exportTarget = DesktopExportTarget()
    )

    application {
        Window(onCloseRequest = ::exitApplication, title = "Promemoria") {
            ReminderRoot(container)
        }
    }
}
