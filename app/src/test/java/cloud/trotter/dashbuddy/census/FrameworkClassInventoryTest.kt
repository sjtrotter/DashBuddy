package cloud.trotter.dashbuddy.census

import cloud.trotter.dashbuddy.core.pipeline.census.FrameworkClasses
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 review AI1 — the pinned framework-class inventory (`core/pipeline/src/main/resources/census/
 * framework-classes.txt`) never goes stale: it is REGENERATED here from the jars on this unit-test runtime
 * classpath — the SDK's (mockable) `android.jar` for `android.view.`/`android.widget.`/`android.webkit.`,
 * and every AndroidX / Material artifact `:app` resolves (appcompat, material, recyclerview, cardview,
 * viewpager, the Compose UI artifacts, …) — and any diff fails. Regenerate deliberately with
 * `./gradlew :app:testDebugUnitTest --tests "*FrameworkClassInventoryTest*" -DupdateFrameworkClasses=true`
 * and review the resource diff.
 */
class FrameworkClassInventoryTest {

    private val resource = File("../core/pipeline/src/main/resources/census/framework-classes.txt")

    @Test
    fun `the pinned framework-class inventory matches the classpath`() {
        val generated = inventoryFromClasspath()
        assertTrue("the classpath must carry android.view.View", "android.view.View" in generated)
        assertTrue("the classpath must carry an AndroidX artifact", generated.any { it.startsWith("androidx.recyclerview.") })
        assertTrue("the classpath must carry Material", "com.google.android.material.card.MaterialCardView" in generated)
        val text = generated.joinToString("\n", postfix = "\n")
        if (System.getProperty("updateFrameworkClasses") == "true") {
            resource.parentFile?.mkdirs()
            resource.writeText(text)
        }
        assertTrue("missing ${resource.absolutePath} — regenerate with -DupdateFrameworkClasses=true", resource.isFile)
        val pinned = resource.readLines().filter { it.isNotBlank() }
        assertEquals("framework-classes.txt is stale — regenerate with -DupdateFrameworkClasses=true", generated.toList(), pinned)
        // The runtime reads the same resource.
        assertTrue(generated.all { it in FrameworkClasses.KNOWN })
    }

    /**
     * The inventoried namespaces (review AI1): the View-bearing SDK packages, and every AndroidX / Material
     * class. Excluded, as never a runtime view class an accessibility node can name: Kotlin file facades
     * (`…Kt`, never instantiated), the Compose Material ICON tables (`androidx.compose.material.icons.` —
     * ~11 000 `ImageVector` holders), and the test-only `androidx.test.` artifacts.
     */
    private val INVENTORY_PREFIXES = listOf("android.view.", "android.widget.", "android.webkit.", "androidx.", "com.google.android.material.")
    private val EXCLUDED_PREFIXES = listOf("androidx.compose.material.icons.", "androidx.test.")

    private fun inventoryFromClasspath(): java.util.SortedSet<String> {
        val out = sortedSetOf<String>()
        checkNotNull(System.getProperty("java.class.path")).split(File.pathSeparator).map(::File).filter { it.isFile && it.extension == "jar" }
            .forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { e ->
                        val name = e.name.removeSuffix(".class").replace('/', '.')
                        if (INVENTORY_PREFIXES.none { name.startsWith(it) } || EXCLUDED_PREFIXES.any { name.startsWith(it) } ||
                            name.endsWith("package-info") || name.endsWith("Kt")
                        ) {
                            return@forEach
                        }
                        if (zip.getInputStream(e).use { isPublicClass(it) }) out += name
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
