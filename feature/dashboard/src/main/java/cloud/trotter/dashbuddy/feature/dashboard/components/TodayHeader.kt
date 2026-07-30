package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.domain.format.formatLongWeekdayMonthDay
import java.time.LocalDate

/**
 * The Home header (#977 / brief §2): today's date, a **live** wall clock, and the app's own status
 * pill.
 *
 * Reactive UI rules 1–3: the clock is a real clock. It is driven by the shared [rememberNow] ticker
 * rather than by a state-machine emission, so it stays right while the driver looks at it — a frozen
 * clock on a screen that calls itself "Today" is exactly the stale value rule 5 warns about. The
 * per-second tick is funnelled through a [derivedStateOf] over the *formatted* string, so the
 * `Text` recomposes once a minute (when the rendered value actually changes) instead of 60×.
 *
 * [statusText] is resolved copy passed down by the host — the same `@StringRes` status vocabulary the
 * home screen has always shown (offline / looking for offers / heading to pickup / …), derived from
 * `AppState` in the ViewModel. This composable adds no status logic of its own.
 */
@Composable
fun TodayHeader(
    today: LocalDate,
    statusText: String,
    dashing: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val formatter = rememberTimeFormatter()
    val now: State<Long> = rememberNow()
    // Reading `now.value` only inside the derivation keeps this composable off the 1-Hz invalidation
    // path: it recomposes when the rendered minute changes, not every tick.
    val clock by remember(formatter) { derivedStateOf { formatter(now.value) } }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = formatLongWeekdayMonthDay(today),
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
            )
            Text(text = clock, style = AppTheme.num.mdNum, color = c.text2)
        }
        AppChip(
            text = statusText,
            color = if (dashing) c.good else c.neutral,
            container = if (dashing) c.goodBg else c.neutralBg,
        )
    }
}
