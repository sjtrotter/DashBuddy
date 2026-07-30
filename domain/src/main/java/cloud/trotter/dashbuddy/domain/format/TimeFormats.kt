package cloud.trotter.dashbuddy.domain.format

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

/**
 * "Jul 3, 2026" — a calendar date for a review surface (recent-dashes list, #315/#650).
 *
 * Unlike the clock/duration strings above, a calendar date is display copy the dasher
 * reads in their own locale, so it follows the [Formats] display policy: the localized
 * MEDIUM date in [locale], resolved against the device [zone]. This is the one owner for
 * a rendered session date — the future #650 drill-down derives from here too (Principle 5).
 */
fun formatShortDate(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(millis).atZone(zone))

/**
 * "Jul 13" — a month-and-day label for the analytics period pager / range picker (#970).
 *
 * Display copy, so it follows the [Formats] policy and localizes its month name through [locale].
 * The field ORDER is a fixed `MMM d` pattern rather than a locale-derived skeleton: `java.time` has
 * no skeleton API in pure Kotlin (Android's `getBestDateTimePattern` is not reachable from `:domain`),
 * and a fixed order is the honest, greppable compromise until #428's i18n pass revisits it. One owner
 * for the label — the pager, the picker's commit button, and the recap hero all call it (Principle 5).
 */
fun formatMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMM d", locale).format(date)

/**
 * "Wed, Jul 15" — a weekday-qualified day label for the Money tab's tapped day bar (#973 / brief §4.2).
 *
 * The weekday is the point: a driver reads an earnings-by-day chart looking for "my Fridays", so the
 * selected bar has to name the day of week, not just the date. Same fixed-order compromise and same
 * one-owner rule as [formatMonthDay].
 */
fun formatWeekdayMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(date)

/**
 * "Monday, Jul 20" — the Home header's date line (#977 / brief §2).
 *
 * The FULL weekday is the point on Home: the whole screen is "today", and the plan strip below it is
 * explicitly *this weekday's* history, so the header has to name the weekday in full rather than the
 * three-letter form [formatWeekdayMonthDay] uses inside a chart. Same fixed-order compromise and same
 * one-owner rule as [formatMonthDay].
 */
fun formatLongWeekdayMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEEE, MMM d", locale).format(date)

/**
 * "5–8 PM" / "11 AM–1 PM" — an hour-of-day RANGE label (#977 / brief §2's plan-strip headline).
 *
 * Both bounds are hours-of-day; [endHourExclusive] is the first hour NOT in the range, so a window
 * covering the 5pm/6pm/7pm cells reads "5–8 PM" (and `24` renders as midnight). The meridiem is
 * printed once when both ends share it — a range is read as one span, not two timestamps.
 *
 * Unlike the compact [hourOfDayLabel] axis token this is display copy, so the hour digit and the
 * AM/PM marker both come from [locale] via `java.time` patterns. It is deliberately **12-hour
 * regardless of the device's 24-hour setting**: that setting is only reachable from Android/Compose
 * (`DateFormat.is24HourFormat`), which `:domain` cannot see — the same documented compromise
 * [hourOfDayLabel] already makes. One owner for the label (Principle 5).
 */
fun hourRangeLabel(
    startHour: Int,
    endHourExclusive: Int,
    locale: Locale = Locale.getDefault(),
): String {
    val start = LocalTime.of(((startHour % 24) + 24) % 24, 0)
    val end = LocalTime.of(((endHourExclusive % 24) + 24) % 24, 0)
    val hour = DateTimeFormatter.ofPattern("h", locale)
    val meridiem = DateTimeFormatter.ofPattern("a", locale)
    val startMeridiem = meridiem.format(start)
    val endMeridiem = meridiem.format(end)
    return if (startMeridiem == endMeridiem) {
        "${hour.format(start)}$HOUR_RANGE_SEPARATOR${hour.format(end)} $endMeridiem"
    } else {
        "${hour.format(start)} $startMeridiem$HOUR_RANGE_SEPARATOR${hour.format(end)} $endMeridiem"
    }
}

/** En dash — a range separator, not a hyphen (matches the analytics pager's range copy). */
private const val HOUR_RANGE_SEPARATOR = "–"

/** "Jul 13, 2026" — [formatMonthDay] plus the year, for a window that leaves the current year. */
fun formatMonthDayYear(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(date)

/** "July 2026" — a month heading for the range picker's calendar pager (#970). */
fun formatMonthYear(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(month)

/** "July" — a bare month name, for a month window inside the current year (#970). */
fun formatMonthName(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMMM", locale).format(month)

/**
 * Compact 12-hour clock label for an **hour-of-day** (0..23): `0→"12a"`, `6→"6a"`, `12→"12p"`,
 * `18→"6p"`. Not a timestamp — a fixed axis/label token for the hour-of-week heatmap (#315 H5), so it
 * takes no zone/locale: the `a`/`p` suffix is a compact machine marker, not localized display copy. The
 * one owner for this label (Principle 5) — the heatmap grid axis and its best-hour callout both call it.
 */
fun hourOfDayLabel(hour: Int): String {
    val h24 = ((hour % 24) + 24) % 24
    val suffix = if (h24 < 12) "a" else "p"
    val h = (h24 % 12).let { if (it == 0) 12 else it }
    return "$h$suffix"
}

/**
 * "5:42 PM" / "17:42" — a localized clock time for a review surface (the per-delivery rows of the
 * #650 drill-down). Same display policy as [formatShortDate]: the localized SHORT time in [locale],
 * resolved against the device [zone] — display copy the dasher reads in their own locale, not a
 * machine string. One owner for a rendered clock time (Principle 5).
 */
fun formatClockTime(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(millis).atZone(zone))
