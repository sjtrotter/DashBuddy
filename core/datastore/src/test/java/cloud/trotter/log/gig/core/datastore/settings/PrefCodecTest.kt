package cloud.trotter.log.gig.core.datastore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefCodecTest {
    @Test
    fun `scalar codecs preserve native values`() {
        assertScalar(true)
        assertScalar(false)
        assertScalar(150.25)
        assertScalar(0.0)
        assertScalar("es")
        assertScalar("")
    }

    @Test
    fun `optional codecs preserve native values and encode null for removal`() {
        assertOptional(true)
        assertOptional(false)
        assertOptional(150.25)
        assertOptional(0.0)
        assertOptional("es")
        assertOptional("")
    }

    private fun <S : Any> assertScalar(value: S) {
        val codec = scalar<S>()
        assertEquals(value, codec.decode(value))
        assertEquals(value, codec.encode(value))
    }

    private fun <S : Any> assertOptional(value: S) {
        val codec = optional<S>()
        assertEquals(value, codec.decode(value))
        assertEquals(value, codec.encode(value))
        assertNull(codec.encode(null))
    }
}
