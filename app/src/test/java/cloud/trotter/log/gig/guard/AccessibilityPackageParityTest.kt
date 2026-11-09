package cloud.trotter.log.gig.guard

import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #1151 review LL11 — the filtered footprint the listener applies on UNDECIDED / DECLINED
 * (`Platform.watchedPackages`, via `ServiceInfoPolicy`) REPLACES the manifest's
 * `android:packageNames`. Pin the two as equal so "cold-start default == filtered footprint" stays
 * true when a platform is added to one and not the other.
 *
 * #170 — the apps gig.log reads are the ones it ships rules for. One set, four places: the generated rules assets
 * (one `<wire>.json` per platform), the registry's bound platforms (`Platform.watchedPackages`), both copies of the
 * service config's `android:packageNames`, and the manifest's `<queries>`. Adding or removing a ruleset without the
 * registry and the config fails here.
 */
class AccessibilityPackageParityTest {

    private val root = RepoRoot.locate()

    @Test
    fun `both service configs' packageNames equal the watched-package registry`() {
        for (path in CONFIGS) {
            val xml = File(root, path)
            assertTrue("missing ${xml.path}", xml.isFile)
            val raw = parse(xml).getAttributeNS(ANDROID_NS, "packageNames")
            assertTrue("$path: android:packageNames must be declared (an absent list means every package)", raw.isNotBlank())
            val declared = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            assertEquals(path, Platform.watchedPackages, declared)
        }
    }

    @Test
    fun `the manifest's queries equal the watched-package registry`() {
        val manifest = parse(File(root, "app/src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries")
        assertEquals("one <queries> block", 1, queries.length)
        val packages = (queries.item(0) as Element).getElementsByTagName("package")
        val declared = (0 until packages.length)
            .map { (packages.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
            .toSet()
        assertEquals(Platform.watchedPackages, declared)
    }

    @Test
    fun `the bound platforms are exactly the platforms with a generated rules asset`() {
        val assets = File(TestRulesetFactory.rulesDir).listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no generated rules assets in ${TestRulesetFactory.rulesDir}", assets.isNotEmpty())
        val withRules = assets.map { file ->
            val platform = Platform.fromWire(file.nameWithoutExtension)
            assertNotNull("rules asset ${file.name} names no registry platform", platform)
            platform!!
        }.toSet()
        assertEquals(withRules, Platform.boundPlatforms.toSet())
        assertEquals(withRules.mapNotNull { it.packageName }.toSet(), Platform.watchedPackages)
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val CONFIGS = listOf(
            "app/src/main/res/xml/accessibility_service_config.xml",
            "app/src/accessibilityTool/res/xml/accessibility_service_config.xml",
        )
    }
}
