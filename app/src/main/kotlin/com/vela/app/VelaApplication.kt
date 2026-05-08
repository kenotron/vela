package com.vela.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.server.VelaMiniAppCleaner
import com.vela.app.server.VelaMiniAppServer
import com.vela.app.ssh.MdnsDiscoveryService
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

    @Inject
    lateinit var mdnsDiscovery: MdnsDiscoveryService

    override fun onCreate() {
        super.onCreate()
        ApprovalNotificationHelper.createChannel(this)
        ApprovalNotificationHelper.createSessionChannel(this)
        // SessionStreamingService is started from MainActivity.onCreate() to avoid
        // ForegroundServiceStartNotAllowedException on Android 12+ (app is background
        // state during Application.onCreate() on cold start).
        profileWorkerScheduler.schedule()
        miniAppCleaner.clearStaleRenderersIfNeeded()
        miniAppServer.start()
        // Start mDNS discovery when app enters foreground; stop when it fully backgrounds.
        // ProcessLifecycleOwner fires ON_START/ON_STOP only for true foreground transitions
        // (not screen rotations or brief pauses) so this is battery-safe.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { mdnsDiscovery.start() }
                override fun onStop(owner: LifecycleOwner)  { mdnsDiscovery.stop() }
            }
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
