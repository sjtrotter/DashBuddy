package cloud.trotter.dashbuddy.core.pipeline.rules

import timber.log.Timber
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signature / integrity verification for a **remote / side-loaded** ruleset bundle
 * (#416 — the hard prerequisite for the matchers CDN split, #192).
 *
 * ## Threat model
 * Once recognition rules arrive from a forkable, network-delivered source, every
 * rule bundle is **untrusted input**. A tampered / MITM'd / malicious-fork bundle
 * that reached [JsonRuleInterpreter.load] unverified could silently drop the
 * dasher's sensitive-screen rules (banking → captured) or redefine recognition
 * wholesale. This verifier is the gate: a bundle compiles only after a detached
 * signature over its **exact canonical bytes** verifies against the **configured
 * source's** pinned public key.
 *
 * ## Scheme (no new crypto dependency)
 * - Detached signature over the exact canonical ruleset JSON bytes.
 * - **ECDSA P-256 with SHA-256** (`SHA256withECDSA`) — available via `java.security`
 *   on every supported API level (min SDK 30), so no BouncyCastle / extra dependency.
 * - Signature transported **base64** (standard alphabet); the underlying signature is
 *   ASN.1 DER `SEQUENCE { r, s }` (what `openssl dgst -sha256 -sign` and Java's
 *   `Signature` both produce/expect — see `matchers/tools/sign-ruleset.sh`).
 * - Public key pinned as **base64 DER X.509 SubjectPublicKeyInfo** in configuration.
 *
 * ## Trust model — forkability without skipping verification
 * Verification is ALWAYS against the *configured source's* key. Each non-bundled
 * source id maps to exactly one pinned key ([keysBySource]); switching sources
 * (the forkability story) switches keys — it never disables the check. A source
 * with no configured key can never mint a [VerifiedRulesetBytes] (fail closed).
 *
 * **Bundled ASSET rulesets are exempt** and load unchanged via
 * [JsonRuleInterpreter.loadDefaults]: the APK signature already covers `assets/`,
 * so re-signing bytes the OS integrity-checks at install would be redundant. Every
 * OTHER path — the [JsonRuleInterpreter.load] CDN hot-reload API and any file-based
 * side-load — MUST pass this verifier, and the type system enforces it: [load] only
 * accepts a [VerifiedRulesetBytes], which only [VerifiedRulesetBytes.verify] can mint.
 *
 * ## Fail direction
 * Every branch fails CLOSED — an oversized bundle, an unconfigured source, an
 * undecodable signature, a verification mismatch, or any crypto exception returns
 * `null` (reject). The caller keeps the last-good ruleset. Logs at ERROR under the
 * stable `Rules` tag (Principle 7); the untrusted bundle is treated as hostile — the
 * log carries only sizes / the source id, **never** any bundle content.
 *
 * @param keysBySource source id → its pinned public key. Built by [fromConfig] from
 *   base64-DER config; a source absent here has no key and is always rejected.
 */
class RulesetVerifier(private val keysBySource: Map<String, PublicKey>) {

    /**
     * Verify [signatureBase64] (a detached ECDSA-P256-SHA256 signature, base64) over
     * [bundleBytes] (the exact canonical JSON bytes) against the pinned key configured
     * for [source]. Returns a [VerifiedRulesetBytes] the loader will accept, or `null`
     * on ANY failure (fail closed).
     */
    fun verify(source: String, bundleBytes: ByteArray, signatureBase64: String): VerifiedRulesetBytes? {
        val key = keysBySource[source]
        if (key == null) {
            // No pinned key configured for this source => can never be trusted.
            Timber.tag(RulesetCrypto.TAG).e(
                "rejected remote bundle: no pinned public key configured for source '%s' (fail closed)",
                source,
            )
            return null
        }
        // The single construction path for a VerifiedRulesetBytes runs the full
        // bounded-ingestion + crypto check (see VerifiedRulesetBytes.verify).
        return VerifiedRulesetBytes.verify(key, source, bundleBytes, signatureBase64)
    }

    companion object {
        /**
         * Build a verifier from configured [keys]. Each key's base64-DER X.509
         * SubjectPublicKeyInfo is decoded once here; a malformed/undecodable key is
         * DROPPED (logged) so its source has no usable key and always fails closed —
         * a bad config entry can never weaken verification for another source.
         */
        fun fromConfig(keys: List<RuleSourceKey>): RulesetVerifier {
            val factory = KeyFactory.getInstance("EC")
            val decoded = HashMap<String, PublicKey>(keys.size)
            for (entry in keys) {
                try {
                    val der = Base64.getDecoder().decode(entry.publicKeyBase64Der)
                    decoded[entry.sourceId] = factory.generatePublic(X509EncodedKeySpec(der))
                } catch (e: Exception) {
                    Timber.tag(RulesetCrypto.TAG).e(
                        e,
                        "dropping unusable pinned key for source '%s' — that source will fail closed",
                        entry.sourceId,
                    )
                }
            }
            return RulesetVerifier(decoded)
        }
    }
}

/** Shared crypto parameters + log tag for the ruleset-verification path (#416). */
internal object RulesetCrypto {
    const val TAG = "Rules"

    /** ECDSA over the NIST P-256 curve with a SHA-256 digest. */
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /**
     * Max accepted remote bundle size, checked BEFORE hashing/verifying. Aligned with
     * [JsonRuleInterpreter]'s post-decode 1 MB file cap so a bundle that would be
     * rejected at compile can't burn crypto work first.
     */
    const val MAX_BUNDLE_BYTES = 1_000_000
}

/**
 * A configured trust anchor: a remote/side-loaded rule [sourceId] paired with the
 * base64-DER (X.509 SubjectPublicKeyInfo) EC-P256 public key that signs its bundles.
 * Generate a keypair per `matchers/tools/README.md`; the private half is NEVER committed.
 */
data class RuleSourceKey(
    val sourceId: String,
    val publicKeyBase64Der: String,
)

/**
 * Canonical ruleset bytes that have **passed signature verification** — the only
 * token [JsonRuleInterpreter.load] accepts (#416). Fail-closed BY CONSTRUCTION: the
 * constructor is private and the sole way to obtain an instance is
 * [VerifiedRulesetBytes.verify], which does the full bounded-ingestion + crypto check.
 * There is thus no way to reach compile-from-remote-bytes without a valid signature —
 * the guarantee is structural, not call-site discipline.
 */
class VerifiedRulesetBytes private constructor(
    /** The verified bundle as a UTF-8 string (== UTF-8 decode of the signed bytes). */
    val json: String,
    /** The configured source id whose pinned key verified these bytes. */
    val source: String,
) {
    companion object {
        /**
         * The SINGLE construction site. Verify [signatureBase64] (detached
         * ECDSA-P256-SHA256, base64) over [bundleBytes] against [key], and mint on
         * success; return `null` on ANY failure (fail closed). Bounded ingestion is
         * enforced here — even a direct caller can't feed an unbounded blob into crypto.
         *
         * Production code goes through [RulesetVerifier.verify], which binds the
         * CONFIGURED key so app code can never present an arbitrary one; this overload
         * exists for a caller that already resolved the pinned key (and for tests that
         * generate their own keypair).
         */
        fun verify(
            key: PublicKey,
            source: String,
            bundleBytes: ByteArray,
            signatureBase64: String,
        ): VerifiedRulesetBytes? {
            // Bounded ingestion BEFORE any crypto: an attacker-supplied blob must not
            // reach the (allocating) base64 decode / signature update.
            if (bundleBytes.size > RulesetCrypto.MAX_BUNDLE_BYTES) {
                Timber.tag(RulesetCrypto.TAG).e(
                    "rejected remote bundle for source '%s': %d bytes exceeds cap %d (before verify)",
                    source, bundleBytes.size, RulesetCrypto.MAX_BUNDLE_BYTES,
                )
                return null
            }

            return try {
                val signature = Base64.getDecoder().decode(signatureBase64)
                val verifier = Signature.getInstance(RulesetCrypto.SIGNATURE_ALGORITHM)
                verifier.initVerify(key)
                verifier.update(bundleBytes)
                if (!verifier.verify(signature)) {
                    Timber.tag(RulesetCrypto.TAG).e(
                        "rejected remote bundle for source '%s': signature does not verify against the " +
                            "pinned key (%d bytes)",
                        source, bundleBytes.size,
                    )
                    return null
                }
                // Verified: decoding to a String is safe — the EXACT bytes were the
                // signed payload; a non-UTF-8 bundle fails downstream JSON parse, not
                // integrity.
                VerifiedRulesetBytes(bundleBytes.toString(Charsets.UTF_8), source)
            } catch (e: IllegalArgumentException) {
                // Base64 decode failure (garbage / missing signature).
                Timber.tag(RulesetCrypto.TAG).e(
                    e, "rejected remote bundle for source '%s': malformed signature encoding", source,
                )
                null
            } catch (e: java.security.GeneralSecurityException) {
                Timber.tag(RulesetCrypto.TAG).e(
                    e, "rejected remote bundle for source '%s': crypto failure during verify", source,
                )
                null
            }
        }
    }
}
