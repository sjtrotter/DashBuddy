package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallIdGrammar
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.dashbuddy.core.data.di.CensusCredentialPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An unusable Keystore secret is reported, never silently replaced on read. The worker explicitly
 * mints and re-enrols a fresh identity; the old server installation is orphaned, not withdrawn.
 */
@Singleton
class CensusCredentialStore @Inject constructor(
    @param:CensusCredentialPreferences private val ds: DataStore<Preferences>,
    private val sealer: KeystoreSealer,
) {
    data class Credential(val installId: String, val secret: String, val enrolled: Boolean) {
        fun bearer(): Bearer.Credential = Bearer.Credential(installId, secret)
        override fun toString(): String = "Credential(installId=${installId.take(8)}…, secret=[redacted], enrolled=$enrolled)"
    }

    /** Deliberately carries no cause/message from cryptography or persisted data. */
    class Unusable : IllegalStateException("Census credential unusable")

    private val mutex = Mutex()
    private var needsKeyReset = false

    /** The UI observes only an enrolled prefix, and never reads/decrypts a secret. */
    val installIdPrefix: Flow<String?> = ds.data.map {
        if (it[ENROLLED] == true) it[INSTALL_ID]?.take(8) else null
    }

    /** Null until enrolled; pending() preserves enrollment idempotency after a lost response. */
    suspend fun current(): Credential? = mutex.withLock { read()?.takeIf { it.enrolled } }
    suspend fun pending(): Credential? = mutex.withLock { read()?.takeUnless { it.enrolled } }

    suspend fun mint(): Credential = mutex.withLock {
        if (needsKeyReset) {
            sealer.reset()
            needsKeyReset = false
        }
        val id = UUID.randomUUID().toString()
        check(InstallIdGrammar.isCanonicalV4(id))
        val secret = InstallSecret.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val sealed = sealer.seal(secret.toByteArray(Charsets.UTF_8))
        ds.edit {
            it[INSTALL_ID] = id
            it[SEALED] = Base64.getEncoder().encodeToString(sealed.ciphertext)
            it[IV] = Base64.getEncoder().encodeToString(sealed.iv)
            it[ENROLLED] = false
        }
        Credential(id, secret, false)
    }

    suspend fun markEnrolled() = mutex.withLock {
        check(read() != null)
        ds.edit { it[ENROLLED] = true }
        Unit
    }

    suspend fun wipe() = mutex.withLock {
        ds.edit { it.clear() }
        Unit
    }

    private suspend fun read(): Credential? {
        val prefs = ds.data.first()
        if (prefs.asMap().isEmpty()) return null
        try {
            val id = requireNotNull(prefs[INSTALL_ID])
            val iv = Base64.getDecoder().decode(requireNotNull(prefs[IV]))
            val ct = Base64.getDecoder().decode(requireNotNull(prefs[SEALED]))
            val secret = sealer.open(iv, ct).toString(Charsets.UTF_8)
            require(InstallIdGrammar.isCanonicalV4(id) && InstallSecret.isValid(secret))
            return Credential(id, secret, prefs[ENROLLED] == true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            needsKeyReset = true
            throw Unusable()
        }
    }

    private companion object {
        val INSTALL_ID = stringPreferencesKey("install_id")
        val SEALED = stringPreferencesKey("secret_sealed")
        val IV = stringPreferencesKey("secret_iv")
        val ENROLLED = booleanPreferencesKey("enrolled")
    }
}
