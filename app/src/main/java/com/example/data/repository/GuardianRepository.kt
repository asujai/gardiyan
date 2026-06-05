package com.example.data.repository

import com.example.data.local.dao.GuardianDao
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GuardianRepository(private val guardianDao: GuardianDao) {

    val userSession: Flow<UserSessionEntity?> = guardianDao.getUserSession()
    val allLogs: Flow<List<StatusLogEntity>> = guardianDao.getAllLogs()
    val restrictedApps: Flow<List<RestrictedAppEntity>> = guardianDao.getAllRestrictedApps()

    suspend fun getSessionSync(): UserSessionEntity? = guardianDao.getUserSessionSync()

    suspend fun insertDefaultSessionIfMissing() {
        val current = guardianDao.getUserSessionSync()
        if (current == null) {
            val defaultSession = UserSessionEntity(
                id = 1,
                username = "GardiyanUser",
                level = 1,
                hasRedBadge = false,
                isActive = false
            )
            guardianDao.insertUserSession(defaultSession)
        }
    }

    suspend fun saveSession(session: UserSessionEntity) {
        guardianDao.insertUserSession(session)
    }

    suspend fun insertLog(eventType: String, appName: String, details: String) {
        val log = StatusLogEntity(
            eventType = eventType,
            appName = appName,
            details = details,
            timestamp = System.currentTimeMillis()
        )
        guardianDao.insertLog(log)
    }

    suspend fun clearLogs() {
        guardianDao.clearLogs()
    }

    // ============================
    // Restricted Apps Operations
    // ============================

    suspend fun getActiveRestrictedAppsSync(): List<RestrictedAppEntity> {
        return guardianDao.getActiveRestrictedAppsSync()
    }

    suspend fun getAllRestrictedAppsSync(): List<RestrictedAppEntity> {
        return guardianDao.getAllRestrictedAppsSync()
    }

    suspend fun getRestrictedAppByPackageSync(pkg: String): RestrictedAppEntity? {
        return guardianDao.getRestrictedAppByPackageSync(pkg)
    }

    suspend fun getRestrictedAppByIdSync(id: Long): RestrictedAppEntity? {
        return guardianDao.getRestrictedAppByIdSync(id)
    }

    /**
     * Bir uygulamayı kısıtlama listesine ekler. Aynı paket zaten varsa limit
     * güncellenir. Yeni satırsa yeni daily-limit ile birlikte eklenir.
     * isActive HER ZAMAN true — kolay kapatma yok.
     */
    suspend fun upsertRestrictedApp(
        packageName: String,
        appName: String,
        dailyLimitMinutes: Int,
        activeDays: String = "Pzt,Sal,Çar,Per,Cum,Cmt,Paz"
    ): Long {
        val existing = guardianDao.getRestrictedAppByPackageSync(packageName)
        return if (existing != null) {
            val updated = existing.copy(
                appName = appName,
                dailyLimitMinutes = dailyLimitMinutes,
                remainingMinutesToday = dailyLimitMinutes,
                remainingSecondsToday = dailyLimitMinutes * 60,
                isActive = true,
                isFailed = false,
                activeDays = activeDays
            )
            guardianDao.updateRestrictedApp(updated)
            existing.id
        } else {
            val newApp = RestrictedAppEntity(
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = dailyLimitMinutes,
                remainingMinutesToday = dailyLimitMinutes,
                remainingSecondsToday = dailyLimitMinutes * 60,
                isActive = true,
                isFailed = false,
                activeDays = activeDays
            )
            guardianDao.insertRestrictedApp(newApp)
        }
    }

    /**
     * Hızlı test için: dakika limiti yerine saniye cinsinden kalan süre ile
     * kısıtlama ekler. Örn. testSeconds=10 → uygulama 10 saniye sonra kilitlenir.
     */
    suspend fun insertQuickTestApp(
        packageName: String,
        appName: String,
        testSeconds: Int,
        activeDays: String = "Pzt,Sal,Çar,Per,Cum,Cmt,Paz"
    ): Long {
        val existing = guardianDao.getRestrictedAppByPackageSync(packageName)
        return if (existing != null) {
            val updated = existing.copy(
                appName = appName,
                dailyLimitMinutes = (testSeconds + 59) / 60,
                remainingMinutesToday = testSeconds / 60,
                remainingSecondsToday = testSeconds,
                isActive = true,
                isFailed = false,
                activeDays = activeDays
            )
            guardianDao.updateRestrictedApp(updated)
            existing.id
        } else {
            val newApp = RestrictedAppEntity(
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = (testSeconds + 59) / 60,
                remainingMinutesToday = testSeconds / 60,
                remainingSecondsToday = testSeconds,
                isActive = true,
                isFailed = false,
                activeDays = activeDays
            )
            guardianDao.insertRestrictedApp(newApp)
        }
    }

    suspend fun removeRestrictedApp(id: Long) {
        guardianDao.deleteRestrictedAppById(id)
    }

    suspend fun clearAllRestrictedApps() {
        guardianDao.clearAllRestrictedApps()
    }

    suspend fun markRestrictedAppFailed(id: Long) {
        guardianDao.markRestrictedAppFailed(id)
    }

    suspend fun deactivateAllRestrictedApps() {
        guardianDao.deactivateAllRestrictedApps()
    }

    suspend fun resetRestrictedApp(id: Long) {
        guardianDao.resetRestrictedApp(id)
    }

    suspend fun updateRestrictedApp(app: RestrictedAppEntity) {
        guardianDao.updateRestrictedApp(app)
    }

    /**
     * 5sn basılı tutma sonrası çağrılır. YALNIZCA bu fonksiyon tüm kısıtlamaları
     * deaktive edebilir. Bu, kullanıcının bilinçli irade beyanıdır.
     *
     * Kırmızı rozet + level düşüşü ceza olarak uygulanır.
     */
    suspend fun cancelAllActiveTargets() {
        val session = getSessionSync() ?: return
        val activeApps = guardianDao.getActiveRestrictedAppsSync()
        if (activeApps.isEmpty()) return

        // Tüm aktif hedefleri deaktive et — SADECE 5sn hold ile
        guardianDao.deactivateAllRestrictedApps()

        // Oturumu güncelle: level 1, kırmızı rozet
        // NOT: session.isActive burada false yapılır çünkü bu bilinçli iptaldir
        val updatedSession = session.copy(
            isActive = false,
            level = 1,
            hasRedBadge = true,
            activeRedemptionDaysLeft = 2,
            redemptionStreakGoal = 2,
            consecutiveSuccessDays = 0
        )
        guardianDao.insertUserSession(updatedSession)

        val appNames = activeApps.joinToString(", ") { it.appName }
        insertLog(
            eventType = "RESET_HOLD_5S",
            appName = appNames,
            details = "5 saniye basılı tutma ile tüm kilitler kaldırıldı. Level 1'e düşürüldünüz ve Kırmızı Utanç Rozeti eklendi."
        )
    }

    /**
     * Tek bir uygulamanın süresi dolduğunda çağrılır.
     *
     * KRİTİK DEĞİŞİKLİK: isActive = false YAPILMAZ.
     * Uygulama isFailed olarak işaretlenir ama aktif kalır.
     * Session isActive da DEĞİŞTİRİLMEZ — kilit devam eder.
     */
    suspend fun failRestrictedApp(appId: Long) {
        val app = guardianDao.getRestrictedAppByIdSync(appId) ?: return
        guardianDao.markRestrictedAppFailed(appId)

        val session = getSessionSync() ?: return
        // Session güncelle: level ve rozet ceza uygulanır
        // AMA isActive DEĞİŞTİRİLMEZ — kilit aktif kalır
        val updatedSession = session.copy(
            level = 1,
            hasRedBadge = true,
            activeRedemptionDaysLeft = 2,
            redemptionStreakGoal = 2,
            consecutiveSuccessDays = 0
        )
        guardianDao.insertUserSession(updatedSession)

        insertLog(
            eventType = "FAILURE",
            appName = app.appName,
            details = "${app.appName} hedefinde süre doldu. Level 1'e düşürüldünüz! Kilit devam ediyor."
        )
    }

    /**
     * Eski API uyumluluğu için.
     *
     * KRİTİK DEĞİŞİKLİK: isActive = false YAPILMAZ.
     * Uygulamalar isFailed olarak işaretlenir ama kilit devam eder.
     */
    suspend fun failActiveTarget() {
        val activeApps = guardianDao.getActiveRestrictedAppsSync()
        if (activeApps.isNotEmpty()) {
            for (app in activeApps) {
                guardianDao.markRestrictedAppFailed(app.id)
            }
        }
        val session = getSessionSync() ?: return
        // Session güncelle: level/rozet ceza uygulanır
        // AMA isActive DEĞİŞTİRİLMEZ — kilit aktif kalır
        val updatedSession = session.copy(
            level = 1,
            hasRedBadge = true,
            activeRedemptionDaysLeft = 2,
            redemptionStreakGoal = 2,
            consecutiveSuccessDays = 0,
            remainingMinutesToday = session.dailyLimitMinutes
        )
        guardianDao.insertUserSession(updatedSession)
        insertLog(
            eventType = "FAILURE",
            appName = session.targetAppName,
            details = "Kilit kırıldı. Level 1 rütbesine düşürüldünüz! Kırmızı Utanç Rozeti profilinize işlendi. Kilit devam ediyor."
        )
    }

    /**
     * Triggered when the user successfully completes a target day
     */
    suspend fun succeedActiveTarget() {
        val session = getSessionSync() ?: return

        val nextStreak = session.consecutiveSuccessDays + 1
        var nextLevel = session.level
        var nextRedBadge = session.hasRedBadge
        var nextRedemptionDays = session.activeRedemptionDaysLeft
        var logDetails = "Günlük hedef başarıyla tamamlandı!"

        if (nextRedBadge && nextRedemptionDays > 0) {
            nextRedemptionDays -= 1
            if (nextRedemptionDays <= 0) {
                nextRedBadge = false
                logDetails += " Başarılı telafi serisi sonucu Kırmızı Rozetiniz sihirli şekilde silindi!"
            } else {
                logDetails += " Kırmızı rozeti silmek için $nextRedemptionDays başarılı gün hedeflenmelidir."
            }
        }

        if (session.level == 1 && nextStreak >= 3) {
            nextLevel = 2
            logDetails += " Tebrikler! 3 günlük seriyi tamamlayarak 'Disiplinli' (Level 2) rütbenize ulaştınız!"
        } else if (session.level == 2 && nextStreak >= 7) {
            nextLevel = 3
            logDetails += " Muhteşem! 7 günlük istikrarlı gidişle 'Usta' (Level 3) rütbesine yükseldiniz!"
        }

        val updatedSession = session.copy(
            isActive = false,
            level = nextLevel,
            hasRedBadge = nextRedBadge,
            activeRedemptionDaysLeft = nextRedemptionDays,
            consecutiveSuccessDays = nextStreak,
            remainingMinutesToday = session.dailyLimitMinutes
        )

        guardianDao.insertUserSession(updatedSession)
        insertLog(
            eventType = "SUCCESS",
            appName = session.targetAppName,
            details = logDetails
        )
    }
}
