package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The analytics projector's watermark (#314) — a singleton row.
 *
 * Lives in **Room, not DataStore**, so the projector can write records AND
 * advance the watermark in one `db.withTransaction`, making the fold exactly-once
 * by construction (a second commit domain would re-process or silently skip a
 * batch on a crash between the two writes).
 *
 * [projectorVersion] is the event-sourcing dividend: bumping the constant makes
 * the next start drop all record rows, reset the watermark to 0, and re-fold the
 * whole durable log — backfill and rebuild are the same code path (PR2).
 */
@Entity(tableName = "analytics_projection_state")
data class AnalyticsProjectionStateEntity(
    /** Singleton row. */
    @PrimaryKey val id: Int = 1,
    /** Last app_events.sequenceId folded. */
    val watermarkSequenceId: Long,
    /** Fold-logic version; mismatch ⇒ wipe records + refold from 0. */
    val projectorVersion: Int,
)
