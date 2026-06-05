package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.FriendEntity
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianDao {

    // User Session queries
    @Query("SELECT * FROM user_sessions WHERE id = 1 LIMIT 1")
    fun getUserSession(): Flow<UserSessionEntity?>

    @Query("SELECT * FROM user_sessions WHERE id = 1 LIMIT 1")
    suspend fun getUserSessionSync(): UserSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserSession(session: UserSessionEntity)

    @Update
    suspend fun updateUserSession(session: UserSessionEntity)

    // Log queries
    @Query("SELECT * FROM status_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<StatusLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: StatusLogEntity)

    @Query("DELETE FROM status_logs")
    suspend fun clearLogs()

    // Friends relational capability (V2 prepared)
    @Query("SELECT * FROM friends_list ORDER BY fullName ASC")
    fun getAllFriends(): Flow<List<FriendEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriends(friends: List<FriendEntity>)

    // Restricted Apps (Multi-app support)
    @Query("SELECT * FROM restricted_apps ORDER BY appName ASC")
    fun getAllRestrictedApps(): Flow<List<RestrictedAppEntity>>

    @Query("SELECT * FROM restricted_apps ORDER BY appName ASC")
    suspend fun getAllRestrictedAppsSync(): List<RestrictedAppEntity>

    @Query("SELECT * FROM restricted_apps WHERE isActive = 1")
    suspend fun getActiveRestrictedAppsSync(): List<RestrictedAppEntity>

    @Query("SELECT * FROM restricted_apps WHERE packageName = :pkg LIMIT 1")
    suspend fun getRestrictedAppByPackageSync(pkg: String): RestrictedAppEntity?

    @Query("SELECT * FROM restricted_apps WHERE id = :id LIMIT 1")
    suspend fun getRestrictedAppByIdSync(id: Long): RestrictedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRestrictedApp(app: RestrictedAppEntity): Long

    @Update
    suspend fun updateRestrictedApp(app: RestrictedAppEntity)

    @Query("DELETE FROM restricted_apps WHERE id = :id")
    suspend fun deleteRestrictedAppById(id: Long)

    @Query("DELETE FROM restricted_apps")
    suspend fun clearAllRestrictedApps()

    @Query("UPDATE restricted_apps SET isFailed = 1 WHERE id = :id")
    suspend fun markRestrictedAppFailed(id: Long)

    @Query("UPDATE restricted_apps SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAllRestrictedApps()

    @Query("UPDATE restricted_apps SET isActive = 1, isFailed = 0, remainingSecondsToday = dailyLimitMinutes * 60, remainingMinutesToday = dailyLimitMinutes, lastResetDate = :resetDate WHERE id = :id")
    suspend fun resetRestrictedApp(id: Long, resetDate: String)
}
