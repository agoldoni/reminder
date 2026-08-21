package it.agoldoni.reminder.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import it.agoldoni.reminder.di.AppContainer
import it.agoldoni.reminder.export.DesktopExportTarget
import it.agoldoni.reminder.export.OdsExporter
import it.agoldoni.reminder.platform.AppInfo
import it.agoldoni.reminder.platform.DesktopAlarmScheduler
import it.agoldoni.reminder.platform.ReminderRoot
import it.agoldoni.reminder.platform.createAppDatabase
import it.agoldoni.reminder.platform.formatDateTime
import it.agoldoni.reminder.platform.localDeviceId
import it.agoldoni.reminder.platform.nowMillis
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

private const val NEW_EVENT_ROUTE = "edit/0"

fun main() {
    val windowVisible = MutableStateFlow(true)

    val singleInstance = SingleInstance()
    if (!singleInstance.acquire { windowVisible.value = true }) {
        println("Promemoria è già in esecuzione: porto in primo piano la finestra esistente.")
        return
    }

    val deviceId = localDeviceId()
    val database = createAppDatabase(deviceId = deviceId)
    val eventDao = database.eventDao()
    val alarmScheduler = DesktopAlarmScheduler(eventDao)
    alarmScheduler.bootstrap()

    val container = AppContainer(
        eventDao = eventDao,
        alarmScheduler = alarmScheduler,
        deviceId = deviceId,
        appInfo = AppInfo(
            author = "Alberto Goldoni",
            version = "1.0",
            build = "desktop",
            buildDate = formatDateTime(nowMillis())
        ),
        exporter = OdsExporter(),
        exportTarget = DesktopExportTarget()
    )

    val navigationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autostart = Autostart()

    application {
        val visible by windowVisible.collectAsState()
        val icon = painterResource("icon.png")

        if (isTraySupported) {
            var autostartEnabled by remember { mutableStateOf(autostart.isEnabled()) }
            Tray(
                icon = icon,
                tooltip = "Promemoria",
                onAction = { windowVisible.value = true },
                menu = {
                    Item("Apri") { windowVisible.value = true }
                    Item("Nuovo promemoria") {
                        windowVisible.value = true
                        navigationRequests.tryEmit(NEW_EVENT_ROUTE)
                    }
                    if (autostart.isSupported) {
                        CheckboxItem("Avvia al login", checked = autostartEnabled) { checked ->
                            if (autostart.setEnabled(checked)) autostartEnabled = checked
                        }
                    }
                    Separator()
                    Item("Esci") {
                        singleInstance.release()
                        exitApplication()
                    }
                }
            )
        }

        Window(
            // Con la tray la chiusura nasconde soltanto: l'app resta attiva per gli allarmi
            onCloseRequest = {
                if (isTraySupported) {
                    windowVisible.value = false
                } else {
                    singleInstance.release()
                    exitApplication()
                }
            },
            visible = visible,
            state = rememberWindowState(width = 900.dp, height = 700.dp),
            title = "Promemoria",
            icon = icon
        ) {
            ReminderRoot(container, navigationRequests)
        }
    }
}
