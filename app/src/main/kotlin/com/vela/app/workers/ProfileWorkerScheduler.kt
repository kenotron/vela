package com.vela.app.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the periodic [ProfileWorker] job and provides an on-demand trigger
 * for the profile refresh FAB in [ProfileScreen].
 *
 * Call [schedule] once on app startup. Call [triggerRefresh] when the user taps
 * the refresh FAB.
 */
@Singleton
class ProfileWorkerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Registers a daily [PeriodicWorkRequest] that fires when the device is idle
     * and battery is not low. Uses [ExistingPeriodicWorkPolicy.KEEP] so repeated
     * calls on app start do not reset the existing schedule window.
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val request = PeriodicWorkRequestBuilder<ProfileWorker>(
            repeatInterval = Duration.ofHours(24),
            flexTimeInterval = Duration.ofHours(4),
        )
            .setConstraints(constraints)
            .addTag(ProfileWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ProfileWorker.TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Immediately enqueues a one-time [ProfileWorker] run. Replaces any in-progress
     * manual run — matching the FAB's "force refresh now" semantics.
     */
    fun triggerRefresh() {
        val request = OneTimeWorkRequestBuilder<ProfileWorker>()
            .addTag(ProfileWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            "${ProfileWorker.TAG}_manual",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
