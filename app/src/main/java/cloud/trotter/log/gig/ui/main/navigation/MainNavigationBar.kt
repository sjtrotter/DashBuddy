package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * The bottom navigation bar (#1447): Home, Analytics, Playbook, always labelled. The label names the
 * item, so the icons carry no description of their own.
 */
@Composable
fun MainNavigationBar(selectedTab: TopLevel, onSelectTab: (TopLevel) -> Unit) {
    NavigationBar {
        TopLevel.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onSelectTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}
