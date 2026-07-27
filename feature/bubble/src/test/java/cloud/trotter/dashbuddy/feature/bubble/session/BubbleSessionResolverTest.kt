package cloud.trotter.dashbuddy.feature.bubble.session

import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #867 — the bubble's session-presentation semantics. The field defect: with two dashes live, the
 * card stack and chat silently swapped platforms mid-glance (a DoorDash "Declined" chip read as the
 * Uber offer the dasher had just acted on).
 */
class BubbleSessionResolverTest {

    private val dd = LiveSession(Platform.DoorDash, "session-dd", startedAt = 1_000L)
    private val uber = LiveSession(Platform.Uber, "session-uber", startedAt = 2_000L)

    private fun resolve(
        mode: BubbleSessionMode = BubbleSessionMode.FOLLOW_ACTIVE,
        selection: Platform? = null,
        follow: Platform? = Platform.DoorDash,
        live: List<LiveSession> = listOf(dd, uber),
        fallback: String? = "session-old",
    ) = BubbleSessionResolver.resolve(mode, selection, follow, live, fallback)

    // --- follow-active (the shipped default) ---

    @Test
    fun `follow-active with no pick shows the last-active platform's dash`() {
        val p = resolve(follow = Platform.Uber)

        assertEquals(Platform.Uber, p.displayedPlatform)
        assertEquals("session-uber", p.chatSessionId)
        assertEquals(listOf(CardSource("session-uber", Platform.Uber)), p.cardSources)
        assertTrue(p.showLiveCard)
    }

    @Test
    fun `follow-active flips with the active platform`() {
        assertEquals("session-dd", resolve(follow = Platform.DoorDash).chatSessionId)
        assertEquals("session-uber", resolve(follow = Platform.Uber).chatSessionId)
    }

    @Test
    fun `a pick overrides the active platform — the surface stops swapping`() {
        val p = resolve(follow = Platform.DoorDash, selection = Platform.Uber)

        assertEquals(Platform.Uber, p.displayedPlatform)
        assertEquals("session-uber", p.chatSessionId)
        // The live card describes the ACTIVE platform's screen — never dress DoorDash's frame up
        // as Uber's live card.
        assertFalse(p.showLiveCard)
    }

    @Test
    fun `a pick for a platform that is not dashing does not apply`() {
        val p = resolve(follow = Platform.DoorDash, selection = Platform.Uber, live = listOf(dd))

        assertEquals(Platform.DoorDash, p.displayedPlatform)
        assertEquals("session-dd", p.chatSessionId)
        assertTrue(p.showLiveCard)
    }

    // --- pinned ---

    @Test
    fun `pinned honors the pick exactly like a temporary pin while both dash`() {
        val p = resolve(
            mode = BubbleSessionMode.PINNED,
            follow = Platform.DoorDash,
            selection = Platform.Uber,
        )

        assertEquals(Platform.Uber, p.displayedPlatform)
        assertEquals("session-uber", p.chatSessionId)
    }

    @Test
    fun `pinned falls back to follow while the pinned platform is dark`() {
        // The caller KEEPS the pin in this mode (that's the durability); the resolver simply has
        // nothing live to show for it, so the display follows rather than blanking.
        val p = resolve(
            mode = BubbleSessionMode.PINNED,
            follow = Platform.DoorDash,
            selection = Platform.Uber,
            live = listOf(dd),
        )

        assertEquals(Platform.DoorDash, p.displayedPlatform)
        assertEquals("session-dd", p.chatSessionId)
    }

    // --- merged ---

    @Test
    fun `merged folds every live session and ignores the pick`() {
        val p = resolve(
            mode = BubbleSessionMode.MERGED,
            follow = Platform.DoorDash,
            selection = Platform.Uber,
        )

        assertEquals(
            listOf(
                CardSource("session-dd", Platform.DoorDash),
                CardSource("session-uber", Platform.Uber),
            ),
            p.cardSources,
        )
        // Chat is NOT merged — it stays on the follow-active session, badged in the header.
        assertEquals("session-dd", p.chatSessionId)
        assertEquals(Platform.DoorDash, p.displayedPlatform)
        assertTrue(p.showLiveCard)
        // A switcher pick would do nothing in this mode, so the row is hidden.
        assertTrue(p.switchablePlatforms.isEmpty())
    }

    @Test
    fun `merged with nothing live still shows the fallback dash`() {
        val p = resolve(mode = BubbleSessionMode.MERGED, follow = null, live = emptyList())

        assertEquals(listOf(CardSource("session-old", null)), p.cardSources)
        assertEquals("session-old", p.chatSessionId)
    }

    // --- the labeling pledge + the switcher gate ---

    @Test
    fun `labels are on in every mode once two dashes are live`() {
        BubbleSessionMode.entries.forEach { mode ->
            assertTrue(mode.name, resolve(mode = mode).labelsVisible)
        }
    }

    @Test
    fun `labels and the switcher are off with a single live dash`() {
        val p = resolve(live = listOf(dd))

        assertFalse(p.labelsVisible)
        assertTrue(p.switchablePlatforms.isEmpty())
    }

    @Test
    fun `the switcher offers every live platform in the caller's order`() {
        assertEquals(listOf(Platform.DoorDash, Platform.Uber), resolve().switchablePlatforms)
    }

    // --- the pre-#867 fallbacks are preserved ---

    @Test
    fun `nothing live falls back to the durable most-recent dash`() {
        val p = resolve(follow = Platform.DoorDash, live = emptyList())

        assertEquals("session-old", p.chatSessionId)
        // No live session ⇒ no platform to attribute the fallback dash to.
        assertEquals(listOf(CardSource("session-old", null)), p.cardSources)
    }

    @Test
    fun `a live dash on another platform beats the log fallback`() {
        // AppState.activeSessionId()'s second arm: the active platform has no session, but some
        // platform is dashing — show that one, not the finished dash from the log.
        val p = resolve(follow = Platform.Instacart, live = listOf(uber))

        assertEquals("session-uber", p.chatSessionId)
    }

    @Test
    fun `no dash and no history shows nothing at all`() {
        val p = resolve(follow = null, live = emptyList(), fallback = null)

        assertNull(p.chatSessionId)
        assertTrue(p.cardSources.isEmpty())
        assertFalse(p.labelsVisible)
    }
}
