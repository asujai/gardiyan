package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val db = GuardianDatabase.getDatabase(context.applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            // Eski session.isActive kontrolü yerine restricted_apps tablosundaki
            // aktif kayıt varlığını kontrol et
            val hasActiveRestrictions = runBlocking {
                repository.getActiveRestrictedAppsSync().isNotEmpty()
            }

            if (hasActiveRestrictions) {
                val serviceIntent = Intent(context, BlockOverlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
