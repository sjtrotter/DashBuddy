package cloud.trotter.log.gig.domain.census.contributor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1625: the invitation rule: after a shift, once until answered, Not now for 14 days, No thanks until Settings. */
class ContributorInvitationTest {
    private val now = 1_794_600_000_000L
    private val day = 86_400_000L
    private val today = LocalDate.of(2026, 11, 13)
    private val grant = ContributorGrant("id-1", "2026-11-01", "2026-12-01", now - 12 * day)

    private fun notify(record: ContributorRecord, eligible: Boolean = true, shiftLive: Boolean = false, at: Long = now) =
        ContributorInvitation.shouldNotify(record, eligible, shiftLive, at, today)

    @Test fun `an eligible phone with no answer is invited`() = assertTrue(notify(ContributorRecord()))

    @Test fun `never while a shift runs`() {
        assertFalse(notify(ContributorRecord(), shiftLive = true))
        assertEquals(
            ContributorEntry.HIDDEN,
            ContributorInvitation.entry(ContributorRecord(invitedAtMillis = now), true, true, shiftLive = true, now, today),
        )
    }

    @Test fun `never when not eligible`() = assertFalse(notify(ContributorRecord(), eligible = false))

    @Test fun `once offered it does not notify again until answered`() {
        val offered = ContributorRecord(invitedAtMillis = now - day)
        assertFalse(notify(offered))
        assertTrue(ContributorInvitation.open(offered, true, false, now, today))
        assertEquals(ContributorEntry.INVITED, ContributorInvitation.entry(offered, true, true, false, now, today))
    }

    @Test fun `Not now holds for 14 days`() {
        val answered = ContributorRecord().answered(InvitationAnswer.NOT_NOW, now)
        assertNull(answered.invitedAtMillis)
        assertFalse(notify(answered, at = now + 14 * day - 1))
        assertTrue(notify(answered, at = now + 14 * day))
    }

    @Test fun `No thanks holds until the dasher accepts`() {
        val answered = ContributorRecord().answered(InvitationAnswer.NO_THANKS, now)
        assertFalse(notify(answered, at = now + 365 * day))
        assertEquals(ContributorEntry.OFF, ContributorInvitation.entry(answered, true, true, false, now, today))
    }

    @Test fun `a live grant is not invited, an expired one asks again`() {
        val granted = ContributorRecord().granted(grant, now)
        assertFalse(notify(granted))
        assertEquals(ContributorEntry.ON, ContributorInvitation.entry(granted, true, true, false, now, today))
        // On its expiry day the grant no longer counts, before anything ends it.
        assertTrue(notify(granted.copy(grant = grant.copy(expiresDay = "2026-11-13"))))
        val ended = granted.ended(ContributorEnd.EXPIRED, now)
        assertNull(ended.grant)
        assertTrue(notify(ended))
    }

    @Test fun `a grant is live before its expiry day and not on it`() {
        assertTrue(grant.liveOn(LocalDate.of(2026, 11, 30)))
        assertFalse(grant.liveOn(LocalDate.of(2026, 12, 1)))
        assertFalse(grant.copy(expiresDay = "not a day").liveOn(today))
    }

    @Test fun `Stop is a No thanks, other ends are not`() {
        val granted = ContributorRecord().granted(grant, now)
        assertEquals(InvitationAnswer.NO_THANKS, granted.ended(ContributorEnd.STOPPED, now).answer)
        assertEquals(InvitationAnswer.NONE, granted.ended(ContributorEnd.DATA_DELETED, now).answer)
        assertEquals(InvitationAnswer.NONE, granted.ended(ContributorEnd.CENSUS_OFF, now).answer)
    }

    @Test fun `a revoked grant is not invited again, a too-new refusal asks again after 2 days`() {
        val revoked = ContributorRecord().granted(grant, now).refused(GrantRefusal.GRANT_REVOKED, now)
        assertNull(revoked.grant)
        assertEquals(ContributorEnd.REVOKED, revoked.ended)
        assertFalse(notify(revoked, at = now + 100 * day))
        val tooNew = ContributorRecord().refused(GrantRefusal.INSTALL_TOO_NEW, now)
        assertFalse(notify(tooNew, at = now + 2 * day - 1))
        assertTrue(notify(tooNew, at = now + 2 * day))
    }

    @Test fun `with the census off there is no row unless a grant is live`() {
        assertEquals(
            ContributorEntry.HIDDEN,
            ContributorInvitation.entry(ContributorRecord().answered(InvitationAnswer.NO_THANKS, now), false, false, false, now, today),
        )
    }
}
