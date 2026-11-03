package com.nezhahq.agent

import android.app.Application
import kotlin.concurrent.thread

class NezhaAgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Keystore and migration run on IO. The UI awaits readiness before using cached values.
        thread(name = "nezha-config-init", isDaemon = true) {
            container.initializeConfigurationStorage()
        }
    }
}
