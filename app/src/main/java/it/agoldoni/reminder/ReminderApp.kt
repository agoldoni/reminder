package it.agoldoni.reminder

import android.app.Application
import it.agoldoni.reminder.di.AppContainer

class ReminderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
