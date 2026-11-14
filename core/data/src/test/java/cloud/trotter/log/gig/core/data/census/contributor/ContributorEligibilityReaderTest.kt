package cloud.trotter.log.gig.core.data.census.contributor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.database.analytics.ContributorActivityDao
import cloud.trotter.log.gig.core.database.analytics.ShiftSpanRow
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** #1625: the reader feeds the rule from the phone's own data and fails closed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ContributorEligibilityReaderTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class XorSealer : KeystoreSealer() {
        override fun seal(bytes: ByteArray) = Sealed(byteArrayOf(1), bytes.map { (it.toInt() xor 85).toByte() }.toByteArray())
        override fun open(iv: ByteArray, ct: ByteArray) = ct.map { (it.toInt() xor 85).toByte() }.toByteArray()
        override fun reset() = Unit
    }

    private val day = 86_400_000L
    private val credentials = CensusCredentialStore(MemoryPreferences(), XorSealer())
    private val source = DevSettingsDataSource(MemoryPreferences())

    private fun spans(now: Long) = listOf(0L, 1L, 2L).map { ShiftSpanRow(now - it * day - 3_600_000L, now - it * day) }

    @Test fun `census on, enrolled a week, three active days and two shifts is eligible`() = runTest {
        val settings = DevSettingsRepository(source, true, StandardTestDispatcher(testScheduler), "0.234.0")
        settings.setCensusUploadEnabled(true)
        credentials.mint()
        credentials.markEnrolled()
        val now = System.currentTimeMillis() + 8 * day
        val dao = mock<ContributorActivityDao> {
            on { shiftsSince(any()) } doReturn spans(now)
            on { endedShiftCount() } doReturn 2
        }
        assertTrue(ContributorEligibilityReader(settings, credentials, dao).eligible(now, ZoneOffset.UTC))
    }

    @Test fun `census off reads nothing else and is not eligible`() = runTest {
        val settings = DevSettingsRepository(source, true, StandardTestDispatcher(testScheduler), "0.234.0")
        val dao = mock<ContributorActivityDao>()
        assertFalse(ContributorEligibilityReader(settings, credentials, dao).eligible(System.currentTimeMillis(), ZoneOffset.UTC))
        verify(dao, never()).endedShiftCount()
    }

    @Test fun `an unreadable read model is not eligible`() = runTest {
        val settings = DevSettingsRepository(source, true, StandardTestDispatcher(testScheduler), "0.234.0")
        settings.setCensusUploadEnabled(true)
        credentials.mint()
        credentials.markEnrolled()
        val dao = mock<ContributorActivityDao> { on { shiftsSince(any()) } doThrow IllegalStateException("db") }
        val now = System.currentTimeMillis() + 8 * day
        assertFalse(ContributorEligibilityReader(settings, credentials, dao).eligible(now, ZoneOffset.UTC))
    }
}
