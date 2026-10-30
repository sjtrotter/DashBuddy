package cloud.trotter.log.gig.domain.state

import kotlinx.serialization.Serializable

/** A positive timer generation, minted by [PlatformRegion.mintWakeId] at installation. */
@Serializable
@JvmInline
value class WakeId internal constructor(val value: Long) {
    init {
        require(value > 0L) { "A wake id must be positive" }
    }
}

/** The deadline and minted identity shared by the four pending timer windows. */
sealed interface WakeBearing {
    val deadline: Long
    val wakeId: WakeId
}

/** Preserve the timer diff's existing projection and effect text without consuming an id. */
val WakeBearing.wake: PendingWake get() = PendingWake(deadline, wakeId)
