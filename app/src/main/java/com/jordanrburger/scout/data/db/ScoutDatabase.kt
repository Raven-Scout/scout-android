package com.jordanrburger.scout.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CapturedNotification::class, PendingAction::class],
    version = 1,
    exportSchema = false,
)
abstract class ScoutDatabase : RoomDatabase() {
    abstract fun capturedNotificationDao(): CapturedNotificationDao
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        @Volatile private var instance: ScoutDatabase? = null

        fun get(context: Context): ScoutDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScoutDatabase::class.java,
                    "scout.db",
                ).build().also { instance = it }
            }
    }
}
