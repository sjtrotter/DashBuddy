package cloud.trotter.dashbuddy.core.designsystem.time

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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

/** 1-Hz tick, scoped to the calling composable. See CLAUDE.md ▸ Reactive UI Principles. */
@Composable
fun rememberNow(tickMs: Long = 1000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(tickMs)
            value = System.currentTimeMillis()
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
