package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.FriendEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.data.local.entity.AppRestrictionEntity
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

    // App Restrictions queries
    @Query("SELECT * FROM app_restrictions ORDER BY appName ASC")
    fun getAllAppRestrictions(): Flow<List<AppRestrictionEntity>>

    @Query("SELECT * FROM app_restrictions WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppRestrictionSync(packageName: String): AppRestrictionEntity?
    
    @Query("SELECT * FROM app_restrictions")
    suspend fun getAllAppRestrictionsSync(): List<AppRestrictionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppRestriction(restriction: AppRestrictionEntity)

    @Update
    suspend fun updateAppRestriction(restriction: AppRestrictionEntity)

    @Delete
    suspend fun deleteAppRestriction(restriction: AppRestrictionEntity)

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
}
