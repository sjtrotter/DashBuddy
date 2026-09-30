package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.domain.census.contract.ClassNameGrammar
import cloud.trotter.dashbuddy.domain.util.sha256OrNull
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The framework classes the census class check exempts (ADR-0011 §2; #1160 reviews AF2, AG2). Public so the
 * `:app` corpus guard can pin that the committed corpus renders no unlisted framework-prefixed class.
 */
object FrameworkClasses {
    /** The framework package prefixes the corpus guard audits against [KNOWN] (AG2). */
    val PACKAGES = listOf("android.", "androidx.", "com.google.android.material.")

    /**
     * The KNOWN framework classes the class check exempts (reviews AF2, AG2, AI1, AL2), by EXACT binary name:
     * the pinned INVENTORY ([INVENTORY_RESOURCE] — every public `android.view.View` SUBCLASS under
     * `android.view.`, `android.widget.`, `android.webkit.`, `androidx.` and `com.google.android.material.`
     * in the compileSdk `android.jar` and the RELEASE runtime classpath; regenerated and diffed by `:app`'s
     * `FrameworkClassInventoryTest`) ∪ [CORPUS] ∪ the wrapper set (pinned by a test). Any other framework-
     * PREFIXED class is judged like an app class — the prefix is not proof (`androidx.RileyButton` is nulled
     * beside "Riley"). A missing or corrupt resource shrinks the set, which only withholds MORE (fail closed).
     */
    val KNOWN: Set<String> by lazy { CORPUS + loadInventory() }

    /**
     * The classpath resource holding the generated inventory (review AL2: View subclasses only, ~10 KB, so
     * plain text — no gzip): a `#sha256=<hex>` header over the body, then one binary name per line.
     */
    const val INVENTORY_RESOURCE = "/census/framework-classes.txt"

    /** The header prefix; the hex is the sha256 of every byte after the header line (review AL2). */
    const val INVENTORY_HEADER = "#sha256="

    private fun loadInventory(): Set<String> = parseInventory { FrameworkClasses::class.java.getResourceAsStream(INVENTORY_RESOURCE) }

    /** [parseInventory] over an already-open [stream] (the test seam). */
    internal fun parseInventory(stream: InputStream?): Set<String> = parseInventory { stream }

    /**
     * The inventory opened by [open], or EMPTY when it is missing or corrupt — which only shrinks [KNOWN], so the
     * class check withholds MORE (fail closed). Reviews AK5, AL2: strict — the size is bounded
     * ([MAX_INVENTORY_BYTES]), the bytes decode as UTF-8 with `CodingErrorAction.REPORT` (a malformed byte is
     * corruption, never a U+FFFD entry), the header's sha256 must match the body (a TRUNCATED list fails it),
     * and EVERY entry must pass `ClassNameGrammar`; any failure — including a mid-stream exception —
     * discards the whole resource. Internal so a test can feed corrupt streams. Review AN2 (Astra, round 17):
     * the resource OPEN runs inside this same catch — an opener that throws must degrade like a reader that does.
     */
    internal fun parseInventory(open: () -> InputStream?): Set<String> = try {
        val stream = open()
        if (stream == null) {
            emptySet()
        } else {
            val bytes = stream.use { readBounded(it, MAX_INVENTORY_BYTES + 1) }
            if (bytes.size > MAX_INVENTORY_BYTES) {
                emptySet()
            } else {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                val header = text.substringBefore('\n')
                val body = text.substringAfter('\n', missingDelimiterValue = "")
                val entries = body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                val intact = header.startsWith(INVENTORY_HEADER) && header.removePrefix(INVENTORY_HEADER) == sha256OrNull(body)
                if (intact && entries.all { ClassNameGrammar.isStatic(it) }) entries.toSet() else emptySet()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ThreadDeath) {
        throw e
    } catch (_: Throwable) {
        // Review AM1: ANY loader failure — an Error included (a `NoSuchMethodError` on an older API level) —
        // degrades to an empty inventory, so `KNOWN` falls back to `CORPUS` and never fails a frame.
        emptySet()
    }

    /**
     * Up to [limit] bytes of [input] via the API-1 `read(byte[], off, len)` loop (review AM1: `readNBytes` is
     * API 33 and minSdk is 30).
     */
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, total, limit - total)
            if (n < 0) break
            // Review AN1 (Astra, round 17): a zero-length read with `len > 0` violates the InputStream contract;
            // treating it as EOF could accept a valid prefix, and looping would spin — so it is corruption.
            if (n == 0) throw IOException("zero-length read from the inventory stream")
            total += n
        }
        return buffer.copyOf(total)
    }

    /** The inventory's size bound (reviews AK5, AL2): far above the ~10 KB list. */
    internal const val MAX_INVENTORY_BYTES = 1024 * 1024

    /** The resource text for [entries] — header + body (the ONE writer the generator uses, review AL2). */
    fun inventoryText(entries: Collection<String>): String {
        val body = entries.sorted().joinToString("\n", postfix = "\n")
        return INVENTORY_HEADER + checkNotNull(sha256OrNull(body)) + "\n" + body
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
