package cloud.trotter.log.gig.domain.analytics

/**
 * Where the records (the read-model tables the projector folds from the event log) stand against
 * that log. Every look-back screen reads one value of this; `RecordsStatusRepository` in `:core:data`
 * is its one owner.
 *
 * - [REBUILDING]: the projector wiped the records for a version change and is refolding the log.
 *   Every period reads empty or partial, so a screen holds its figures back.
 * - [UPDATING]: the records run behind the log (their watermark is below the latest event id),
 *   usually just after a shift ends. Figures show, with one line saying they are catching up.
 * - [UP_TO_DATE]: the records have folded every event in the log.
 */
enum class RecordsStatus {
    UP_TO_DATE,
    UPDATING,
    REBUILDING,
    ;

    companion object {
        /**
         * The status from its three inputs. A null [watermark] (nothing folded yet) behind a non-null
         * [latestEventId] is [UPDATING]; an empty log ([latestEventId] null) is [UP_TO_DATE].
         */
        fun of(rebuilding: Boolean, watermark: Long?, latestEventId: Long?): RecordsStatus = when {
            rebuilding -> REBUILDING
            latestEventId != null && (watermark ?: 0L) < latestEventId -> UPDATING
            else -> UP_TO_DATE
        }
    }
}
