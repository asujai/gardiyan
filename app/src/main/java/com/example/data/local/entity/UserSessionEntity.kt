package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_sessions")
data class UserSessionEntity(
    @PrimaryKey val id: Int = 1, // Single user row
    val username: String = "GuardUser",
    val level: Int = 1, // Level 1 (Çaylak), Level 2 (Disiplinli), Level 3 (Usta)
    val hasRedBadge: Boolean = false, // True if failed and has Kırmızı Utanç Rozeti
    
    // Active Target Configurations
    val isActive: Boolean = false,
    val targetAppPackage: String = "", // e.g. "com.instagram.android"
    val targetAppName: String = "", // e.g. "Instagram"
    val dailyLimitMinutes: Int = 60,
    val remainingMinutesToday: Int = 60,
    val remainingSecondsToday: Int = 3600, // Gerçek zamanlı saniye sayacı (dakika * 60)
    val isObserverMode: Boolean = false, // Guard (False) vs Observer (True)
    val shameMessage: String = "",
    val shameImagePath: String = "",
    
    // Companion Info (Observer)
    val observerInviteLink: String = "",
    val isObserverConfirmed: Boolean = false,
    val observerContactName: String = "",
    
    // Redemption (Telafi) Task Status
    val activeRedemptionDaysLeft: Int = 0, // Days left to clean badge
    val redemptionStreakGoal: Int = 2, // Target consecutive days without fail
    val consecutiveSuccessDays: Int = 0, // Saved streaks for Level transitions
    
    val lastSuccessDateMillis: Long = 0L,
    val lastCheckedMillis: Long = System.currentTimeMillis()
)
