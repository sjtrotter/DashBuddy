package cloud.trotter.dashbuddy.ui.main.ratings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * Ratings screen (#316) — binds the focused platform's `RatingsSnapshot` (already
 * in app state) via [RatingsViewModel]. Headline standing rates render as
 * [AppGaugeRing]s; acceptance / delivery counts / shopping-quality render as
 * [AppStatTile]s. Numbers only — no store/customer text (Principle 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingsScreen(
    onBack: () -> Unit,
    viewModel: RatingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ratings_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!state.hasData) {
            RatingsEmpty(
                platformName = state.platformName,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.platformName?.let {
                Text(it, style = AppTheme.num.lgNum, color = AppTheme.colors.text)
            }

            HeadlineGauges(state)

            StandingTiles(state)

            if (state.hasShoppingQuality) {
                Text(
                    stringResource(R.string.ratings_screen_shopping_quality_header),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.text2,
                )
                ShoppingQualityTiles(state)
            }
        }
    }
}

/** Customer rating (0–5), on-time and completion (0–100%) as gauge rings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadlineGauges(state: RatingsUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.customerRating?.let {
            AppGaugeRing(
                progress = (it / 5.0).toFloat(),
                value = Formats.decimal(it, digits = 2),
                label = stringResource(R.string.ratings_screen_gauge_customer),
                color = AppTheme.colors.accent,
            )
        }
        state.onTimeRate?.let {
            AppGaugeRing(
                progress = (it / 100.0).toFloat(),
                value = percentText(it),
                label = stringResource(R.string.ratings_screen_gauge_on_time),
                color = AppTheme.colors.accent,
            )
        }
        state.completionRate?.let {
            AppGaugeRing(
                progress = (it / 100.0).toFloat(),
                value = percentText(it),
                label = stringResource(R.string.ratings_screen_gauge_completion),
                color = AppTheme.colors.accent,
            )
        }
    }
}

/** Acceptance rate + 30-day / lifetime delivery counts. */
@Composable
private fun StandingTiles(state: RatingsUiState) {
    val tiles = buildList {
        state.acceptanceRate?.let { add(stringResource(R.string.ratings_screen_tile_acceptance) to percentText(it)) }
        state.deliveriesLast30Days?.let { add(stringResource(R.string.ratings_screen_tile_deliveries_30d) to Formats.commaInt(it)) }
        state.lifetimeDeliveries?.let { add(stringResource(R.string.ratings_screen_tile_lifetime_deliveries) to Formats.commaInt(it)) }
    }
    TileGrid(tiles)
}

/** Shopping-quality percentages + lifetime shop count (shop platforms only). */
@Composable
private fun ShoppingQualityTiles(state: RatingsUiState) {
    val tiles = buildList {
        state.originalItemsFoundRate?.let { add(stringResource(R.string.ratings_screen_tile_original_items_found) to percentText(it)) }
        state.totalItemsFoundRate?.let { add(stringResource(R.string.ratings_screen_tile_total_items_found) to percentText(it)) }
        state.substitutionIssuesRate?.let { add(stringResource(R.string.ratings_screen_tile_substitution_issues) to percentText(it)) }
        state.itemsWithQualityIssuesRate?.let { add(stringResource(R.string.ratings_screen_tile_quality_issues) to percentText(it)) }
        state.itemsWrongOrMissingRate?.let { add(stringResource(R.string.ratings_screen_tile_wrong_or_missing) to percentText(it)) }
        state.lifetimeShoppingOrders?.let { add(stringResource(R.string.ratings_screen_tile_lifetime_shops) to Formats.commaInt(it)) }
    }
    TileGrid(tiles)
}

/** Lays a list of (label, value) tiles two-per-row; the odd tile fills its row. */
@Composable
private fun TileGrid(tiles: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { (label, value) ->
                    AppStatTile(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RatingsEmpty(platformName: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AppCard {
            Text(
                text = stringResource(R.string.ratings_screen_empty_title),
                style = AppTheme.num.lgNum,
                color = AppTheme.colors.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.ratings_screen_empty_desc_format,
                    platformName ?: stringResource(R.string.ratings_screen_empty_fallback_platform),
                ),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.text2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Renders a 0–100 rate as an integer percent, e.g. `95.0` → "95%". */
private fun percentText(rate: Double): String = "${Formats.decimal(rate, digits = 0)}%"
