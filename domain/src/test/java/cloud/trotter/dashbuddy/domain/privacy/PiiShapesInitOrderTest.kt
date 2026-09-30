package cloud.trotter.dashbuddy.domain.privacy

import java.io.File
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 review AH6 — `PiiShapes.GATED_NAME_PREFIXES` is touched FIRST on a FRESH class load (an isolated
 * class loader, so this JVM's already-initialized copy cannot mask an init-order defect): the map and the
 * regex its predicate reads must both be built, never a `<clinit>` NPE (the #909 class).
 */
class PiiShapesInitOrderTest {

    private fun location(cls: Class<*>): File =
        File(checkNotNull(cls.protectionDomain?.codeSource?.location) { "no code source for ${cls.name}" }.toURI())

    @Test
    fun `GATED_NAME_PREFIXES works when it is the first member touched`() {
        val urls = arrayOf(location(PiiShapes::class.java), location(Unit::class.java)).map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val cls = loader.loadClass(PiiShapes::class.java.name)
            assertTrue("isolated copy", cls !== PiiShapes::class.java)
            val instance = cls.getField("INSTANCE").get(null)
            val gated = cls.getMethod("getGATED_NAME_PREFIXES").invoke(instance) as Map<*, *>
            // The predicate is the ISOLATED loader's `Function1`: invoke it reflectively.
            val predicate = checkNotNull(gated["Return "])
            val invoke = predicate.javaClass.getMethod("invoke", Any::class.java).apply { isAccessible = true }
            assertEquals(true, invoke.invoke(predicate, "Riley P to the store"))
            assertEquals(false, invoke.invoke(predicate, "to dash"))
        }
    }
}
