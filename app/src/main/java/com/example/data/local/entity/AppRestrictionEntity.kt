package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_restrictions")
data class AppRestrictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int = 60,
    val remainingMinutesToday: Int = 60,
    val remainingSecondsToday: Int = 3600,
    val isActive: Boolean = true
)
