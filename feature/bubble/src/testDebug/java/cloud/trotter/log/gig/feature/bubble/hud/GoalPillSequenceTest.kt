package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.feature.bubble.MutableClock
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalPillSequenceTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(1_700_000_000_000L)
    private var state by mutableStateOf<GoalPillState>(GoalPillState.InProgress("$1", .9f))
    private var key by mutableStateOf<String?>(null)
    private var mark by mutableStateOf<MarkRead>(MarkRead.Loaded(null))
    private val marks = mutableListOf<String>()
    private var taps = 0

    @Before fun setup() { compose.mainClock.autoAdvance = false }
    private fun show(reduced: Boolean = false) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides reduced) {
                    GoalPill(state, celebrateKey = key, onCelebrated = { marks += it }, onClick = { taps++ }, markLoading = mark == MarkRead.Loading)
                }
            }
        }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private fun cross() {
        compose.runOnIdle { state = GoalPillState.Met; key = "today|7900" }
        settle()
    }
    private fun phase(expected: GoalMetPhase) = compose.onNodeWithTag("hud.goal")
        .assert(SemanticsMatcher.expectValue(GoalMetPhaseKey, expected))

    @Test fun sequence_playsPhasesOnFrameClock() {
        show(); cross(); phase(GoalMetPhase.COLLAPSE)
        // A wall-clock jump cannot advance motion.
        clock.millis += 60_000
        phase(GoalMetPhase.COLLAPSE)
        compose.mainClock.advanceTimeBy(300); phase(GoalMetPhase.CLOSE)
        compose.mainClock.advanceTimeBy(400); phase(GoalMetPhase.CHECK)
        compose.mainClock.advanceTimeBy(400); phase(GoalMetPhase.SETTLE)
        compose.mainClock.advanceTimeBy(200); phase(GoalMetPhase.DONE)
    }
    @Test fun sequence_writesMarkOnceAtStart() {
        show(); cross()
        phase(GoalMetPhase.COLLAPSE)
        compose.runOnIdle { assertEquals(listOf("today|7900"), marks) }
        compose.mainClock.advanceTimeBy(1400)
        compose.runOnIdle { assertEquals(1, marks.size) }
    }
    @Test fun sequence_survivesMarkLanding() {
        show(); cross()
        compose.runOnIdle { key = null }; settle()
        compose.mainClock.advanceTimeBy(300); phase(GoalMetPhase.CLOSE)
        compose.mainClock.advanceTimeBy(400); phase(GoalMetPhase.CHECK)
        compose.mainClock.advanceTimeBy(600); phase(GoalMetPhase.DONE)
        assertEquals(1, marks.size)
    }
    @Test fun reducedMotion_jumpsToEndState() {
        show(reduced = true); cross(); phase(GoalMetPhase.DONE)
        assertEquals(listOf("today|7900"), marks)
        compose.onNodeWithText("to go").assertDoesNotExist()
    }
    @Test fun tap_duringSequence_reachesOnClick() {
        show(); cross()
        compose.onNodeWithTag("hud.goal").assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(56f)).performClick()
        settle(); assertEquals(1, taps)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("hud.goal").performClick()
        settle(); assertEquals(2, taps)
    }
    @Test fun leavingMet_midSequence_rendersNewState() {
        show(); cross()
        compose.runOnIdle { state = GoalPillState.InProgress("$79", 0f); key = null }; settle()
        compose.onNodeWithText("$79").assertExists()
        compose.onNodeWithContentDescription("Goal met").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1400)
        compose.onNodeWithText("$79").assertExists()
        assertEquals(1, marks.size)
    }
    @Test fun liveRegion_announcesGoalMetOnce() {
        show(); cross()
        val goal = compose.onNodeWithTag("hud.goal")
        goal.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val description = goal.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        repeat(4) {
            compose.mainClock.advanceTimeBy(350)
            goal.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, description))
            compose.onAllNodesWithContentDescription("Goal met").assertCountEquals(1)
        }
        assertEquals(listOf("Goal met"), description)
    }
    @Test fun `a loading placeholder does not start the sequence at collapse`() {
        val day: LocalDate? = null
        state = GoalPillMapper.map(79.0, day?.let { 80.0 })
        show()
        compose.onNodeWithText(EMPTY_VALUE).assertExists()
        compose.runOnIdle {
            state = GoalPillMapper.map(79.0, 80.0)
            mark = MarkRead.Loaded(null)
            key = GoalCelebrationResolver.resolve(state, HudMode.LIVE, LocalDate.of(2026, 10, 18), 79.0, mark)
        }
        settle()
        phase(GoalMetPhase.CLOSE)
        compose.onNodeWithText(EMPTY_VALUE).assertDoesNotExist()
        compose.onNodeWithText("to go").assertDoesNotExist()
        val frame = compose.onNodeWithTag("hud.goal").fetchSemanticsNode().config[GoalMetFrameKey]
        assertEquals(0f, frame.textAlpha, 0f)
        assertEquals(1f, frame.arcSweep, 0f)
        assertEquals(1, marks.size)
    }
    @Test fun `loading mark shows the outlined state not the filled flag`() {
        state = GoalPillState.Met
        mark = MarkRead.Loading
        show()
        fun outlined(progress: Float) {
            compose.onNodeWithTag("hud.goal").assert(SemanticsMatcher.expectValue(GoalMetFrameKey,
                GoalMetFrame(GoalMetPhase.CLOSE, 0f, 1f, progress, 0f, GoalIcon.OUTLINED_FLAG)))
        }
        outlined(1f)
        compose.mainClock.advanceTimeBy(GoalMetSequence.DURATION_MS.toLong() * 2)
        outlined(1f)
        assertTrue(marks.isEmpty())
        compose.runOnIdle { mark = MarkRead.Loaded(null); key = "today|7900" }; settle()
        phase(GoalMetPhase.CLOSE)
        compose.mainClock.advanceTimeBy(GoalMetSequence.DURATION_MS.toLong())
        phase(GoalMetPhase.DONE)
    }
    @Test fun loadingMark_preservesLastKnownProgress() {
        show()
        compose.runOnIdle { state = GoalPillState.Met; mark = MarkRead.Loading }; settle()
        compose.onNodeWithTag("hud.goal").assert(SemanticsMatcher.expectValue(GoalMetFrameKey,
            GoalMetFrame(GoalMetPhase.CLOSE, 0f, 1f, .9f, 0f, GoalIcon.OUTLINED_FLAG)))
    }
    @Test fun openedAlreadyMet_startsAtClose() {
        state = GoalPillState.Met; key = "today|7900"
        show(); phase(GoalMetPhase.CLOSE)
        compose.onNodeWithText("$0").assertDoesNotExist()
        compose.onNodeWithText("to go").assertDoesNotExist()
        assertEquals(1, marks.size)
    }
}
