package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bottom bar: three icon-only tabs named Home, Recap and Plan for screen readers and on long
 * press, the selected one marked, taps reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainNavigationBarTest {
    @get:Rule val compose = createComposeRule()

    private val tabNode = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private var tapped: TopLevel? = null

    private fun show(selected: TopLevel) = compose.setContent {
        GigLogTheme { MainNavigationBar(selectedTab = selected, onSelectTab = { tapped = it }) }
    }

    private fun name(tab: TopLevel) = RuntimeEnvironment.getApplication().getString(tab.labelRes)

    private fun tab(tab: TopLevel): SemanticsNodeInteraction =
        compose.onNode(tabNode and hasContentDescription(name(tab)))

    @Test fun `the tabs are named Home, Recap and Plan`() {
        assertEquals(listOf("Home", "Recap", "Plan"), TopLevel.entries.map(::name))
    }

    @Test fun `each tab is named by its icon and the selected one is marked`() {
        show(TopLevel.ANALYTICS)
        assertEquals(TopLevel.entries.size, compose.onAllNodes(tabNode).fetchSemanticsNodes().size)
        for (entry in TopLevel.entries) {
            if (entry == TopLevel.ANALYTICS) tab(entry).assertIsSelected() else tab(entry).assertIsNotSelected()
        }
    }

    @Test fun `the bar shows no label text`() {
        show(TopLevel.HOME)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(tabNode),
            useUnmergedTree = true).assertCountEquals(0)
    }

    /** The plain tooltip hides itself after a moment, so the clock is held while the press is down. */
    @Test fun `a long press shows the tab's name and does not switch tabs`() {
        show(TopLevel.HOME)
        compose.onNode(hasText(name(TopLevel.PLAYBOOK))).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        tab(TopLevel.PLAYBOOK).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasText(name(TopLevel.PLAYBOOK))).assertExists()
        tab(TopLevel.PLAYBOOK).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(null, tapped)
    }

    @Test fun `a long press leaves the next tap working`() {
        show(TopLevel.HOME)
        tab(TopLevel.PLAYBOOK).performTouchInput { longClick() }
        compose.waitForIdle()
        tab(TopLevel.PLAYBOOK).performClick()
        compose.waitForIdle()
        assertEquals(TopLevel.PLAYBOOK, tapped)
    }

    @Test fun `a long press cancelled after the tooltip leaves the next accessibility click working`() {
        show(TopLevel.HOME)
        compose.mainClock.autoAdvance = false
        tab(TopLevel.PLAYBOOK).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        tab(TopLevel.PLAYBOOK).performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        tab(TopLevel.PLAYBOOK).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(TopLevel.PLAYBOOK, tapped)
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
}
