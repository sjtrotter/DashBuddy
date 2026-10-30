package cloud.trotter.log.gig.core.designsystem.time

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.Clock
import java.util.Date

/**
 * The shared time kit (#358): the CLAUDE.md-canonical 1-Hz ticker plus the
 * device-aware wall-clock formatter for the bubble HUD's glance surfaces.
 *
 * The pure duration/countdown formatters (`formatDuration`/`formatCountdown`)
 * moved to `:domain` (`format.TimeFormats`, #467) so the formatting SSOT is
 * colocated with [cloud.trotter.log.gig.domain.format.Formats] and reachable
 * from every layer; only the Compose-bound helpers remain here.
 */

/**
 * The clock every time-derived composable reads, through [rememberNow] (#1333).
 * Production uses the system clock; a Compose test provides one it controls.
 * Provide a stable instance: a new instance re-keys the ticker.
 */
val LocalNowProvider = staticCompositionLocalOf<Clock> { Clock.systemUTC() }

/**
 * 1-Hz tick, scoped to the calling composable. See CLAUDE.md ▸ Reactive UI Principles.
 * Reads [LocalNowProvider]; a test advances its clock and the Compose test clock together.
 */
@Composable
fun rememberNow(tickMs: Long = 1000L): State<Long> {
    val clock = LocalNowProvider.current
    return produceState(initialValue = clock.millis(), clock, tickMs) {
        value = clock.millis()
        while (true) {
            delay(tickMs)
            value = clock.millis()
        }
    }
}

/**
 * One delay to the deadline, no polling. Reads [LocalNowProvider]; tests move the clock
 * and mainClock together as for [rememberNow].
 */
@Composable
fun rememberDeadlinePassed(deadlineMs: Long): State<Boolean> {
    val clock = LocalNowProvider.current
    return produceState(initialValue = clock.millis() >= deadlineMs, deadlineMs, clock) {
        if (!value) {
            delay((deadlineMs - clock.millis()).coerceAtLeast(0))
            value = true
        }
    }
}

/**
 * A remembered wall-clock formatter honoring the device's 12/24-hour setting
 * (e.g. "3:42 PM" / "15:42"). Remembered per composition — the old private
 * copy allocated a new lambda every recomposition under a 1-Hz ticker.
 */
@Composable
fun rememberTimeFormatter(): (Long) -> String {
    val ctx = LocalContext.current
    val use24 = DateFormat.is24HourFormat(ctx)
    return remember(use24) {
        val pattern = if (use24) "HH:mm" else "h:mm a"
        { millis: Long -> DateFormat.format(pattern, Date(millis)).toString() }
    }
}
