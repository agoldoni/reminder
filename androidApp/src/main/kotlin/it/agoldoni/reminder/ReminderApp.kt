package it.agoldoni.reminder

import android.app.Application
import android.content.Context
import it.agoldoni.reminder.di.AppContainer
import it.agoldoni.reminder.export.AndroidExportTarget
import it.agoldoni.reminder.export.OdsExporter
import it.agoldoni.reminder.platform.AndroidAlarmScheduler
import it.agoldoni.reminder.platform.AndroidAppContainer
import it.agoldoni.reminder.platform.AppInfo
import it.agoldoni.reminder.platform.createAppDatabase
import it.agoldoni.reminder.platform.localDeviceId

class ReminderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val deviceId = localDeviceId(this)
        val database = createAppDatabase(this, deviceId)
        container = AppContainer(
            eventDao = database.eventDao(),
            alarmScheduler = AndroidAlarmScheduler(this),
            deviceId = deviceId,
            appInfo = AppInfo(
                author = BuildConfig.APP_AUTHOR,
                version = BuildConfig.VERSION_NAME,
                build = BuildConfig.VERSION_CODE.toString(),
                buildDate = BuildConfig.BUILD_DATE
            ),
            exporter = OdsExporter(),
            exportTarget = AndroidExportTarget(this)
        )
        AndroidAppContainer.instance = container
    }
}

/** Accesso al container da qualsiasi punto che disponga di un [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as ReminderApp).container
