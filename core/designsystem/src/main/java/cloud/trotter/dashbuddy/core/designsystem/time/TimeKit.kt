package cloud.trotter.dashbuddy.core.designsystem.time

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The shared time kit (#358): the CLAUDE.md-canonical 1-Hz ticker plus the
 * duration/countdown/clock formatters that the bubble HUD's glance surfaces
 * derive from it. One definition repo-wide — the two divergent local copies
 * (FlowCardItem, BubbleScreen) are gone.
 *
 * Digits are pinned to [Locale.ROOT]: durations and countdowns are clock-like
 * strings ("3m 12s", "7:05") whose digits must not localize to non-ASCII
 * numerals on a glance surface.
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
 * "2h 5m" / "3m 12s" / "45s". Negative inputs floor to "0s" — a duration that
 * hasn't started reads as zero, never as a negative.
 */
fun formatDuration(millis: Long): String {
    val safe = millis.coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return when {
        hours > 0 -> String.format(Locale.ROOT, "%dh %dm", hours, minutes)
        minutes > 0 -> String.format(Locale.ROOT, "%dm %ds", minutes, seconds)
        else -> String.format(Locale.ROOT, "%ds", seconds)
    }
}

/**
 * "m:ss" countdown for deadline heroes. Negatives format as their absolute
 * magnitude — the caller renders the ahead/late label and color.
 */
fun formatCountdown(millis: Long): String {
    val safe = kotlin.math.abs(millis)
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(safe)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return String.format(Locale.ROOT, "%d:%02d", totalMinutes, seconds)
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
