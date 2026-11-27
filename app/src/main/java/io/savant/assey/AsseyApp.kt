package io.savant.assey

import android.app.Application
import io.savant.assey.service.Notifications

class AsseyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannel(this)
    }
}
