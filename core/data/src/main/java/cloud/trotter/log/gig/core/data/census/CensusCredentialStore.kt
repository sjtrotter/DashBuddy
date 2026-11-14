package cloud.trotter.log.gig.core.data.census

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallIdGrammar
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.log.gig.core.data.di.CensusCredentialPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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
    class Transient : IllegalStateException("Census credential temporarily unavailable")

    private val mutex = Mutex()
    private var needsKeyReset = false

    /** The UI observes an enrolled prefix or id, and never reads/decrypts a secret. */
    val installIdPrefix: Flow<String?> = ds.data.map {
        if (it[ENROLLED] == true) it[INSTALL_ID]?.take(8) else null
    }

    /** The full enrolled install id — a random handle, not a secret — so the developer can copy it for the server-side trust step (#1185). */
    val installId: Flow<String?> = ds.data.map { if (it[ENROLLED] == true) it[INSTALL_ID] else null }

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

    /** Forgets the current identity. Retired identities ([retire]) stay until a deletion uses them ([forgetRetired]). */
    suspend fun wipe() = mutex.withLock {
        ds.edit { prefs -> CURRENT_KEYS.forEach { prefs.remove(it) } }
        Unit
    }

    /**
     * #1622: the identity reset. An ENROLLED identity may have uploads on the server, so its ID and sealed key move to
     * the retired list, where "Delete my census data" can still delete under it; it is never used to upload again. A
     * pending identity uploaded nothing and is simply dropped. At most [MAX_RETIRED] are kept, oldest dropped first.
     */
    suspend fun retire() = mutex.withLock {
        ds.edit { prefs ->
            val id = prefs[INSTALL_ID]
            val sealed = prefs[SEALED]
            val iv = prefs[IV]
            if (prefs[ENROLLED] == true && id != null && sealed != null && iv != null) {
                val kept = decodeRetired(prefs[RETIRED]).filterNot { it.id == id } + Retired(id, sealed, iv)
                prefs[RETIRED] = encodeRetired(kept.takeLast(MAX_RETIRED))
            }
            CURRENT_KEYS.forEach { prefs.remove(it) }
        }
        Unit
    }

    /** #1622: the retired identities' install IDs, oldest first; reading them decrypts nothing. */
    suspend fun retiredIds(): List<String> = mutex.withLock { decodeRetired(ds.data.first()[RETIRED]).map { it.id } }

    /**
     * #1622: the retired identity [installId] with its key, enrolled, or null when it is not kept. Throws [Unusable]
     * when its key can no longer be opened, [Transient] on a passing Keystore failure.
     */
    suspend fun retired(installId: String): Credential? = mutex.withLock {
        val entry = decodeRetired(ds.data.first()[RETIRED]).firstOrNull { it.id == installId } ?: return@withLock null
        open(entry.id, entry.iv, entry.sealed, enrolled = true)
    }

    /** #1622: drops a retired identity once a deletion under it is confirmed. */
    suspend fun forgetRetired(installId: String) = mutex.withLock {
        ds.edit { prefs ->
            val kept = decodeRetired(prefs[RETIRED]).filterNot { it.id == installId }
            if (kept.isEmpty()) prefs.remove(RETIRED) else prefs[RETIRED] = encodeRetired(kept)
        }
        Unit
    }

    private suspend fun read(): Credential? {
        val prefs = try {
            ds.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw Transient()
        }
        if (CURRENT_KEYS.none { prefs.contains(it) }) return null
        return open(prefs[INSTALL_ID], prefs[IV], prefs[SEALED], prefs[ENROLLED] == true)
    }

    private fun open(storedId: String?, storedIv: String?, storedSealed: String?, enrolled: Boolean): Credential {
        try {
            val id = storedId ?: throw Unusable()
            val iv = decode(storedIv)
            val ct = decode(storedSealed)
            val secret = sealer.open(iv, ct).toString(Charsets.UTF_8)
            if (!InstallIdGrammar.isCanonicalV4(id) || !InstallSecret.isValid(secret)) throw Unusable()
            return Credential(id, secret, enrolled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e) {
                is Unusable, is UnrecoverableKeyException, is KeyPermanentlyInvalidatedException, is AEADBadTagException -> {
                    needsKeyReset = true
                    throw Unusable()
                }
                else -> throw Transient()
            }
        }
    }

    private fun decode(value: String?): ByteArray {
        if (value == null) throw Unusable()
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw Unusable()
        }
    }

    /** A retired identity as stored: the ID and the sealed key, Base64 as the current one is. */
    @Serializable
    private data class Retired(val id: String, val sealed: String, val iv: String) {
        override fun toString(): String = "Retired([redacted])"
    }

    private fun decodeRetired(json: String?): List<Retired> =
        json?.let { runCatching { Json.decodeFromString(RETIRED_LIST, it) }.getOrNull() }.orEmpty()

    private fun encodeRetired(list: List<Retired>): String = Json.encodeToString(RETIRED_LIST, list)

    private companion object {
        val INSTALL_ID = stringPreferencesKey("install_id")
        val SEALED = stringPreferencesKey("secret_sealed")
        val IV = stringPreferencesKey("secret_iv")
        val ENROLLED = booleanPreferencesKey("enrolled")
        val CURRENT_KEYS = listOf(INSTALL_ID, SEALED, IV, ENROLLED)
        val RETIRED = stringPreferencesKey("retired_json")
        const val MAX_RETIRED = 4
        val RETIRED_LIST = ListSerializer(Retired.serializer())
    }
}
