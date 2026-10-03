package cloud.trotter.dashbuddy.core.data.census

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import java.security.ProviderException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CensusCredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSealer : KeystoreSealer() {
        var unusable = false
        var failure: Exception? = null
        var resets = 0
        var seals = 0
        override fun seal(bytes: ByteArray): Sealed {
            seals++
            return Sealed(byteArrayOf(1), bytes.map { (it.toInt() xor 85).toByte() }.toByteArray())
        }
        override fun open(iv: ByteArray, ct: ByteArray): ByteArray {
            failure?.let { throw it }
            if (unusable) throw UnrecoverableKeyException()
            return ct.map { (it.toInt() xor 85).toByte() }.toByteArray()
        }
        override fun reset() {
            resets++
            unusable = false
            failure = null
        }
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

    @Test fun `permanent keystore failures report unusable and only explicit mint resets key`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val failures = listOf(UnrecoverableKeyException(), KeyPermanentlyInvalidatedException(), AEADBadTagException())
            for ((index, failure) in failures.withIndex()) {
                val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "permanent-$index.preferences_pb") }
                val sealer = FakeSealer()
                val store = CensusCredentialStore(ds, sealer)
                val original = store.mint()
                store.markEnrolled()
                sealer.failure = failure
                assertTrue(runCatching { store.current() }.exceptionOrNull() is CensusCredentialStore.Unusable)
                assertEquals(0, sealer.resets)
                assertNotEquals(original.installId, store.mint().installId)
                assertEquals(1, sealer.resets)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `transient keystore failures retain enrolled and pending identities without sealing or reset`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val failures = listOf(ProviderException(), KeyStoreException(), IllegalStateException(), IllegalArgumentException(), Exception())
            for ((index, failure) in failures.withIndex()) {
                val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "transient-$index.preferences_pb") }
                val sealer = FakeSealer()
                val store = CensusCredentialStore(ds, sealer)
                val original = store.mint()
                for (enrolled in listOf(false, true)) {
                    if (enrolled) store.markEnrolled()
                    val persisted = ds.data.first()
                    sealer.failure = failure
                    assertTrue(runCatching { store.current() }.exceptionOrNull() is CensusCredentialStore.Transient)
                    assertTrue(runCatching { store.pending() }.exceptionOrNull() is CensusCredentialStore.Transient)
                    assertEquals(persisted, ds.data.first())
                    sealer.failure = null
                    assertEquals(original.copy(enrolled = enrolled), if (enrolled) store.current() else store.pending())
                    assertEquals(0, sealer.resets)
                    assertEquals(1, sealer.seals)
                }
                store.mint()
                assertEquals(0, sealer.resets) // A transient failure must not arm a future key reset either.
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `missing sealed value is unusable`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "missing.preferences_pb") }
            val store = CensusCredentialStore(ds, FakeSealer())
            store.mint()
            store.markEnrolled()
            ds.edit { it.remove(stringPreferencesKey("secret_sealed")) }
            assertTrue(runCatching { store.current() }.exceptionOrNull() is CensusCredentialStore.Unusable)
        } finally {
            scope.cancel()
        }
    }
}
