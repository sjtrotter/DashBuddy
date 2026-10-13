package cloud.trotter.dashbuddy.core.designsystem.time

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The shared time kit (#358): the CLAUDE.md-canonical 1-Hz ticker plus the
 * device-aware wall-clock formatter for the bubble HUD's glance surfaces.
 *
 * The pure duration/countdown formatters (`formatDuration`/`formatCountdown`)
 * moved to `:domain` (`format.TimeFormats`, #467) so the formatting SSOT is
 * colocated with [cloud.trotter.dashbuddy.domain.format.Formats] and reachable
 * from every layer; only the Compose-bound helpers remain here.
 */

/** The wall clock the ticker reads (#1333). Production uses the system clock; a Compose test provides its own. */
fun interface NowProvider {
    fun nowMillis(): Long
}

/** The ticker's clock. Every composable that shows a time reads it through [rememberNow], never the system clock directly. */
val LocalNowProvider = staticCompositionLocalOf<NowProvider> { NowProvider { System.currentTimeMillis() } }

/**
 * 1-Hz tick, scoped to the calling composable. See CLAUDE.md ▸ Reactive UI Principles.
 * Reads [LocalNowProvider]; a test advances its clock and the Compose test clock together.
 */
@Composable
fun rememberNow(tickMs: Long = 1000L): State<Long> {
    val clock = LocalNowProvider.current
    return produceState(initialValue = clock.nowMillis(), clock, tickMs) {
        while (true) {
            delay(tickMs)
            value = clock.nowMillis()
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
