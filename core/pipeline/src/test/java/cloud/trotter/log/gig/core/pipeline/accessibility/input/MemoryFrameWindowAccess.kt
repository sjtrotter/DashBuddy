package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform

/** Scalar handles and scripted I/O; no Android classes, mocks, or Robolectric. */
internal data class MemoryWindow(
    val id: Int,
    val layer: Int,
    val kind: WindowKind = WindowKind.APPLICATION,
    val active: Boolean = false,
    val pip: Boolean = false,
    val bounds: WindowVerdictCache.Bounds = WindowVerdictCache.Bounds(0, 0, 100, 100),
)

internal data class MemoryRoot(val packageName: String?, val windowId: Int, val text: String = "root")

internal class MemoryFrameWindowAccess : FrameWindowAccess<MemoryWindow, MemoryRoot> {
    sealed interface Call {
        data object Enumerate : Call
        data class FetchRoot(val id: Int) : Call
        data object NativeRoot : Call
        data object DisplayRead : Call
    }

    val calls = mutableListOf<Call>()
    val mappedRoots = mutableListOf<MemoryRoot>()
    var windowList: List<MemoryWindow> = emptyList()
    var native: MemoryRoot? = null
    var own: String? = OWN
    var onEnumerate: () -> Unit = {}
    var onRoot: (Int) -> Unit = {}
    var display: () -> Long = { 10_000L }
    var mappingFails = false
    var onTrace: () -> Unit = {}
    private val roots = mutableMapOf<Int, ArrayDeque<() -> MemoryRoot?>>()
    private val natives = ArrayDeque<() -> MemoryRoot?>()

    /** Repeat the last scripted answer, like a live window that retains its root. */
    fun scriptRoots(id: Int, vararg answers: () -> MemoryRoot?) {
        roots[id] = ArrayDeque(answers.toList())
    }
    fun scriptNative(vararg answers: () -> MemoryRoot?) {
        natives.clear()
        natives.addAll(answers)
    }
    fun add(window: MemoryWindow, root: MemoryRoot?): MemoryWindow {
        windowList = windowList + window
        scriptRoots(window.id, { root })
        return window
    }
    private fun ArrayDeque<() -> MemoryRoot?>.next(): MemoryRoot? =
        if (isEmpty()) null else (if (size > 1) removeFirst() else first()).invoke()

    override fun windows(): List<MemoryWindow> {
        calls += Call.Enumerate
        val enumerated = windowList
        onEnumerate()
        return enumerated
    }
    override fun singleActive(windows: List<MemoryWindow>) = windows.filter { it.active }.singleOrNull()
    override fun nativeRoot(): MemoryRoot? {
        calls += Call.NativeRoot
        return if (natives.isEmpty()) native else natives.next()
    }
    override fun root(window: MemoryWindow): MemoryRoot? {
        calls += Call.FetchRoot(window.id)
        onRoot(window.id)
        return roots[window.id]?.next()
    }
    override fun id(window: MemoryWindow) = window.id
    override fun layer(window: MemoryWindow) = window.layer
    override fun kind(window: MemoryWindow) = window.kind
    override fun pip(window: MemoryWindow) = window.pip
    override fun bounds(window: MemoryWindow) = window.bounds
    override fun rootWindowId(root: MemoryRoot) = root.windowId
    override fun packageName(root: MemoryRoot) = root.packageName
    override fun ownPackage() = own
    override fun displayArea(): Long {
        calls += Call.DisplayRead
        return display()
    }
    override fun mapNative(root: MemoryRoot) = snapshot(root, null)
    override fun mapWindow(window: MemoryWindow, root: MemoryRoot, total: Int) = snapshot(
        root,
        TreeSnapshot.WindowContext(
            window.id,
            when (window.kind) { WindowKind.APPLICATION -> 1; WindowKind.SYSTEM -> 3; WindowKind.OTHER -> 4 },
            null, window.layer, window.active, false, total,
        ),
    )
    private fun snapshot(root: MemoryRoot, context: TreeSnapshot.WindowContext?): AccessibilitySource.RootSnapshot? {
        mappedRoots += root
        if (mappingFails) return null
        return AccessibilitySource.RootSnapshot(UiNode(text = root.text), root.packageName, context)
    }
    override fun traceTopology(windows: List<MemoryWindow>, displayArea: Long) = onTrace()

    companion object {
        const val OWN = "cloud.trotter.log.gig"
        const val OTHER = "example.foreign"
        val OVERLAY = Platform.overlayPackages.first()
        val APP = Platform.watchedPackages.first { it !in Platform.overlayPackages }
        val ENABLED = setOf(APP, OVERLAY)
    }
}

internal class MemoryFrames {
    val access = MemoryFrameWindowAccess()
    val stats = PipelineStats()
    val cache = WindowVerdictCache()
    val resolver = FrameWindowResolver(access, stats, cache)
    fun event(enabled: Set<String> = MemoryFrameWindowAccess.ENABLED) = resolver.event(99, "trigger") { it in enabled }
    fun topology(enabled: Set<String> = MemoryFrameWindowAccess.ENABLED) = resolver.topology { enabled }
}
