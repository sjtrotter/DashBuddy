package cloud.trotter.log.gig.state.effects

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JUnit: policy inputs contain only counts and flags, with no Android runtime. */
class TapAbortPolicyTest {
    private fun abort(reason: TapAbortReason, vararg inputs: TapGateInput) {
        inputs.forEach { assertEquals(it.toString(), TapGateDecision.Abort(reason), decide(it)) }
    }

    private fun proceed(vararg inputs: TapGateInput) {
        inputs.forEach { assertEquals(it.toString(), TapGateDecision.Continue, decide(it)) }
    }

    @Test
    fun `every abort reason has a triggering input`() {
        val cases = listOf(
            TapGateInput.Package(false) to TapAbortReason.NO_PACKAGE,
            TapGateInput.Roots(0) to TapAbortReason.NO_ROOTS,
            TapGateInput.Search(1, false, 0, 1) to TapAbortReason.OVERSIZED,
            TapGateInput.Search(0, true, 0, 1) to TapAbortReason.ACTIVE_CUT,
            TapGateInput.Search(0, false, 1, 1) to TapAbortReason.INCONCLUSIVE,
            TapGateInput.Search(0, false, 0, 0) to TapAbortReason.NO_CANDIDATES,
            TapGateInput.Owners(1, 0, 1) to TapAbortReason.STALE_SEMANTIC,
            TapGateInput.Owners(0, 1, 1) to TapAbortReason.STALE_OWNERS,
            TapGateInput.Owners(0, 0, 0) to TapAbortReason.NO_OWNERS,
            TapGateInput.Verification(1, 0, 1, 1) to TapAbortReason.UNPROVABLE,
            TapGateInput.Verification(0, 1, 2, 1) to TapAbortReason.STALE_EVIDENCE,
            TapGateInput.Verification(0, 0, 1, 0) to TapAbortReason.UNVERIFIED,
            TapGateInput.Window(0, 1) to TapAbortReason.DECIDING_CUT,
            TapGateInput.Retained(true, 2, false) to TapAbortReason.NESTED,
            TapGateInput.Retained(false, 2, true) to TapAbortReason.TWINS,
            TapGateInput.Rank(true, 2) to TapAbortReason.TIED,
        )
        assertEquals(TapAbortReason.entries.toSet(), cases.map { it.second }.toSet())
        cases.forEach { (input, reason) -> abort(reason, input) }
    }

    @Test
    fun `every checkpoint has an all-clear case`() {
        proceed(
            TapGateInput.Package(true),
            TapGateInput.Roots(1),
            TapGateInput.Search(0, false, 0, 1),
            TapGateInput.Owners(0, 0, 1),
            TapGateInput.Verification(0, 0, 1, 1),
            TapGateInput.Window(0, 0),
            TapGateInput.Retained(false, 1, false),
            TapGateInput.Rank(false, 1),
        )
    }

    @Test
    fun `search failures keep oversized then active cut then inconclusive then empty precedence`() {
        abort(TapAbortReason.OVERSIZED, TapGateInput.Search(1, true, 1, 0))
        abort(TapAbortReason.ACTIVE_CUT, TapGateInput.Search(0, true, 1, 0))
        abort(TapAbortReason.INCONCLUSIVE, TapGateInput.Search(0, false, 1, 0))
        abort(TapAbortReason.NO_CANDIDATES, TapGateInput.Search(0, false, 0, 0))
    }

    @Test
    fun `owners keep stale semantic then multiple stale owners then empty precedence`() {
        abort(TapAbortReason.STALE_SEMANTIC, TapGateInput.Owners(1, 2, 0))
        abort(TapAbortReason.STALE_OWNERS, TapGateInput.Owners(0, 2, 0))
        abort(TapAbortReason.NO_OWNERS, TapGateInput.Owners(0, 1, 0))
    }

