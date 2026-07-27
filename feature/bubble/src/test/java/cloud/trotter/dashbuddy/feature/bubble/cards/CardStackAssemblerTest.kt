package cloud.trotter.dashbuddy.feature.bubble.cards

import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.session.CardSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #867 — merged-stack interleave + the ≥2-live platform-chip gate. */
class CardStackAssemblerTest {

    private fun awaiting(id: String, startedAt: Long) = FlowCardSnapshot.Awaiting(
        id = id,
        phaseStartedAt = startedAt,
        phaseEndedAt = startedAt + 1,
    )

    private val ddSource = CardSource("session-dd", Platform.DoorDash)
    private val uberSource = CardSource("session-uber", Platform.Uber)

    @Test
    fun `a single session passes its fold through untouched`() {
        // The fold emits in CLOSE order, which is not start order — a global sort would silently
        // reorder the shipped single-session stack, so the single-source path must not sort.
        val cards = listOf(awaiting("a", 300), awaiting("b", 100), awaiting("c", 200))

        val out = CardStackAssembler.assemble(
            folded = listOf(ddSource to cards),
            activeCard = null,
            activePlatform = Platform.DoorDash,
            labelsVisible = false,
        )

        assertEquals(cards, out.stack.completed)
        assertTrue(out.platformLabels.isEmpty())
    }

    @Test
    fun `two sessions interleave by phase start while keeping each session's order`() {
        val dd = listOf(awaiting("dd1", 100), awaiting("dd2", 400))
        val uber = listOf(awaiting("ub1", 200), awaiting("ub2", 300))

        val out = CardStackAssembler.assemble(
            folded = listOf(ddSource to dd, uberSource to uber),
            activeCard = null,
            activePlatform = Platform.DoorDash,
            labelsVisible = true,
        )

        assertEquals(
            listOf("dd1", "ub1", "ub2", "dd2"),
            out.stack.completed.map { it.id },
        )
    }

    @Test
    fun `a tie keeps the earlier source's card first, deterministically`() {
        val dd = listOf(awaiting("dd1", 100))
        val uber = listOf(awaiting("ub1", 100))

        val out = CardStackAssembler.assemble(
            folded = listOf(ddSource to dd, uberSource to uber),
            activeCard = null,
            activePlatform = null,
            labelsVisible = true,
        )

        assertEquals(listOf("dd1", "ub1"), out.stack.completed.map { it.id })
    }

    @Test
    fun `every merged card is chipped with its own platform`() {
        val out = CardStackAssembler.assemble(
            folded = listOf(
                ddSource to listOf(awaiting("dd1", 100)),
                uberSource to listOf(awaiting("ub1", 200)),
            ),
            activeCard = awaiting("live", 300).copy(phaseEndedAt = null),
            activePlatform = Platform.Uber,
            labelsVisible = true,
        )

        assertEquals(Platform.DoorDash.shortName, out.platformLabels["dd1"])
        assertEquals(Platform.Uber.shortName, out.platformLabels["ub1"])
        // The live card belongs to whichever platform's frame we last saw.
        assertEquals(Platform.Uber.shortName, out.platformLabels["live"])
    }

    @Test
    fun `no chips at all when only one dash is live`() {
        val out = CardStackAssembler.assemble(
            folded = listOf(ddSource to listOf(awaiting("dd1", 100))),
            activeCard = awaiting("live", 300).copy(phaseEndedAt = null),
            activePlatform = Platform.DoorDash,
            labelsVisible = false,
        )

        assertTrue(out.platformLabels.isEmpty())
    }

    @Test
    fun `an unattributable session contributes no chips`() {
        // The post-dash log fallback has no live platform to name.
        val out = CardStackAssembler.assemble(
            folded = listOf(CardSource("session-old", null) to listOf(awaiting("old", 100))),
            activeCard = null,
            activePlatform = null,
            labelsVisible = true,
        )

        assertTrue(out.platformLabels.isEmpty())
    }

    @Test
    fun `the live card still suppresses its frozen twin`() {
        // CardStack.of's #458 at-door de-dup must survive the merge.
        val live = awaiting("dup", 100).copy(phaseEndedAt = null)

        val out = CardStackAssembler.assemble(
            folded = listOf(ddSource to listOf(awaiting("dup", 100), awaiting("other", 50))),
            activeCard = live,
            activePlatform = Platform.DoorDash,
            labelsVisible = false,
        )

        assertEquals(listOf("other"), out.stack.completed.map { it.id })
        assertEquals(live, out.stack.active)
    }
}
