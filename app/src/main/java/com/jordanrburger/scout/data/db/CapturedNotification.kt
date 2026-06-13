package com.jordanrburger.scout.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "captured_notifications",
    indices = [Index("forwardedAt")],
)
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val pkg: String,
    val postTimeMs: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val category: String?,
    val isOngoing: Boolean,
    val removed: Boolean,
    val capturedAt: String,
    val forwardedAt: Long? = null,
)

@Dao
interface CapturedNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: CapturedNotification): Long

    @Query("SELECT * FROM captured_notifications WHERE forwardedAt IS NULL ORDER BY postTimeMs ASC LIMIT :limit")
    suspend fun unforwarded(limit: Int): List<CapturedNotification>

    @Query("UPDATE captured_notifications SET forwardedAt = :timestamp WHERE id IN (:ids)")
    suspend fun markForwarded(ids: List<Long>, timestamp: Long)

    @Query("SELECT * FROM captured_notifications ORDER BY postTimeMs DESC LIMIT 500")
    fun recent(): Flow<List<CapturedNotification>>

    @Query("DELETE FROM captured_notifications WHERE forwardedAt IS NOT NULL AND postTimeMs < :olderThanMs")
    suspend fun pruneForwarded(olderThanMs: Long)

    @Query("DELETE FROM captured_notifications WHERE forwardedAt IS NULL")
    suspend fun deleteAllUnforwarded()

    @Query("DELETE FROM captured_notifications WHERE forwardedAt IS NULL AND pkg NOT IN (:allowed)")
    suspend fun deleteUnforwardedNotIn(allowed: Set<String>)
}
