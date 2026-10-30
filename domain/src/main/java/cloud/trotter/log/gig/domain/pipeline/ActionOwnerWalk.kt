package cloud.trotter.log.gig.domain.pipeline

import cloud.trotter.log.gig.domain.model.accessibility.UiNode

/** Node access and identity for the shared bind-time and live action-owner walk. */
interface OwnerShape<N : Any> {
    fun parent(n: N): N?
    fun foreign(n: N): Boolean
    fun takesClick(n: N): Boolean
    fun same(a: N, b: N): Boolean
}

/** Inspect self and at most [NodeRef.MAX_OWNER_WALK] - 1 parents, refusing foreign hops and cycles. */
fun <N : Any> actionOwner(start: N?, s: OwnerShape<N>): N? {
    var node = start
    val seen = ArrayList<N>(4)
    repeat(NodeRef.MAX_OWNER_WALK) { step ->
        val n = node ?: return null
        if (s.foreign(n) || seen.any { s.same(it, n) }) return null
        if (s.takesClick(n)) return n
        seen.add(n)
        if (step + 1 < NodeRef.MAX_OWNER_WALK) node = s.parent(n)
    }
    return null
}

object UiOwnerShape : OwnerShape<UiNode> {
    override fun parent(n: UiNode): UiNode? = n.parent
    override fun foreign(n: UiNode): Boolean = n.foreignPackage
    override fun takesClick(n: UiNode): Boolean = n.takesClick
    // UiNode.equals compares attributes; identical-looking wrappers are still distinct nodes.
    override fun same(a: UiNode, b: UiNode): Boolean = a === b
}
