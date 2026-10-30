package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class HudModeResolverTest {
    private fun resolve(flow: Flow, mode: Mode?, session: Boolean = mode != null, card: Boolean, offer: Boolean = false, log: Boolean = false) =
        HudModeResolver.resolve(flow, mode, session, card, offer, log)

    @Test fun `offer presented gives offer even with the log open`() =
        assertEquals(HudMode.OFFER, resolve(Flow.OfferPresented, Mode.Online, card = true, offer = true, log = true))
    @Test fun `offer presented without an offer card stays live`() =
        assertEquals(HudMode.LIVE, resolve(Flow.OfferPresented, Mode.Online, card = false))
    @Test fun `offer presented with another platform's job card stays live`() =
        assertEquals(HudMode.LIVE, resolve(Flow.OfferPresented, Mode.Online, card = true, offer = false))
    @Test fun `offline with no active card gives resting`() {
        assertEquals(HudMode.RESTING, resolve(Flow.Idle, Mode.Offline, card = false))
        assertEquals(HudMode.RESTING, resolve(Flow.Idle, null, card = false))
    }
    @Test fun `a dash ended from paused is resting`() =
        assertEquals(HudMode.RESTING, resolve(Flow.Idle, Mode.Paused, session = false, card = false))
    @Test fun `offline with an active card stays live`() =
        assertEquals(HudMode.LIVE, resolve(Flow.Idle, Mode.Offline, card = true))
    @Test fun `online idle gives live`() = assertEquals(HudMode.LIVE, resolve(Flow.Idle, Mode.Online, card = false))
    @Test fun `log open while live gives log`() = assertEquals(HudMode.LOG, resolve(Flow.Idle, Mode.Online, card = true, log = true))
    @Test fun `paused gives live`() = assertEquals(HudMode.LIVE, resolve(Flow.TaskPickupArrived, Mode.Paused, card = true))
}
