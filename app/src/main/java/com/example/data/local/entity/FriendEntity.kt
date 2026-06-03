package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pre-configured Relational Schema for V2 Social Syncing.
 * Stores friends synchronizable from Firebase/Supabase.
 * Not active in the MVP UI, but structured in relations so that 
 * branch extension for V2 requires zero DB restructuring.
 */
@Entity(tableName = "friends_list")
data class FriendEntity(
    @PrimaryKey val friendUserId: String,
    val fullName: String,
    val currentLevel: Int = 1, // Friend's level: 1 (Çaylak), 2 (Disiplinli), 3 (Usta)
    val hasRedBadge: Boolean = false, // Friend failed on their target
    val activeTargetApp: String = "",
    val activeLimitMinutes: Int = 0,
    val lastSyncTime: Long = System.currentTimeMillis()
)
