package cloud.trotter.dashbuddy.ui.components.economy

import kotlinx.coroutines.Deferred
import cloud.trotter.dashbuddy.ui.main.settings.garage.WriteResult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstants
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.state.Platform

@Immutable
data class TimeConstantsPlatformUiState(
    val platform: Platform,
    val automatic: TimeConstantPair,
    val effective: TimeConstantPair,
    val sampleCount: Int,
)

@Immutable
data class TimeConstantsUiState(
    val paceOverride: Double? = null,
    val overheadOverride: Double? = null,
    val platforms: List<TimeConstantsPlatformUiState> = emptyList(),
)

/** Presentation only: the host supplies resolved values and owns every persistence action. */
@Composable
fun EconomyTimeConstantsSection(
    state: TimeConstantsUiState,
    onOverrideChange: (EconomyField, Double?) -> Deferred<WriteResult>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.economy_time_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.economy_time_help), style = MaterialTheme.typography.bodySmall)
        TimeOverrideControl(EconomyField.AVG_MIN_PER_MILE, state.paceOverride, onOverrideChange, enabled)
        TimeOverrideControl(EconomyField.BASE_PICKUP_MIN, state.overheadOverride, onOverrideChange, enabled)
        state.platforms.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.platform.displayName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.economy_time_automatic_values,
                    Formats.decimal(row.automatic.minutesPerMile, 2), Formats.decimal(row.automatic.stopOverheadMinutes, 2)))
                Text(stringResource(R.string.economy_time_effective_values,
                    Formats.decimal(row.effective.minutesPerMile, 2), Formats.decimal(row.effective.stopOverheadMinutes, 2)))
                Text(pluralStringResource(R.plurals.economy_time_deliveries, row.sampleCount, row.sampleCount))
                if (row.sampleCount < TimeConstants.MIN_SAMPLES) {
                    Text(stringResource(R.string.economy_time_default_progress, row.sampleCount, TimeConstants.MIN_SAMPLES))
                }
                if (row.sampleCount > TimeConstants.WINDOW_SIZE) {
                    Text(stringResource(R.string.economy_time_window, TimeConstants.WINDOW_SIZE))
                }
            }
        }
    }
}

@Composable
private fun TimeOverrideControl(
    field: EconomyField,
    value: Double?,
    onOverrideChange: (EconomyField, Double?) -> Deferred<WriteResult>,
    enabled: Boolean,
) {
    val session = LocalEditSession.current
    Column {
        CurrencyInput(
            label = stringResource(if (field == EconomyField.AVG_MIN_PER_MILE)
                R.string.economy_time_pace else R.string.economy_time_overhead),
            value = value ?: if (field == EconomyField.AVG_MIN_PER_MILE) 2.5 else 0.0,
            onValueChange = { onOverrideChange(field, it) },
            fieldKey = field.name, showCurrencySymbol = false, enabled = enabled,
            valid = { text -> nonnegativeNumber(text)?.takeIf {
                field != EconomyField.AVG_MIN_PER_MILE || it > 0
            } },
        )
        TextButton(enabled = enabled, onClick = {
            session.discard(field.name)
            onOverrideChange(field, null)
        }) { Text(stringResource(R.string.economy_time_use_automatic)) }
    }
}
