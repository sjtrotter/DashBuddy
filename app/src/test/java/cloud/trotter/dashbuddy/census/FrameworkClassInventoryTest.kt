package cloud.trotter.dashbuddy.census

import cloud.trotter.dashbuddy.core.pipeline.census.FrameworkClasses
import cloud.trotter.dashbuddy.domain.census.contract.ClassNameGrammar
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 reviews AI1, AI2, AK4 — the pinned framework-class inventory (`core/pipeline/src/main/resources/census/
 * framework-classes.txt.gz`, gzip) never goes stale and never exempts a debug-only class. It is REGENERATED
 * from the RELEASE runtime classpath — the jars `:app:censusReleaseClasspath` lists (the `releaseRuntimeClasspath`
 * AndroidX / Material classes jars + the compileSdk `android.jar`; every unit-test task depends on it) — and
 * any diff fails. Regenerate deliberately with
 * `./gradlew :app:testDebugUnitTest --tests "*FrameworkClassInventoryTest*" -DupdateFrameworkClasses=true`
 * and review the resource diff.
 */
class FrameworkClassInventoryTest {

    /** GZIP-compressed (review AI2); diffed on its DECOMPRESSED content. */
    private val resource = File("../core/pipeline/src/main/resources/census/framework-classes.txt.gz")

    /** `:app:censusReleaseClasspath`'s output: one jar path per line. */
    private val releaseClasspath = File("build/census/release-classpath.txt")

    /** Packages that exist only in debug / test builds (review AK4): never in the inventory. */
    private val DEBUG_ONLY_PREFIXES = listOf("androidx.compose.ui.tooling.", "androidx.test.", "leakcanary", "shark.")

    @Test
    fun `the pinned inventory holds no debug-only class`() {
        val pinned = pinned()
        assertTrue("missing ${resource.absolutePath}", pinned.isNotEmpty())
        val debugOnly = pinned.filter { name -> DEBUG_ONLY_PREFIXES.any { name.startsWith(it) } }
        assertEquals(emptyList<String>(), debugOnly)
    }

    private fun pinned(): List<String> =
        if (!resource.isFile) emptyList() else GZIPInputStream(resource.inputStream()).bufferedReader(Charsets.UTF_8).use { r -> r.readLines().filter { it.isNotBlank() } }

    @Test
    fun `the pinned framework-class inventory matches the release classpath`() {
        assertTrue("missing ${releaseClasspath.absolutePath} — :app:censusReleaseClasspath must run first", releaseClasspath.isFile)
        val jars = releaseClasspath.readLines().filter { it.isNotBlank() }.map(::File)
        val generated = inventoryFrom(jars)
        assertTrue("the classpath must carry android.view.View", "android.view.View" in generated)
        assertTrue("the classpath must carry an AndroidX artifact", generated.any { it.startsWith("androidx.recyclerview.") })
        assertTrue("the classpath must carry Material", "com.google.android.material.card.MaterialCardView" in generated)
        val text = generated.joinToString("\n", postfix = "\n")
        if (System.getProperty("updateFrameworkClasses") == "true") {
            resource.parentFile?.mkdirs()
            // GZIPOutputStream writes a zero MTIME, so the bytes are deterministic for a given list.
            GZIPOutputStream(resource.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        assertTrue("missing ${resource.absolutePath} — regenerate with -DupdateFrameworkClasses=true", resource.isFile)
        val pinned = pinned()
        assertEquals("framework-classes.txt.gz is stale — regenerate with -DupdateFrameworkClasses=true", generated.toList(), pinned)
        // The runtime reads the same resource.
        assertTrue(generated.all { it in FrameworkClasses.KNOWN })
    }

    /**
     * The inventoried namespaces (review AI1): the View-bearing SDK packages, and every AndroidX / Material
     * class. Excluded, as never a runtime view class an accessibility node can name: Kotlin file facades
     * (`…Kt`, never instantiated), the Compose Material ICON tables (`androidx.compose.material.icons.` —
     * ~11 000 `ImageVector` holders), the test-only `androidx.test.` artifacts, and every debug-only package
     * (AK4 — `androidx.compose.ui.tooling.` also covers the release-scope `ui-tooling-preview` annotations,
     * which are never a view class).
     */
    private val INVENTORY_PREFIXES = listOf("android.view.", "android.widget.", "android.webkit.", "androidx.", "com.google.android.material.")
    private val EXCLUDED_PREFIXES = listOf("androidx.compose.material.icons.", "androidx.test.") + DEBUG_ONLY_PREFIXES

    private fun inventoryFrom(jars: List<File>): java.util.SortedSet<String> {
        val out = sortedSetOf<String>()
        jars.filter { it.isFile && it.extension == "jar" }
            .forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { e ->
                        val name = e.name.removeSuffix(".class").replace('/', '.')
                        if (INVENTORY_PREFIXES.none { name.startsWith(it) } || EXCLUDED_PREFIXES.any { name.startsWith(it) } ||
                            name.endsWith("package-info") || name.endsWith("Kt")
                        ) {
                            return@forEach
                        }
                        // AK5: only a name the class grammar accepts (the runtime loader rejects the whole
                        // resource on any other; such a class never reaches the wire anyway).
                        if (ClassNameGrammar.isStatic(name) && zip.getInputStream(e).use { isPublicClass(it) }) out += name
                    }
                }
            }
        return out
    }

    /** The class file's own `access_flags`: ACC_PUBLIC and not ACC_SYNTHETIC (constant pool skipped). */
    private fun isPublicClass(stream: InputStream): Boolean {
        val input = DataInputStream(stream.buffered())
        if (input.readInt() != 0xCAFEBABE.toInt()) return false
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        var i = 1
        while (i < count) {
            when (input.readUnsignedByte()) {
                1 -> input.skipNBytes(input.readUnsignedShort().toLong())
                3, 4 -> input.skipNBytes(4)
                5, 6 -> { input.skipNBytes(8); i++ }
                7, 8, 16, 19, 20 -> input.skipNBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                15 -> input.skipNBytes(3)
                else -> return false
            }
            i++
        }
        val flags = input.readUnsignedShort()
        return flags and 0x0001 != 0 && flags and 0x1000 == 0
    }
}
