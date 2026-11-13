package cloud.trotter.log.gig.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * #1620 (ADR-0011 §21): encrypted in transit. Every endpoint the app calls is `https` (the census server, the EIA price
 * API, the EPA vehicle API and any other URL in shipped source), and the app's network security config refuses
 * cleartext for every host, referenced from the manifest every build merges.
 */
class HttpsEndpointGuardTest {

    private val root = RepoRoot.locate()

    /** Every quoted `http://` or `https://` string in a shipped Kotlin or Java source file, with where it is. */
    private fun urlLiterals(): List<Pair<String, String>> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(".") || it == root }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && SHIPPED.containsMatchIn(it.invariantSeparatorsPath) }
        .flatMap { file -> URL.findAll(file.readText()).map { it.groupValues[1] to file.relativeTo(root).invariantSeparatorsPath } }
        .toList()

    @Test fun `every URL in shipped source is https`() {
        val urls = urlLiterals()
        val endpoints = urls.map { it.first }
        // Non-vacuous: the three servers the app calls are found by this scan.
        for (host in listOf("https://census.dashbuddy.trotter.cloud", "https://api.eia.gov/", "https://www.fueleconomy.gov/")) {
            assertTrue("$host is found in shipped source", endpoints.any { it.startsWith(host) })
        }
        val cleartext = urls.filterNot { it.first.startsWith("https://") }
        assertEquals("non-https URL in shipped source: $cleartext", emptyList<Pair<String, String>>(), cleartext)
    }

    @Test fun `the network security config refuses cleartext for every host, from the main manifest`() {
        val config = File(root, "app/src/main/res/xml/network_security_config.xml")
        val doc = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(config)
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals("no host is excepted", 0, doc.getElementsByTagName("domain-config").length)
        assertEquals("no debug override trusts user certificates", 0, doc.getElementsByTagName("debug-overrides").length)

        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
        // No build type ships its own copy of the config or re-enables cleartext in its manifest.
        root.resolve("app/src").listFiles().orEmpty().filter { it.isDirectory && it.name != "main" }.forEach { sourceSet ->
            assertFalse(File(sourceSet, "res/xml/network_security_config.xml").exists())
            val variant = File(sourceSet, "AndroidManifest.xml")
            if (variant.isFile) {
                assertFalse(variant.readText().contains("usesCleartextTraffic"))
                assertFalse(variant.readText().contains("networkSecurityConfig"))
            }
        }
    }

    private companion object {
        /** Only the source sets that ship: `src/main`, `src/debug`, `src/release`, `src/sideload` of any module. */
        val SHIPPED = Regex("/src/(main|debug|release|sideload)/")
        /** A string literal that starts with a web scheme (licence headers and KDoc links are not quoted). */
        val URL = Regex("\"(https?://[^\"]*)\"")
    }
}
