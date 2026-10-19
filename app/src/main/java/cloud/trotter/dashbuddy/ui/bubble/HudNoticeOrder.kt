package cloud.trotter.dashbuddy.ui.bubble

import java.util.concurrent.atomic.AtomicLong

/** Both notices use one in-process ordering, including commits in the same millisecond. */
internal object HudNoticeOrder {
    private val tokens = AtomicLong()
    fun next(): Long = tokens.incrementAndGet()
}
