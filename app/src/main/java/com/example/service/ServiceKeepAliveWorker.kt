package com.example.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Foreground service ölse bile servisin yeniden başlatılmasını garanti eden
 * WorkManager yedek işçisi. WorkManager, OEM batarya optimizasyonlarına
 * (Xiaomi MIUI, Samsung, Huawei vb.) karşı Foreground Service'den daha
 * dayanıklıdır.
 */
class ServiceKeepAliveWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            // restricted_apps tablosundaki aktif kayıt varlığını kontrol et
            // Eski session.isActive && session.targetAppPackage kontrolü kaldırıldı
            val hasActiveRestrictions = withContext(Dispatchers.IO) {
                repository.getActiveRestrictedAppsSync().isNotEmpty()
            }

            if (hasActiveRestrictions) {
                if (!BlockOverlayService.isServiceRunning.get()) {
                    BlockOverlayService.start(applicationContext)
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
