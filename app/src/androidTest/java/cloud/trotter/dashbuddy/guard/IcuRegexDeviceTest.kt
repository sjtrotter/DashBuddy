package cloud.trotter.dashbuddy.guard

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.trotter.dashbuddy.state.effects.EvidenceFilename
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** ART/ICU counterpart to IcuRegexGuardTest: compile and run the production #909 regex. */
@RunWith(AndroidJUnit4::class)
class IcuRegexDeviceTest {
    @Test
    fun escapedTemplateBracesCompileAndMatchOnDevice() {
        // First access initializes the real Regex("""\{\w*\}""") on the device.
        assertEquals("Offer", EvidenceFilename.sanitizePrefix("Offer - {storeName}"))
        assertEquals("Rule", EvidenceFilename.sanitizePrefix("{}"))
        assertEquals("Offer - {a b}", EvidenceFilename.sanitizePrefix("Offer - {a b}"))
    }
}
