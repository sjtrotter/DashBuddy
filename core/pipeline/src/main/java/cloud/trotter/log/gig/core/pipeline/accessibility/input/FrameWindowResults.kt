package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason

internal data class LocatedWindow<W : Any, R : Any>(
    val window: W,
    val root: R,
    val totalWindowCount: Int,
    val overlay: Boolean = false,
)

internal sealed interface Foreground<out W : Any, out R : Any> {
    data class Found<W : Any, R : Any>(val located: LocatedWindow<W, R>) : Foreground<W, R>
    data class Refused(val reason: ForegroundSkipReason) : Foreground<Nothing, Nothing>
}

internal sealed interface OverlayScan<out W : Any, out R : Any> {
    data object None : OverlayScan<Nothing, Nothing>
    data class Overlay<W : Any, R : Any>(val located: LocatedWindow<W, R>) : OverlayScan<W, R>
    data class Refused(val reason: ForegroundSkipReason) : OverlayScan<Nothing, Nothing>
}

internal sealed interface OverlayProbe<out R : Any> {
    class Candidate<R : Any>(val packageName: String, val root: R?) : OverlayProbe<R>
    data object NotCandidate : OverlayProbe<Nothing>
    data object Unreadable : OverlayProbe<Nothing>
    data object NoDisplayArea : OverlayProbe<Nothing>
    data object BudgetExhausted : OverlayProbe<Nothing>
}

internal sealed interface ActiveWindow<out W : Any, out R : Any> {
    data class Enabled<W : Any, R : Any>(val window: W, val root: R) : ActiveWindow<W, R>
    data class NotEnabled<W : Any, R : Any>(val window: W, val root: R?) : ActiveWindow<W, R>
    data class Unknown<W : Any>(val window: W?) : ActiveWindow<W, Nothing>
}
