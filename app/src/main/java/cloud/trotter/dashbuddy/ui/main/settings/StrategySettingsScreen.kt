package cloud.trotter.dashbuddy.ui.main.settings

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.ui.main.settings.components.DraggableRuleRow
import cloud.trotter.dashbuddy.ui.main.settings.components.FakeOfferCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StrategySettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.evaluationConfig.collectAsState()

    // Local Sim State
    var simPay by remember { mutableFloatStateOf(6.50f) }
    var simDist by remember { mutableFloatStateOf(3.2f) }

    val simulationResult = viewModel.simulateOffer(simPay.toDouble(), simDist.toDouble())

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

            viewModel.reorderRules(newList)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // --- STICKY HEADER ---
        Surface(
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.zIndex(1f)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "The Lab",
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
                            "Pay: $${String.format(Locale.getDefault(), "%.2f", simPay)}",
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
                            "Dist: ${String.format(Locale.getDefault(), "%.1f", simDist)} mi",
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
                    "Global Overrides",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    label = "🛡️ Protect Stats Mode",
                    subtitle = "Auto-accept everything",
                    checked = config.protectStatsMode,
                    onCheckedChange = { viewModel.toggleProtectStats(it) }
                )
                SwitchRow(
                    label = "🛒 Allow Shopping Orders",
                    subtitle = "If off, Red Card orders are auto-declined",
                    checked = config.allowShopping,
                    onCheckedChange = { viewModel.toggleAllowShopping(it) }
                )
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
            }

            // Index 1
            item {
                Text(
                    "Priorities (Rack & Stack)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Drag to reorder. Top rules matter most.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
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
                            onUpdate = { updatedRule -> viewModel.updateRule(updatedRule) }
                        )
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
    onCheckedChange: (Boolean) -> Unit
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}