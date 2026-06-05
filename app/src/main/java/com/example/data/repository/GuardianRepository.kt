package com.example.data.repository

import com.example.data.local.dao.GuardianDao
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class GuardianRepository(private val guardianDao: GuardianDao) {

    companion object {
        const val ALL_DAYS = "Pzt,Sal,Çar,Per,Cum,Cmt,Paz"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun todayKey(): String = dateFormat.format(Date())

        fun todayDayLabel(): String {
            return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "Pzt"
                Calendar.TUESDAY -> "Sal"
                Calendar.WEDNESDAY -> "Çar"
                Calendar.THURSDAY -> "Per"
                Calendar.FRIDAY -> "Cum"
                Calendar.SATURDAY -> "Cmt"
                Calendar.SUNDAY -> "Paz"
                else -> ""
            }
        }
    }

    val userSession: Flow<UserSessionEntity?> = guardianDao.getUserSession()
    val allLogs: Flow<List<StatusLogEntity>> = guardianDao.getAllLogs()
    val restrictedApps: Flow<List<RestrictedAppEntity>> = guardianDao.getAllRestrictedApps()

    suspend fun getSessionSync(): UserSessionEntity? = guardianDao.getUserSessionSync()

    suspend fun insertDefaultSessionIfMissing() {
        val current = guardianDao.getUserSessionSync()
        if (current == null) {
            guardianDao.insertUserSession(
                UserSessionEntity(
                    id = 1,
                    username = "GardiyanUser",
                    level = 1,
                    hasRedBadge = false,
                    isActive = false
                )
            )
        }
    }

    suspend fun saveSession(session: UserSessionEntity) {
        guardianDao.insertUserSession(session)
    }

    suspend fun insertLog(eventType: String, appName: String, details: String) {
        guardianDao.insertLog(
            StatusLogEntity(
                eventType = eventType,
                appName = appName,
                details = details,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearLogs() {
        guardianDao.clearLogs()
    }

    suspend fun getActiveRestrictedAppsSync(): List<RestrictedAppEntity> {
        resetDailyCountersIfNeeded()
        return guardianDao.getActiveRestrictedAppsSync()
    }

    suspend fun getActiveRestrictedAppsForTodaySync(): List<RestrictedAppEntity> {
        val today = todayDayLabel()
        return getActiveRestrictedAppsSync().filter { app ->
            val days = app.activeDays.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            days.isEmpty() || days.contains(today)
        }
    }

    suspend fun getAllRestrictedAppsSync(): List<RestrictedAppEntity> {
        resetDailyCountersIfNeeded()
        return guardianDao.getAllRestrictedAppsSync()
    }

    suspend fun getRestrictedAppByPackageSync(pkg: String): RestrictedAppEntity? =
        guardianDao.getRestrictedAppByPackageSync(pkg)

    suspend fun getRestrictedAppByIdSync(id: Long): RestrictedAppEntity? =
        guardianDao.getRestrictedAppByIdSync(id)

    suspend fun upsertRestrictedApp(
        packageName: String,
        appName: String,
        dailyLimitMinutes: Int,
        activeDays: String = ALL_DAYS
    ): Long {
        val today = todayKey()
        val existing = guardianDao.getRestrictedAppByPackageSync(packageName)
        return if (existing != null) {
            guardianDao.updateRestrictedApp(
                existing.copy(
                    appName = appName,
                    dailyLimitMinutes = dailyLimitMinutes,
                    remainingMinutesToday = dailyLimitMinutes,
                    remainingSecondsToday = dailyLimitMinutes * 60,
                    isActive = true,
                    isFailed = false,
                    activeDays = activeDays,
                    lastResetDate = today
                )
            )
            existing.id
        } else {
            guardianDao.insertRestrictedApp(
                RestrictedAppEntity(
                    packageName = packageName,
                    appName = appName,
                    dailyLimitMinutes = dailyLimitMinutes,
                    remainingMinutesToday = dailyLimitMinutes,
                    remainingSecondsToday = dailyLimitMinutes * 60,
                    isActive = true,
                    isFailed = false,
                    activeDays = activeDays,
                    lastResetDate = today
                )
            )
        }
    }

    suspend fun insertQuickTestApp(
        packageName: String,
        appName: String,
        testSeconds: Int,
        activeDays: String = ALL_DAYS
    ): Long {
        val today = todayKey()
        val existing = guardianDao.getRestrictedAppByPackageSync(packageName)
        val dailyLimitMinutes = (testSeconds + 59) / 60
        return if (existing != null) {
            guardianDao.updateRestrictedApp(
                existing.copy(
                    appName = appName,
                    dailyLimitMinutes = dailyLimitMinutes,
                    remainingMinutesToday = testSeconds / 60,
                    remainingSecondsToday = testSeconds,
                    isActive = true,
                    isFailed = false,
                    activeDays = activeDays,
                    lastResetDate = today
                )
            )
            existing.id
        } else {
            guardianDao.insertRestrictedApp(
                RestrictedAppEntity(
                    packageName = packageName,
                    appName = appName,
                    dailyLimitMinutes = dailyLimitMinutes,
                    remainingMinutesToday = testSeconds / 60,
                    remainingSecondsToday = testSeconds,
                    isActive = true,
                    isFailed = false,
                    activeDays = activeDays,
                    lastResetDate = today
                )
            )
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
        guardianDao.resetRestrictedApp(id, todayKey())
    }

    suspend fun updateRestrictedApp(app: RestrictedAppEntity) {
        guardianDao.updateRestrictedApp(app)
    }

    suspend fun resetDailyCountersIfNeeded() {
        val today = todayKey()
        guardianDao.getAllRestrictedAppsSync()
            .filter { it.lastResetDate != today }
            .forEach { app ->
                guardianDao.updateRestrictedApp(
                    app.copy(
                        remainingMinutesToday = app.dailyLimitMinutes,
                        remainingSecondsToday = app.dailyLimitMinutes * 60,
                        isFailed = false,
                        lastResetDate = today
                    )
                )
            }
    }

    suspend fun cancelAllActiveTargets() {
        val session = getSessionSync() ?: return
        val activeApps = guardianDao.getActiveRestrictedAppsSync()
        if (activeApps.isEmpty()) return

        guardianDao.deactivateAllRestrictedApps()

        guardianDao.insertUserSession(
            session.copy(
                isActive = false,
                level = 1,
                hasRedBadge = true,
                activeRedemptionDaysLeft = 2,
                redemptionStreakGoal = 2,
                consecutiveSuccessDays = 0
            )
        )

        val appNames = activeApps.joinToString(", ") { it.appName }
        insertLog(
            eventType = "RESET_HOLD_5S",
            appName = appNames,
            details = "5 saniye basılı tutma ile tüm kilitler kaldırıldı. Level 1'e düşürüldünüz ve Kırmızı Utanç Rozeti eklendi."
        )
    }

    suspend fun failRestrictedApp(appId: Long) {
        val app = guardianDao.getRestrictedAppByIdSync(appId) ?: return
        if (app.isFailed) return

        guardianDao.markRestrictedAppFailed(appId)

        val session = getSessionSync()
        if (session != null) {
            guardianDao.insertUserSession(
                session.copy(
                    level = 1,
                    hasRedBadge = true,
                    activeRedemptionDaysLeft = 2,
                    redemptionStreakGoal = 2,
                    consecutiveSuccessDays = 0
                )
            )
        }

        insertLog(
            eventType = "FAILURE",
            appName = app.appName,
            details = "${app.appName} hedefinde süre doldu. Level 1'e düşürüldünüz. Kilit devam ediyor."
        )
    }

    suspend fun failActiveTarget() {
        val activeApps = guardianDao.getActiveRestrictedAppsSync()
        activeApps.forEach { app ->
            if (!app.isFailed) {
                failRestrictedApp(app.id)
            }
        }
    }

    suspend fun succeedActiveTarget() {
        val session = getSessionSync() ?: return

        val nextStreak = session.consecutiveSuccessDays + 1
        var nextLevel = session.level
        var nextRedBadge = session.hasRedBadge
        var nextRedemptionDays = session.activeRedemptionDaysLeft
        var logDetails = "Günlük hedef başarıyla tamamlandı."

        if (nextRedBadge && nextRedemptionDays > 0) {
            nextRedemptionDays -= 1
            if (nextRedemptionDays <= 0) {
                nextRedBadge = false
                logDetails += " Başarılı telafi serisi sonucu kırmızı rozetiniz silindi."
            } else {
                logDetails += " Kırmızı rozeti silmek için $nextRedemptionDays başarılı gün kaldı."
            }
        }

        if (session.level == 1 && nextStreak >= 3) {
            nextLevel = 2
            logDetails += " 3 günlük seriyle Disiplinli rütbesine ulaştınız."
        } else if (session.level == 2 && nextStreak >= 7) {
            nextLevel = 3
            logDetails += " 7 günlük seriyle Usta rütbesine yükseldiniz."
        }

        guardianDao.insertUserSession(
            session.copy(
                isActive = false,
                level = nextLevel,
                hasRedBadge = nextRedBadge,
                activeRedemptionDaysLeft = nextRedemptionDays,
                consecutiveSuccessDays = nextStreak,
                remainingMinutesToday = session.dailyLimitMinutes
            )
        )
        insertLog(
            eventType = "SUCCESS",
            appName = session.targetAppName,
            details = logDetails
        )
    }
}
