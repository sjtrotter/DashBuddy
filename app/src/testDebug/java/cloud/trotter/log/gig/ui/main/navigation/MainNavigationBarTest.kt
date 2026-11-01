package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #1447 — the bottom bar: three labelled tabs, the selected one marked, taps reported, 200 % text fits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainNavigationBarTest {
    @get:Rule val compose = createComposeRule()

    private val tabNode = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private var tapped: TopLevel? = null

    private fun show(selected: TopLevel, fontScale: Float = 1f) = compose.setContent {
        GigLogTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MainNavigationBar(selectedTab = selected, onSelectTab = { tapped = it })
            }
        }
    }

    private fun label(tab: TopLevel) = RuntimeEnvironment.getApplication().getString(tab.labelRes)

    /** The label's own text node, inside its tab (the unmerged tree, so no other "Home" can match). */
    private fun labelNode(tab: TopLevel): SemanticsNodeInteraction =
        compose.onNode(hasText(label(tab)) and hasAnyAncestor(tabNode), useUnmergedTree = true)

    private fun tab(tab: TopLevel): SemanticsNodeInteraction = compose.onNode(tabNode and hasText(label(tab)))

    @Test fun `each tab is labelled and the selected one is marked`() {
        show(TopLevel.ANALYTICS)
        assertEquals(TopLevel.entries.size, compose.onAllNodes(tabNode).fetchSemanticsNodes().size)
        for (entry in TopLevel.entries) {
            labelNode(entry).assertExists()
            if (entry == TopLevel.ANALYTICS) tab(entry).assertIsSelected() else tab(entry).assertIsNotSelected()
        }
    }

    @Test fun `a tap reports its tab`() {
        show(TopLevel.HOME)
        tab(TopLevel.PLAYBOOK).performClick()
        compose.waitForIdle()
        assertEquals(TopLevel.PLAYBOOK, tapped)
    }

    @Test fun `each tab is at least 48 dp`() {
        show(TopLevel.HOME)
        for (entry in TopLevel.entries) tab(entry).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun `at 200 percent text no label overflows`() {
        show(TopLevel.HOME, fontScale = 2f)
        for (entry in TopLevel.entries) {
            val results = mutableListOf<TextLayoutResult>()
            labelNode(entry).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            assertFalse("${entry.name} label overflows its height", layout.didOverflowHeight)
            assertFalse("${entry.name} label overflows its width", layout.didOverflowWidth)
        }
    }
}
