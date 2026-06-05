package com.example.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.GuardianDao
import com.example.data.local.entity.FriendEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.data.local.entity.AppRestrictionEntity

@Database(
    entities = [
        UserSessionEntity::class,
        FriendEntity::class,
        StatusLogEntity::class,
        AppRestrictionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun guardianDao(): GuardianDao

    companion object {
        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        fun getDatabase(context: Context): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GuardianDatabase::class.java,
                    "guardian_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
