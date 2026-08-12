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
 * **One disclosure per screen** (#1024 rule 2) — the tappable "How these numbers work" row that owns
 * the §9 wording every money surface used to restate per card: what "frozen" costs mean, what an
 * estimate is, and where cash tips sit.
 *
 * The honesty rules are not weakened by consolidating them; they are stated **once, in full, where a
 * driver can find them**, instead of as nine footnotes nobody reads. Each line is the SAME string its
 * originating card already ships — one owner for the wording, so the footer can never drift from the
 * card whose figure it explains (Principle 5). Consumers: the Playbook (#1024 part 1) and **Home**
 * (#1024 part 3, which deleted Home's two per-card disclosure lines in favour of this row); the Money
 * tab joins them in part 2.
 *
 * [includePlanProjection] adds the projection disclaimer — *from your own record, lifetime; a
 * projection, not a promise* — and is **required on any screen that renders a plan or a
 * best-stretch rate**. Home's plan headline names the weekday it quotes ("your Mondays") but cannot
 * carry the lifetime scope and the not-a-guarantee qualifier inline without becoming a paragraph, so
 * they live here; the string is the Weekly Plan's own (`weekly_plan_provenance`), not a second copy.
 * It is opt-in rather than always-on because a screen that projects nothing must not carry a
 * disclaimer about projections — that is the noise #1024 exists to remove.
 *
 * Collapsed by default and `rememberSaveable`-backed: a disclosure that reopens itself on every
 * rotation is noise, and one that forgets it was opened mid-read is worse.
 */
@Composable
fun HowNumbersWorkFooter(
    modifier: Modifier = Modifier,
    includePlanProjection: Boolean = false,
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
                // Frozen cost — the same sentence the Money tab's car-cost disclosure expands to.
                DisclosureNote(stringResource(R.string.money_tab_where_went_frozen_note))
                // Estimate basis — the same sentence the Offers tab states above its funnel.
                DisclosureNote(stringResource(R.string.offers_tab_frozen_disclosure))
                // Cash tips: driver-attested money, added on top and never estimated (#688).
                DisclosureNote(stringResource(R.string.disclosure_cash_tips))
                // Projections — the Weekly Plan's own provenance sentence, on the screens that project.
                if (includePlanProjection) {
                    DisclosureNote(stringResource(R.string.weekly_plan_provenance))
                }
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
