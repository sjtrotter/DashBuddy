package cloud.trotter.log.gig.core.pipeline.accessibility.input

/** Framework handles cross this port unchanged; no per-window adapters or retained roots. */
internal interface FrameWindowAccess<W : Any, R : Any> {
    fun windows(): List<W>
    fun singleActive(windows: List<W>): W?
    fun nativeRoot(): R?
    fun root(window: W): R?
    fun id(window: W): Int
    fun layer(window: W): Int
    fun kind(window: W): WindowKind
    fun pip(window: W): Boolean
    fun bounds(window: W): WindowVerdictCache.Bounds
    fun rootWindowId(root: R): Int
    fun packageName(root: R): String?
    fun ownPackage(): String?
    fun displayArea(): Long
    fun mapNative(root: R): AccessibilitySource.RootSnapshot?
    fun mapWindow(window: W, root: R, total: Int): AccessibilitySource.RootSnapshot?
    fun traceTopology(windows: List<W>, displayArea: Long)
}

internal enum class WindowKind { APPLICATION, SYSTEM, OTHER }
