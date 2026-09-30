package cloud.trotter.dashbuddy.core.pipeline.census

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The framework classes the census class check exempts (ADR-0011 §2; #1160 reviews AF2, AG2). Public so the
 * `:app` corpus guard can pin that the committed corpus renders no unlisted framework-prefixed class.
 */
object FrameworkClasses {
    /** The framework package prefixes the corpus guard audits against [KNOWN] (AG2). */
    val PACKAGES = listOf("android.", "androidx.", "com.google.android.material.")

    /**
     * The KNOWN framework classes the class check exempts (reviews AF2, AG2, AI1), by EXACT binary name:
     * the pinned INVENTORY ([INVENTORY_RESOURCE] — every public class under `android.view.`, `android.widget.`,
     * `android.webkit.`, `androidx.` and `com.google.android.material.` in the SDK `android.jar` and the
     * AndroidX / Material artifacts the build resolves; regenerated and diffed by `:app`'s
     * `FrameworkClassInventoryTest`) ∪ [CORPUS] ∪ the wrapper set (pinned by a test). Any other framework-
     * PREFIXED class is judged like an app class — the prefix is not proof (`androidx.RileyButton` is nulled
     * beside "Riley"). A missing resource shrinks the set, which only withholds MORE (fail closed).
     */
    val KNOWN: Set<String> by lazy { CORPUS + loadInventory() }

    /**
     * The classpath resource holding the generated inventory — one binary name per line, GZIP-compressed
     * (reviews AI1, AI2: the plain list is ~464 KB of APK for a list read once).
     */
    const val INVENTORY_RESOURCE = "/census/framework-classes.txt.gz"

    private fun loadInventory(): Set<String> = parseInventory(FrameworkClasses::class.java.getResourceAsStream(INVENTORY_RESOURCE))

    /**
     * The inventory in [gzipped], or EMPTY when it is missing or corrupt — which only shrinks [KNOWN], so the
     * class check withholds MORE (fail closed). Internal so a test can feed a corrupt stream.
     */
    internal fun parseInventory(gzipped: InputStream?): Set<String> = try {
        gzipped?.let { GZIPInputStream(it) }?.bufferedReader(Charsets.UTF_8)
            ?.useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * Every [PACKAGES] class the committed corpus renders (a corpus guard fails on an unlisted one), the
     * wrapper set, and the Material `Chip` the AG2 vectors name — kept even when the inventory resource is
     * unavailable.
     */
    val CORPUS: Set<String> = setOf(
        "android.appwidget.AppWidgetHostView",
        "android.view.SurfaceView",
        "android.view.TextureView",
        "android.view.View",
        "android.view.ViewGroup",
        "android.webkit.WebView",
        "android.widget.AutoCompleteTextView",
        "android.widget.Button",
        "android.widget.CheckBox",
        "android.widget.CompoundButton",
        "android.widget.EditText",
        "android.widget.FrameLayout",
        "android.widget.GridView",
        "android.widget.HorizontalScrollView",
        "android.widget.Image",
        "android.widget.ImageButton",
        "android.widget.ImageView",
        "android.widget.LinearLayout",
        "android.widget.ListView",
        "android.widget.ProgressBar",
        "android.widget.RadioButton",
        "android.widget.RelativeLayout",
        "android.widget.ScrollView",
        "android.widget.SeekBar",
        "android.widget.Switch",
        "android.widget.TextSwitcher",
        "android.widget.TextView",
        "android.widget.ViewSwitcher",
        "androidx.appcompat.widget.LinearLayoutCompat",
        "androidx.cardview.widget.CardView",
        "androidx.compose.ui.platform.ComposeView",
        "androidx.compose.ui.viewinterop.ViewFactoryHolder",
        "androidx.recyclerview.widget.RecyclerView",
        "androidx.recyclerview.widget.StaggeredGridLayoutManager",
        "androidx.viewpager.widget.ViewPager",
        "com.google.android.material.chip.Chip",
    )
}
