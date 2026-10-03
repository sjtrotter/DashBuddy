package cloud.trotter.dashbuddy.core.data.census

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** No authentication prompt: background uploads must be able to open the installation key. */
@Singleton
open class KeystoreSealer @Inject constructor() {
    data class Sealed(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun toString(): String = "Sealed([redacted])"
    }

    open fun seal(bytes: ByteArray): Sealed {
        val key = keyStore().getKey(ALIAS, null) as SecretKey? ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Sealed(cipher.iv, cipher.doFinal(bytes))
    }

    open fun open(iv: ByteArray, ct: ByteArray): ByteArray {
        // Never generate a replacement key on read: this ciphertext would be orphaned.
        val key = keyStore().getKey(ALIAS, null) as SecretKey?
            ?: throw UnrecoverableKeyException("Census key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    /** An invalidated key cannot seal its replacement credential. Only called during recovery. */
    open fun reset() {
        keyStore().deleteEntry(ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val ALIAS = "census_install_secret"
    }
}
