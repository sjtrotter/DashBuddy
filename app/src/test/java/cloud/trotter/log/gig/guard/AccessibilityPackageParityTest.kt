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
 * (`Platform.readablePackages`, via `ServiceInfoPolicy`) REPLACES the service config's
 * `android:packageNames`. Pin the two as equal so "cold-start default == filtered footprint" stays
 * true when a platform is added to one and not the other.
 *
 * #170 — per build, one set in three places: `Platform.readablePackages(row.readsUnboundPlatforms)`, the
 * `packageNames` of the service config that build merges, and its merged `<queries>` (main's plus the build type's
 * manifest). Release and sideload read the bound platforms; debug reads every registry platform with a package.
 * The bound platforms are exactly the platforms with a generated rules asset, so adding or removing a ruleset
 * without the registry and the configs fails here.
 */
class AccessibilityPackageParityTest {

    private val root = RepoRoot.locate()
    private val builds = BuildTable.rows(root)

    @Test
    fun `the build table holds the three builds and only debug reads the unbound platforms`() {
        assertEquals(setOf("debug", "release", "sideload"), builds.keys)
        assertEquals(setOf("debug"), builds.filterValues { it.readsUnboundPlatforms }.keys)
    }

    @Test
    fun `each build's service config packageNames equals its readable registry`() {
        for ((build, row) in builds) {
            val path = BuildTable.serviceConfigPath(root, build, row)
            val raw = parse(File(root, path)).getAttributeNS(ANDROID_NS, "packageNames")
            assertTrue("$path: android:packageNames must be declared (an absent list means every package)", raw.isNotBlank())
            val declared = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            assertEquals("$build ($path)", Platform.readablePackages(row.readsUnboundPlatforms), declared)
        }
    }

    @Test
    fun `each build's merged queries equal its readable registry`() {
        val main = queries("app/src/main/AndroidManifest.xml")
        for ((build, row) in builds) {
            val merged = main + queries("app/src/$build/AndroidManifest.xml")
            assertEquals(build, Platform.readablePackages(row.readsUnboundPlatforms), merged)
        }
    }

    @Test
    fun `release and sideload read the bound platforms, debug every platform with a package`() {
        assertEquals(Platform.watchedPackages, Platform.readablePackages(includeUnbound = false))
        assertEquals(
            Platform.entries.mapNotNull { it.packageName }.toSet(),
            Platform.readablePackages(includeUnbound = true),
        )
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

    /** The `<queries>` packages of a manifest; none when the file or the block is absent. */
    private fun queries(path: String): Set<String> {
        val file = File(root, path)
        if (!file.isFile) return emptySet()
        val blocks = parse(file).getElementsByTagName("queries")
        return (0 until blocks.length).flatMap { b ->
            val packages = (blocks.item(b) as Element).getElementsByTagName("package")
            (0 until packages.length).map { (packages.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }.toSet()
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
