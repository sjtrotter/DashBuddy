package cloud.trotter.log.gig.domain.ci

import org.junit.Assert.assertEquals
import org.junit.Test

// #1450 temporary probe: proves a red test reaches the CI gate summary. Reverted in the next commit.
class Ci1450ProbeTest {
    @Test
    fun `probe fails on purpose`() {
        assertEquals("the CI summary shows this message", 1, 2)
    }
}
