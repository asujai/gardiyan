package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object KeepAliveScheduler {

    private const val WORK_NAME = "gardiyan_service_keep_alive"

    /**
     * 15 dakikada bir servisin çalışıp çalışmadığını kontrol eden
     * periyodik iş planla. WorkManager OEM batarya optimizasyonlarından
     * (Doze mode) etkilenmeden çalışır.
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ServiceKeepAliveWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
