package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.domain.model.cards.*
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

class HudModeResolverTest {
    @Test
    fun `offer presented gives offer even with the log open`() {
        assertEquals(HudMode.OFFER, HudModeResolver.resolve(Flow.OfferPresented, Mode.Online, true, true))
    }

    @Test
    fun `offline with no active card gives resting`() {
        assertEquals(HudMode.RESTING, HudModeResolver.resolve(Flow.Idle, Mode.Offline, false, false))
        assertEquals(HudMode.RESTING, HudModeResolver.resolve(Flow.Idle, null, false, false))
    }

    @Test
    fun `offline with an active card stays live`() {
        assertEquals(HudMode.LIVE, HudModeResolver.resolve(Flow.Idle, Mode.Offline, true, false))
    }

    @Test
    fun `online idle gives live`() {
        assertEquals(HudMode.LIVE, HudModeResolver.resolve(Flow.Idle, Mode.Online, false, false))
    }

    @Test
    fun `log open while live gives log`() {
        assertEquals(HudMode.LOG, HudModeResolver.resolve(Flow.Idle, Mode.Online, true, true))
    }

    @Test
    fun `paused gives live`() {
        assertEquals(HudMode.LIVE, HudModeResolver.resolve(Flow.TaskPickupArrived, Mode.Paused, true, false))
    }
}
