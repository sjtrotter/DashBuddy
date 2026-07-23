package cloud.trotter.dashbuddy.core.pipeline.rules

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Test-only signing helpers for the #416 ruleset-verification path. Every keypair is
 * generated at runtime — NO private key is ever committed. Mirrors the production
 * `SHA256withECDSA` P-256 scheme (the same one `matchers/tools/sign-ruleset.sh` uses),
 * so tests exercise the real verifier end to end.
 */
object TestRulesetSigning {

    /** A fresh EC P-256 keypair. */
    fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /** Detached ECDSA-P256-SHA256 signature over [bytes], base64-encoded. */
    fun signBase64(bytes: ByteArray, privateKey: PrivateKey): String {
        val signer = Signature.getInstance(RulesetCrypto.SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(bytes)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** The public key as base64-DER X.509 SubjectPublicKeyInfo (the pinned-config form). */
    fun publicKeyBase64Der(pair: KeyPair): String =
        Base64.getEncoder().encodeToString(pair.public.encoded)

    /**
     * Sign [json] with [pair]'s private key and verify through the real
     * [RulesetVerifier], returning the [VerifiedRulesetBytes] a `load()` call needs.
     * Fails the test (non-null assumption) if verification unexpectedly rejects.
     */
    fun verified(json: String, source: String, pair: KeyPair): VerifiedRulesetBytes {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val verifier = RulesetVerifier.fromConfig(
            listOf(RuleSourceKey(source, publicKeyBase64Der(pair))),
        )
        return requireNotNull(verifier.verify(source, bytes, signBase64(bytes, pair.private))) {
            "test fixture failed to verify for source '$source'"
        }
    }
}
