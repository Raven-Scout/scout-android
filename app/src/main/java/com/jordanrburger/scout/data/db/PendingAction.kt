package com.jordanrburger.scout.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** Offline write queue (spec §5): mark-done / snooze taken while the bridge is
 *  unreachable, drained by ForwardWorker before each fetch. */
@Entity(tableName = "pending_actions")
data class PendingAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prefix: String,
    val action: String, // "done" | "snooze"
    val payload: String? = null, // snooze: until date ISO
    val createdAt: Long,
    val attempts: Int = 0,
)

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(action: PendingAction): Long

    @Query("SELECT * FROM pending_actions ORDER BY createdAt ASC")
    suspend fun all(): List<PendingAction>

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_actions SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: Long)
}
