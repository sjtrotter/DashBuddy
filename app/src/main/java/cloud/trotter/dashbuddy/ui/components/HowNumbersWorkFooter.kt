package cloud.trotter.dashbuddy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * One disclosure per screen: cash-tip inclusion and screen-specific scope notes.
 *
 * [includePlanProjection] adds the Weekly Plan's lifetime projection provenance on screens that
 * render a plan or best-stretch rate. [extraNotes] supplies notes specific to the current screen.
 *
 * Collapsed by default; [rememberSaveable] preserves the expanded state across rotation.
 */
@Composable
fun HowNumbersWorkFooter(
    modifier: Modifier = Modifier,
    includePlanProjection: Boolean = false,
    extraNotes: List<String> = emptyList(),
) {
    val c = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = c.line)
        Spacer(Modifier.height(10.dp))
        DisclosureRow(
            text = stringResource(R.string.disclosure_how_numbers_work),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DisclosureNote(stringResource(R.string.disclosure_cash_tips))
                // Projections — the Weekly Plan's own provenance sentence, on the screens that project.
                if (includePlanProjection) {
                    DisclosureNote(stringResource(R.string.weekly_plan_provenance))
                }
                extraNotes.forEach { DisclosureNote(it) }
            }
        }
    }
}

@Composable
private fun DisclosureNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.text3,
    )
}
