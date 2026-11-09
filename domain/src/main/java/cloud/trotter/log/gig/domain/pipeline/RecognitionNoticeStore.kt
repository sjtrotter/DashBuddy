package cloud.trotter.log.gig.domain.pipeline

/**
 * The last recognition notice time per platform wire (#1560), carried across process restarts so a
 * platform posts at most one notice a day. Implementations may throw; the owner (`RecognitionHealthMonitor`)
 * fails open on every call.
 */
interface RecognitionNoticeStore {
    /** Wire → epoch millis of that platform's last notice. */
    suspend fun load(): Map<String, Long>

    suspend fun save(lastNoticeAt: Map<String, Long>)

    /** No persistence: every process starts with no notice history. */
    object NoOp : RecognitionNoticeStore {
        override suspend fun load(): Map<String, Long> = emptyMap()
        override suspend fun save(lastNoticeAt: Map<String, Long>) = Unit
    }
}
