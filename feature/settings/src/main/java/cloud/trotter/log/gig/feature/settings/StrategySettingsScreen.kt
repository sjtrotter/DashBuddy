package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.log.gig.feature.settings.R
import cloud.trotter.log.gig.domain.config.OfferAutomationConfig
import cloud.trotter.log.gig.domain.evaluation.EvaluationConfig
import cloud.trotter.log.gig.domain.evaluation.OfferSimulation
import cloud.trotter.log.gig.domain.evaluation.ScoringRule
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.settings.components.DraggableRuleRow
import cloud.trotter.log.gig.feature.settings.components.FakeOfferCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun StrategySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.evaluationConfig.collectAsStateWithLifecycle()
    val automation by viewModel.automationConfig.collectAsStateWithLifecycle() // #577

    StrategySettingsContent(
        config = config,
        automation = automation,
        onBack = onBack,
        onReorder = { viewModel.reorderRules(it) },
        onToggleProtect = { viewModel.toggleProtectStats(it) },
        onToggleShopping = { viewModel.toggleAllowShopping(it) },
        onToggleQuickDeclines = { viewModel.toggleQuickDeclines(it) },
        onUpdateRule = { viewModel.updateRule(it) },
    )
}

/**
 * Offer rules, stateless (#1447 hoisted it out of [StrategySettingsScreen] so a screenshot test
 * renders it without Hilt). The page sits below a tab, so it has a title bar with a back button;
 * "Test offer" stays the sticky section heading under it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StrategySettingsContent(
    config: EvaluationConfig,
    automation: OfferAutomationConfig,
    onBack: () -> Unit,
    onReorder: (List<ScoringRule>) -> Unit,
    onToggleProtect: (Boolean) -> Unit,
    onToggleShopping: (Boolean) -> Unit,
    onToggleQuickDeclines: (Boolean) -> Unit,
    onUpdateRule: (ScoringRule) -> Unit,
) {
    // Local Sim State
    var simPay by remember { mutableFloatStateOf(6.50f) }
    var simDist by remember { mutableFloatStateOf(3.2f) }

    // Derived display state, memoized on its own inputs (#367 memoization, #944 UDF): a pure
    // :domain call, not a ViewModel function invoked from composition.
    val simulationResult = remember(simPay, simDist, config) {
        OfferSimulation.simulate(simPay.toDouble(), simDist.toDouble(), config)
    }

    // --- REORDERABLE STATE ---
    val haptic = LocalHapticFeedback.current
    val rules = config.rules

    // FIX 1: Create the list state explicitly
    val listState = rememberLazyListState()

    // FIX 2: Pass listState into the reorderable state
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val offset = 2
        val fromIndex = from.index - offset
        val toIndex = to.index - offset

        if (fromIndex >= 0 && toIndex >= 0 && fromIndex < rules.size && toIndex < rules.size) {
            val newList = rules.toMutableList()
            newList.add(toIndex, newList.removeAt(fromIndex))

            onReorder(newList)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.strategy_settings_title)) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // --- STICKY HEADER ---
            Surface(
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.zIndex(1f)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.strategy_settings_the_lab_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FakeOfferCard(evaluation = simulationResult)

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.strategy_settings_sim_pay_format, Formats.money(simPay.toDouble())),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Slider(
                                value = simPay,
                                onValueChange = { simPay = it },
                                valueRange = 2f..30f
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.strategy_settings_sim_dist_format, Formats.decimal(simDist.toDouble())),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Slider(
                                value = simDist,
                                onValueChange = { simDist = it },
                                valueRange = 0.5f..20f
                            )
                        }
                    }
                }
            }

            // --- RULE LIST ---
            LazyColumn(
                state = listState, // FIX 3: Use the explicitly created listState
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
            ) {

                // Index 0
                item {
                    Text(
                        stringResource(R.string.strategy_settings_global_overrides_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        label = stringResource(R.string.strategy_settings_protect_stats_label),
                        subtitle = stringResource(R.string.strategy_settings_protect_stats_subtitle),
                        checked = config.protectStatsMode,
                        onCheckedChange = onToggleProtect
                    )
                    SwitchRow(
                        label = stringResource(R.string.strategy_settings_allow_shopping_label),
                        subtitle = stringResource(R.string.strategy_settings_allow_shopping_subtitle),
                        checked = config.allowShopping,
                        onCheckedChange = onToggleShopping
                    )
                    SwitchRow(
                        label = stringResource(R.string.strategy_settings_quick_declines_label),
                        subtitle = stringResource(R.string.strategy_settings_quick_declines_subtitle),
                        checked = automation.quickDeclinesEnabled,
                        onCheckedChange = onToggleQuickDeclines
                    )
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                }

                // Index 1
                item {
                    Text(
                        stringResource(R.string.strategy_settings_priorities_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.strategy_settings_priorities_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.text3
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // Index 2+ (Draggable Items)
                items(rules, key = { it.id }) { rule ->
                    ReorderableItem(reorderableState, key = rule.id) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp

                        // Create the modifier for the HANDLE
                        val handleModifier = Modifier.draggableHandle()

                        // Create the modifier for the ROW (Animations only)
                        val rowModifier = Modifier.graphicsLayer {
                            scaleX = if (isDragging) 1.05f else 1f
                            scaleY = if (isDragging) 1.05f else 1f
                            translationY = if (isDragging) 4f else 0f
                            shadowElevation = elevation.value
                        }

                        Box(modifier = rowModifier) { // Wrap in a Box to apply graphicsLayer
                            DraggableRuleRow(
                                rule = rule,
                                modifier = handleModifier, // <--- Pass handle logic here
                                onUpdate = onUpdateRule
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text3)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
