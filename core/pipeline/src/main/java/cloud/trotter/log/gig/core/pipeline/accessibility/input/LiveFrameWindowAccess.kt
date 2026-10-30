package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import timber.log.Timber

/** Android I/O for frame resolution. Handles are passed through, never wrapped or re-fetched to map. */
internal class LiveFrameWindowAccess(private val source: AccessibilitySource) :
    FrameWindowAccess<AccessibilityWindowInfo, AccessibilityNodeInfo> {
    override fun windows() = source.getWindows()
    override fun singleActive(windows: List<AccessibilityWindowInfo>) = source.activeFromEnumeration(windows).single
    override fun nativeRoot() = source.getLiveNativeRoot()
    override fun root(window: AccessibilityWindowInfo) = window.root
    override fun id(window: AccessibilityWindowInfo) = window.id
    override fun layer(window: AccessibilityWindowInfo) = window.layer
    override fun kind(window: AccessibilityWindowInfo) = when (window.type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
        AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
        else -> WindowKind.OTHER
    }
    override fun pip(window: AccessibilityWindowInfo) = window.isInPictureInPictureMode
    override fun bounds(window: AccessibilityWindowInfo): WindowVerdictCache.Bounds {
        val r = Rect()
        window.getBoundsInScreen(r)
        return WindowVerdictCache.Bounds(r.left, r.top, r.right, r.bottom)
    }
    override fun rootWindowId(root: AccessibilityNodeInfo) = root.windowId
    override fun packageName(root: AccessibilityNodeInfo) = root.packageName?.toString()
    override fun ownPackage() = source.ownPackage()

    /** Service metrics only; unknown display admits no overlay. No bounds fallback (BB4/DD5). */
    override fun displayArea(): Long {
        val metrics = try {
            source.getService()?.resources?.displayMetrics
        } catch (_: Exception) {
            null
        }
        if (metrics != null && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        }
        return 0L
    }

    override fun mapNative(root: AccessibilityNodeInfo) = source.getCurrentRootSnapshot(root)
    override fun mapWindow(window: AccessibilityWindowInfo, root: AccessibilityNodeInfo, total: Int) =
        source.getWindowSnapshot(window, root, total)

    override fun traceTopology(windows: List<AccessibilityWindowInfo>, displayArea: Long) {
        Timber.tag("Pipeline").d("\uD83E\uDE9F Window list: %d windows", windows.size)
        windows.forEachIndexed { i, w ->
            // Titles are app-controlled text: diagnostics carry LENGTH only (#1148 G1).
            val areaPct = if (displayArea > 0L) (bounds(w).area() * 100 / displayArea).toInt() else -1
            Timber.tag("Pipeline").d(
                "  [%d] id=%d type=%d layer=%d area%%=%d titleLen=%d active=%s focused=%s",
                i, w.id, w.type, w.layer, areaPct, w.title?.length ?: 0, w.isActive, w.isFocused,
            )
        }
    }
}
