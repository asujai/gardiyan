package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "restricted_apps",
    indices = [Index(value = ["packageName"], unique = true)]
)
data class RestrictedAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int,
    val remainingMinutesToday: Int,
    val remainingSecondsToday: Int,
    val isActive: Boolean = true,
    val isFailed: Boolean = false,
    val activeDays: String = "Pzt,Sal,Çar,Per,Cum,Cmt,Paz",
    val lastResetDate: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
)
