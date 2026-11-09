package cloud.trotter.log.gig.core.datastore.diagnostics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #1560 — the one persisted value of a recognition incident: the last notice time per platform wire.
 * A value that does not decode must throw: the owner (`RecognitionHealthMonitor`) catches it, logs it and
 * fails open, so the failure is visible instead of reading as "never notified".
 */
class RecognitionNoticeDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = stringPreferencesKey("last_notice_at_json")

    private fun TestScope.store(name: String) = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tmp.root, "$name.preferences_pb") },
    )

    @Test
    fun `a saved map loads back unchanged`() = runTest {
        val source = RecognitionNoticeDataSource(store("recognition_notices"))
        source.save(mapOf("doordash" to 1_000L, "uber" to 2_000L))
        assertEquals(mapOf("doordash" to 1_000L, "uber" to 2_000L), source.load())
    }

    @Test
    fun `an empty store loads an empty map`() = runTest {
        assertEquals(emptyMap<String, Long>(), RecognitionNoticeDataSource(store("recognition_notices")).load())
    }

    @Test
    fun `a value that is not json throws`() = runTest {
        val ds = store("recognition_notices")
        ds.edit { it[key] = "{not json" }
        assertLoadThrows(RecognitionNoticeDataSource(ds))
    }

    @Test
    fun `a non-number time throws`() = runTest {
        val ds = store("recognition_notices")
        ds.edit { it[key] = """{"doordash":"soon"}""" }
        assertLoadThrows(RecognitionNoticeDataSource(ds))
    }

    private suspend fun assertLoadThrows(source: RecognitionNoticeDataSource) {
        try {
            source.load()
            fail("a stored value that does not decode must throw; the owner fails open")
        } catch (_: Exception) {
            // expected
        }
    }
}
