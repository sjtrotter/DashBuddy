package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.census.contract.auth.InstallIdGrammar
import cloud.trotter.census.contract.auth.InstallSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.UnrecoverableKeyException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CensusCredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSealer : KeystoreSealer() {
        var unusable = false
        override fun seal(bytes: ByteArray): Sealed = Sealed(byteArrayOf(1), bytes.map { (it.toInt() xor 85).toByte() }.toByteArray())
        override fun open(iv: ByteArray, ct: ByteArray): ByteArray {
            if (unusable) throw UnrecoverableKeyException()
            return ct.map { (it.toInt() xor 85).toByte() }.toByteArray()
        }
        override fun reset() { unusable = false }
    }

    @Test fun `mint is canonical pending roundtrips and tostring redacts`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "credentials.preferences_pb") }
            val sealer = FakeSealer()
            val store = CensusCredentialStore(ds, sealer)
            assertNull(store.current())
            val minted = store.mint()
            assertTrue(InstallIdGrammar.isCanonicalV4(minted.installId))
            assertTrue(InstallSecret.isValid(minted.secret))
            assertFalse(minted.enrolled)
            assertFalse(minted.toString().contains(minted.installId))
            assertFalse(minted.toString().contains(minted.secret))
            assertTrue(minted.toString().contains(minted.installId.take(8)))
            assertNull(store.current())
            assertEquals(minted, CensusCredentialStore(ds, sealer).pending())
            assertNull(store.installIdPrefix.first())
            assertFalse(ds.data.first().asMap().values.any { it == minted.secret })
            store.markEnrolled()
            assertEquals(minted.copy(enrolled = true), CensusCredentialStore(ds, sealer).current())
            assertEquals(minted.installId.take(8), store.installIdPrefix.first())
            store.wipe()
            assertNull(store.current())
            assertNull(store.pending())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `unusable key reports explicitly and remint produces fresh identity`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "unusable.preferences_pb") }
            val sealer = FakeSealer()
            val store = CensusCredentialStore(ds, sealer)
            val old = store.mint()
            store.markEnrolled()
            sealer.unusable = true
            var thrown: Exception? = null
            try { store.current() } catch (e: CensusCredentialStore.Unusable) { thrown = e }
            assertTrue(thrown is CensusCredentialStore.Unusable)
            val fresh = store.mint()
            assertNotEquals(old.installId, fresh.installId)
            assertNotEquals(old.secret, fresh.secret)
            store.markEnrolled()
            assertEquals(fresh.copy(enrolled = true), store.current())
        } finally {
            scope.cancel()
        }
    }
}
