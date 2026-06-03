package com.example.data.repository

import com.example.data.local.dao.GuardianDao
import com.example.data.local.entity.UserSessionEntity
import com.example.data.local.entity.StatusLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GuardianRepository(private val guardianDao: GuardianDao) {

    val userSession: Flow<UserSessionEntity?> = guardianDao.getUserSession()
    val allLogs: Flow<List<StatusLogEntity>> = guardianDao.getAllLogs()

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

    /**
     * Triggered when the user fails a target (either target timer expires while running, 
     * or they execute the 60 seconds "Pes Et" sequence).
     */
    suspend fun failActiveTarget() {
        val session = getSessionSync() ?: return
        
        // Immediate Heavy Status Sentence:
        // Down with the user to Level 1 (Çaylak)
        // Add Kırmızı Utanç Rozeti (hasRedBadge = true)
        // Set redemption goal: 2 perfect target cycles to clean the badge
        val updatedSession = session.copy(
            isActive = false,
            level = 1,
            hasRedBadge = true,
            activeRedemptionDaysLeft = 2,
            redemptionStreakGoal = 2,
            consecutiveSuccessDays = 0,
            remainingMinutesToday = session.dailyLimitMinutes
        )
        
        guardianDao.insertUserSession(updatedSession)
        
        // Record Failure status
        insertLog(
            eventType = "FAILURE",
            appName = session.targetAppName,
            details = "Kilit kırıldı. Level 1 rütbesine düşürüldünüz! Kırmızı Utanç Rozeti profilinize işlendi."
        )
    }

    /**
     * Triggered when the user successfully completes a target day
     */
    suspend fun succeedActiveTarget() {
        val session = getSessionSync() ?: return
        if (!session.isActive) return

        val nextStreak = session.consecutiveSuccessDays + 1
        var nextLevel = session.level
        var nextRedBadge = session.hasRedBadge
        var nextRedemptionDays = session.activeRedemptionDaysLeft
        var logDetails = "Günlük hedef başarıyla tamamlandı!"

        // 1. Redemption check: if they have red badge and are doing redemption task
        if (nextRedBadge && nextRedemptionDays > 0) {
            nextRedemptionDays -= 1
            if (nextRedemptionDays <= 0) {
                nextRedBadge = false
                logDetails += " Başarılı telafi serisi sonucu Kırmızı Rozetiniz sihirli şekilde silindi!"
            } else {
                logDetails += " Kırmızı rozeti silmek için $nextRedemptionDays başarılı gün hedeflenmelidir."
            }
        }

        // 2. Level Progression check based on active streak
        // Lvl 1 -> Lvl 2: Needs 3 consecutive successful days
        // Lvl 2 -> Lvl 3: Needs 7 consecutive successful days (Haftalık hedefler)
        if (session.level == 1 && nextStreak >= 3) {
            nextLevel = 2
            logDetails += " Tebrikler! 3 günlük seriyi tamamlayarak 'Disiplinli' (Level 2) rütbenize ulaştınız!"
        } else if (session.level == 2 && nextStreak >= 7) {
            nextLevel = 3
            logDetails += " Muhteşem! 7 günlük istikrarlı gidişle 'Usta' (Level 3) rütbesine yükseldiniz!"
        }

        val updatedSession = session.copy(
            isActive = false, // completed for today
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
