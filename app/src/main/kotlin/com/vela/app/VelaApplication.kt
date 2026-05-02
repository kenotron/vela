package com.vela.app

import android.app.Application
import android.content.Intent
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.streaming.SessionStreamingService
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vela.app.server.VelaMiniAppCleaner
import com.vela.app.server.VelaMiniAppServer
import com.vela.app.workers.ProfileWorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VelaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var profileWorkerScheduler: ProfileWorkerScheduler

    @Inject
    lateinit var miniAppServer: VelaMiniAppServer

    @Inject
    lateinit var miniAppCleaner: VelaMiniAppCleaner

    override fun onCreate() {
        super.onCreate()
        ApprovalNotificationHelper.createChannel(this)
        ApprovalNotificationHelper.createSessionChannel(this)
        startService(Intent(this, SessionStreamingService::class.java))
        profileWorkerScheduler.schedule()
        miniAppCleaner.clearStaleRenderersIfNeeded()
        miniAppServer.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
