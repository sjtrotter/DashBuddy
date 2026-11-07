package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The bottom navigation bar: Home, Recap, Plan, icons only. Each icon carries its tab's name as its
 * content description for screen readers and shows it in a tooltip on long press; the selected tab
 * keeps the indicator.
 */
@Composable
fun MainNavigationBar(selectedTab: TopLevel, onSelectTab: (TopLevel) -> Unit) {
    NavigationBar {
        TopLevel.entries.forEach { tab -> NavTab(tab, selected = tab == selectedTab, onSelect = { onSelectTab(tab) }) }
    }
}

/**
 * One tab. The tooltip takes no input of its own (`enableUserInput = false`): its gesture handling wraps
 * the icon in a separate semantics node, so the tab would lose its name. The tab's own press opens the
 * tooltip instead, and a press long enough to show it does not also switch tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.NavTab(tab: TopLevel, selected: Boolean, onSelect: () -> Unit) {
    val name = stringResource(tab.labelRes)
    val tooltip = rememberTooltipState()
    val presses = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    var longPressed by remember { mutableStateOf(false) }
    LaunchedEffect(presses, longPressMs) {
        presses.interactions.collectLatest { interaction ->
            if (interaction is PressInteraction.Press) {
                longPressed = false
                delay(longPressMs)
                longPressed = true
                scope.launch { tooltip.show() }
            }
        }
    }
    NavigationBarItem(
        selected = selected,
        onClick = { if (longPressed) longPressed = false else onSelect() },
        icon = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(name) } },
                state = tooltip,
                enableUserInput = false,
            ) {
                Icon(tab.icon, contentDescription = name)
            }
        },
        alwaysShowLabel = false,
        interactionSource = presses,
    )
}
