package com.nezhahq.agent

import android.app.Application
import kotlin.concurrent.thread

class NezhaAgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Off the main thread: opening storage is what migrates the legacy encrypted store, and
        // building its master key is an Android Keystore operation that takes seconds on some
        // devices — long enough to make cold start an ANR.
        //
        // Moving the work here is not by itself enough, because initialization and reads share one
        // lock inside ConfigurationStorageCoordinator: a main-thread read started while this runs
        // waits out the entire migration and reproduces the ANR from the other side. Whoever needs
        // the result therefore awaits container.configurationReadiness instead of reading through.
        //
        // The root-mode grant applied alongside it needs no waiter: privileged access stays denied
        // until the grant lands, so a collector that samples during the gap reports unprivileged
        // values for one cycle instead of acting on a grant that was never confirmed.
        thread(name = "nezha-config-init", isDaemon = true) {
            container.initializeConfigurationStorage()
        }
    }
}
