package cloud.trotter.log.gig.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cloud.trotter.log.gig.ui.main.navigation.MainNavigationBar
import cloud.trotter.log.gig.ui.main.navigation.TopLevel

/**
 * The app shell's frame (#1447): the bottom navigation bar on the three tab roots, nothing below a
 * tab. Stateless: the selected tab comes in, a tap goes out.
 *
 * The frame takes no system insets: each screen's title bar owns the status bar, the tab bar owns
 * the navigation bar, and consuming the bar's padding stops a screen padding the bottom twice.
 */
@Composable
fun MainScaffold(
    selectedTab: TopLevel?,
    onSelectTab: (TopLevel) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (selectedTab != null) MainNavigationBar(selectedTab = selectedTab, onSelectTab = onSelectTab)
        },
    ) { inner ->
        content(Modifier.padding(inner).consumeWindowInsets(inner))
    }
}
