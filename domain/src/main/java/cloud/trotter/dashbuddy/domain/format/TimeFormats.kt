package cloud.trotter.dashbuddy.domain.format

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Duration / countdown formatters (#358, #467) — the pure half of the former
 * `:core:designsystem` TimeKit. Lives in `:domain` alongside [Formats] so the
 * formatting SSOT is colocated and reachable by every layer (the Compose
 * helpers `rememberNow`/`rememberTimeFormatter` stay in designsystem — they
 * need Compose/Android).
 *
 * Digits are pinned to [Locale.ROOT]: durations and countdowns are clock-like
 * strings ("3m 12s", "7:05") whose digits must not localize to non-ASCII
 * numerals on a glance surface.
 */

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
