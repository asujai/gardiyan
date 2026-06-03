package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "status_logs")
data class StatusLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val eventType: String, // "FAILURE", "SUCCESS", "REDEEMED", "LEVEL_UP", "TARGET_STARTED"
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String = "",
    val details: String = ""
)