    @Test
    fun `verification keeps unprovable then stale evidence then unverified precedence`() {
        abort(TapAbortReason.UNPROVABLE, TapGateInput.Verification(1, 1, 2, 0))
        abort(TapAbortReason.STALE_EVIDENCE, TapGateInput.Verification(0, 1, 2, 0))
        abort(TapAbortReason.UNVERIFIED, TapGateInput.Verification(0, 1, 1, 0))
    }

    @Test
    fun `nested retained candidates abort before semantic twins`() {
        abort(TapAbortReason.NESTED, TapGateInput.Retained(true, 2, true))
    }

    @Test
    fun `root and search counts distinguish zero from positive counts`() {
        proceed(TapGateInput.Roots(2), TapGateInput.Search(0, false, 0, 2))
        // oversizedQuery is already the search's overflow fact, not a count to compare to the cap here.
        abort(TapAbortReason.OVERSIZED,
            TapGateInput.Search(1, false, 0, 1),
            TapGateInput.Search(65, false, 0, 1),
        )
        abort(TapAbortReason.INCONCLUSIVE, TapGateInput.Search(0, false, 2, 1))
    }

    @Test
    fun `stale owners use the sum of retained and stale targets with a threshold of two`() {
        proceed(TapGateInput.Owners(0, 0, 2), TapGateInput.Owners(0, 0, 3))
        // A lone stale owner does not trip STALE_OWNERS, but no surviving owner still refuses.
        abort(TapAbortReason.NO_OWNERS, TapGateInput.Owners(0, 1, 0))
        abort(TapAbortReason.STALE_OWNERS,
            TapGateInput.Owners(0, 1, 1),
            TapGateInput.Owners(0, 2, 0),
            TapGateInput.Owners(0, 1, 2),
        )
        abort(TapAbortReason.STALE_SEMANTIC, TapGateInput.Owners(2, 0, 1))
    }

    @Test
    fun `stale evidence uses original targets not verified survivors or a sum`() {
        proceed(
            TapGateInput.Verification(0, 0, 2, 2),
            TapGateInput.Verification(0, 1, 1, 1),
            TapGateInput.Verification(0, 2, 1, 1),
        )
        abort(TapAbortReason.STALE_EVIDENCE,
            TapGateInput.Verification(0, 1, 2, 1),
            TapGateInput.Verification(0, 1, 3, 1),
        )
        abort(TapAbortReason.UNVERIFIED,
            TapGateInput.Verification(0, 0, 2, 0),
            TapGateInput.Verification(0, 1, 1, 0),
        )
        abort(TapAbortReason.UNPROVABLE, TapGateInput.Verification(2, 0, 2, 1))
    }

    @Test
    fun `bounds cuts refuse only when there are no active verified candidates`() {
        proceed(TapGateInput.Window(0, 0), TapGateInput.Window(1, 0),
            TapGateInput.Window(1, 1), TapGateInput.Window(2, 2))
        abort(TapAbortReason.DECIDING_CUT, TapGateInput.Window(0, 1), TapGateInput.Window(0, 2))
    }

    @Test
    fun `twins require more than one retained candidate and a semantic survivor`() {
        proceed(TapGateInput.Retained(false, 0, true), TapGateInput.Retained(false, 1, true),
            TapGateInput.Retained(false, 2, false), TapGateInput.Retained(false, 3, false))
        abort(TapAbortReason.TWINS, TapGateInput.Retained(false, 2, true), TapGateInput.Retained(false, 3, true))
        abort(TapAbortReason.NESTED, TapGateInput.Retained(true, 1, false))
    }

    @Test
    fun `ties require an unresolved rank and more than one candidate`() {
        proceed(TapGateInput.Rank(true, 0), TapGateInput.Rank(true, 1),
            TapGateInput.Rank(false, 2), TapGateInput.Rank(false, 3))
        abort(TapAbortReason.TIED, TapGateInput.Rank(true, 2), TapGateInput.Rank(true, 3))
    }
}
