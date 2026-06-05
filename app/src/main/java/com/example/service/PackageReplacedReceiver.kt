package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Uygulama güncellendiğinde (yeni APK yüklendiğinde) otomatik olarak açılmasını sağlar.
 * Android hem MY_PACKAGE_REPLACED hem de eski cihazlarda PACKAGE_REPLACED yayını yapar.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_REPLACED) {

            val replacedPackage = if (action == Intent.ACTION_PACKAGE_REPLACED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ?: context.packageName
                } else {
                    @Suppress("DEPRECATION")
                    intent.data?.schemeSpecificPart ?: context.packageName
                }
            } else {
                context.packageName
            }

            if (replacedPackage == context.packageName) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    context.startActivity(launchIntent)
                }
            }
        }
    }
}
