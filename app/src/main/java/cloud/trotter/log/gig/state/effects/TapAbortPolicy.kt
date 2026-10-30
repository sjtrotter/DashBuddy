package cloud.trotter.log.gig.state.effects

/** Facts captured at each existing tap checkpoint; no live nodes or deferred reads. */
sealed interface TapGateInput {
    data class Package(val present: Boolean) : TapGateInput
    data class Roots(val count: Int) : TapGateInput
    data class Search(
        val oversizedQuery: Int,
        val activeBoundsCut: Boolean,
        val inconclusiveHits: Int,
        val candidates: Int,
    ) : TapGateInput
    data class Owners(val staleSemantic: Int, val stale: Int, val targets: Int) : TapGateInput
    data class Verification(
        val semanticUnprovable: Int,
        val staleEvidence: Int,
        val targets: Int,
        val verified: Int,
    ) : TapGateInput
    data class Window(val activeVerified: Int, val cutBoundsWindows: Int) : TapGateInput
    data class Retained(val nested: Boolean, val count: Int, val anySemantic: Boolean) : TapGateInput
    data class Rank(val unresolved: Boolean, val count: Int) : TapGateInput
}

sealed interface TapGateDecision {
    object Continue : TapGateDecision
    data class Abort(val reason: TapAbortReason) : TapGateDecision
}

enum class TapAbortReason {
    NO_PACKAGE,
    NO_ROOTS,
    OVERSIZED,
    ACTIVE_CUT,
    INCONCLUSIVE,
    NO_CANDIDATES,
    STALE_SEMANTIC,
    STALE_OWNERS,
    NO_OWNERS,
    UNPROVABLE,
    STALE_EVIDENCE,
    UNVERIFIED,
    DECIDING_CUT,
    NESTED,
    TWINS,
    TIED,
}

/** Refuse in checkpoint order. Selection, live reads and dispatch belong to the handler. */
fun decide(input: TapGateInput): TapGateDecision {
    val reason = when (input) {
        is TapGateInput.Package -> if (!input.present) TapAbortReason.NO_PACKAGE else null
        is TapGateInput.Roots -> if (input.count == 0) TapAbortReason.NO_ROOTS else null
        is TapGateInput.Search -> when {
            input.oversizedQuery > 0 -> TapAbortReason.OVERSIZED
            input.activeBoundsCut -> TapAbortReason.ACTIVE_CUT
            input.inconclusiveHits > 0 -> TapAbortReason.INCONCLUSIVE
            input.candidates == 0 -> TapAbortReason.NO_CANDIDATES
            else -> null
        }
        is TapGateInput.Owners -> when {
            input.staleSemantic > 0 -> TapAbortReason.STALE_SEMANTIC
            input.stale > 0 && input.targets + input.stale >= 2 -> TapAbortReason.STALE_OWNERS
            input.targets == 0 -> TapAbortReason.NO_OWNERS
            else -> null
        }
        is TapGateInput.Verification -> when {
            input.semanticUnprovable > 0 -> TapAbortReason.UNPROVABLE
            input.staleEvidence > 0 && input.targets >= 2 -> TapAbortReason.STALE_EVIDENCE
            input.verified == 0 -> TapAbortReason.UNVERIFIED
            else -> null
        }
        is TapGateInput.Window -> when {
            input.activeVerified == 0 && input.cutBoundsWindows > 0 -> TapAbortReason.DECIDING_CUT
            else -> null
        }
        is TapGateInput.Retained -> when {
            input.nested -> TapAbortReason.NESTED
            input.count > 1 && input.anySemantic -> TapAbortReason.TWINS
            else -> null
        }
        is TapGateInput.Rank -> when {
            input.unresolved && input.count > 1 -> TapAbortReason.TIED
            else -> null
        }
    }
    return reason?.let { TapGateDecision.Abort(it) } ?: TapGateDecision.Continue
}
