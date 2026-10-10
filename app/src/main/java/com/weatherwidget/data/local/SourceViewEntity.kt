package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.weatherwidget.shared.sourceview.SourceViewDayRow
import com.weatherwidget.shared.sourceview.SourceViewSwitch

/**
 * Source switches per day (`SourceViewTally` in `:shared`). Desktop keeps the same table
 * (`SourceViewSql.DAYS_DDL`, `:shared`). No timestamps: per-day counts only.
 */
@Entity(
    tableName = "source_view_days",
    primaryKeys = ["date", "sourceId", "viewKind", "triggerKind", "wasPrimary"],
)
data class SourceViewDayEntity(
    val date: Long,
    val sourceId: String,
    val viewKind: String,
    val triggerKind: String,
    val wasPrimary: Boolean,
    val switches: Int = 1,
) {
    fun toRow() = SourceViewDayRow(date, sourceId, viewKind, triggerKind, wasPrimary, switches)
}

/** One row (id = 1): the day `source_view_days` started counting (`SourceViewSql.TRACKING_DDL`). */
@Entity(tableName = "source_view_tracking")
data class SourceViewTrackingEntity(
    @PrimaryKey val id: Int = 1,
    val startedDate: Long,
)

@Dao
interface SourceViewDao {
    @Query(
        "UPDATE source_view_days SET switches = switches + 1 WHERE date = :date AND sourceId = :sourceId " +
            "AND viewKind = :viewKind AND triggerKind = :triggerKind AND wasPrimary = :wasPrimary",
    )
    suspend fun increment(date: Long, sourceId: String, viewKind: String, triggerKind: String, wasPrimary: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SourceViewDayEntity): Long

    /** Increment-then-insert, as [ApiUsageDao.logCall]: SQLite upsert needs API 30, minSdk is 26. */
    @Transaction
    suspend fun record(switch: SourceViewSwitch) {
        val kind = switch.viewKind.name
        val trigger = switch.trigger.name
        if (increment(switch.dateMs, switch.sourceId, kind, trigger, switch.wasPrimary) == 0) {
            insert(SourceViewDayEntity(switch.dateMs, switch.sourceId, kind, trigger, switch.wasPrimary, 1))
        }
    }

    @Query("SELECT * FROM source_view_days WHERE date >= :sinceDateMs")
    suspend fun getSince(sinceDateMs: Long): List<SourceViewDayEntity>

    @Query("SELECT startedDate FROM source_view_tracking WHERE id = 1")
    suspend fun getTrackingStart(): Long?

    /** Retention: `SourceViewTally.retentionCutoffMs`. */
    @Query("DELETE FROM source_view_days WHERE date < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
