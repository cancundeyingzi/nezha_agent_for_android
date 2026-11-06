package com.nezhahq.agent

import android.app.Application
import kotlin.concurrent.thread

open class NezhaAgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        // Keystore and migration run on IO. The UI awaits readiness before using cached values.
        thread(name = "nezha-config-init", isDaemon = true) {
            container.initializeConfigurationStorage()
        }
    }

    /** One immutable graph per process, assembled before any Android entry point can use it. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
