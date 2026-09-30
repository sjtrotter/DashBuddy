package cloud.trotter.dashbuddy.guard

import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #1151 review LL11 — the filtered footprint the listener applies on UNDECIDED / DECLINED
 * (`Platform.watchedPackages`, via `ServiceInfoPolicy`) REPLACES the manifest's
 * `android:packageNames`. Pin the two as equal so "cold-start default == filtered footprint" stays
 * true when a platform is added to one and not the other.
 */
class AccessibilityPackageParityTest {

    @Test
    fun `manifest packageNames equals the watched-package registry`() {
        val xml = File(locateRepoRoot(), "app/src/main/res/xml/accessibility_service_config.xml")
        assertTrue("missing ${xml.path}", xml.isFile)

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(xml).documentElement
        val raw = root.getAttributeNS(ANDROID_NS, "packageNames")
        assertTrue("android:packageNames must be declared (an absent list means every package)", raw.isNotBlank())

        val manifest = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(Platform.watchedPackages, manifest)
    }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: error("Could not locate repo root walking up from ${File(".").absolutePath}")
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
