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
import it.agoldoni.reminder.platform.AndroidAppSettings
import it.agoldoni.reminder.platform.localDeviceId
import it.agoldoni.reminder.platform.localDeviceName
import it.agoldoni.reminder.sync.LocalIdentity
import it.agoldoni.reminder.sync.NsdDiscovery
import it.agoldoni.reminder.sync.SyncEngine
import it.agoldoni.reminder.sync.SyncService
import it.agoldoni.reminder.web.WebService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReminderApp : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var syncService: SyncService
        private set

    /**
     * Costruito qui e non in `MainActivity` di proposito: vive quanto il processo. Legato
     * all'Activity, ogni rotazione dello schermo rigenererebbe il token e l'indirizzo già digitato
     * sull'altro dispositivo smetterebbe di funzionare.
     */
    lateinit var webService: WebService
        private set

    override fun onCreate() {
        super.onCreate()
        val deviceId = localDeviceId(this)
        val database = createAppDatabase(this, deviceId)
        val alarmScheduler = AndroidAlarmScheduler(this)
        // Ad app chiusa il telefono non ascolta — Android non lascia tenere un socket aperto — e
        // sincronizza chiamando lui al rientro in primo piano (vedi MainActivity). Mentre la
        // schermata di sincronizzazione è aperta ascolta anche lui: serve sulle reti dove è il
        // telefono a non raggiungere il PC.
        val settings = AndroidAppSettings(this)
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        syncService = SyncService(
            identity = LocalIdentity(deviceId, localDeviceName(this)),
            peers = database.peerDao(),
            engine = SyncEngine(database.eventDao(), alarmScheduler),
            discovery = NsdDiscovery(this, syncScope),
            settings = settings,
            scope = syncScope,
            listensInBackground = false
        )
        syncService.start()

        // Scope proprio: con la schermata di sincronizzazione aperta i due server sono in ascolto
        // insieme, e non devono condividere né stato né sorte.
        webService = WebService(
            dao = database.eventDao(),
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        container = AppContainer(
            eventDao = database.eventDao(),
            alarmScheduler = alarmScheduler,
            deviceId = deviceId,
            appInfo = AppInfo(
                author = BuildConfig.APP_AUTHOR,
                version = BuildConfig.VERSION_NAME,
                build = BuildConfig.VERSION_CODE.toString(),
                buildDate = BuildConfig.BUILD_DATE
            ),
            exporter = OdsExporter(),
            exportTarget = AndroidExportTarget(this),
            sync = syncService,
            web = webService
        )
        AndroidAppContainer.instance = container
    }
}

/** Accesso al container da qualsiasi punto che disponga di un [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as ReminderApp).container
