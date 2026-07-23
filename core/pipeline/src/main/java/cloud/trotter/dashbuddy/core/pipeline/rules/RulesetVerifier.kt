package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
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
 * `asset:`-prefixed (auto-grant) source id, an over-long or undecodable signature, a
 * verification mismatch, or any exception during verify returns `null` (reject). The
 * caller keeps the last-good ruleset. Logs at ERROR under the stable `Rules` tag
 * (Principle 7); the untrusted bundle is treated as hostile — the log carries only
 * sizes / the source id, **never** any bundle content.
 *
 * ## Known residual — no freshness / rollback protection (deferred to #192)
 * This gate proves a bundle was signed by the pinned key; it does NOT prove the bundle
 * is the *latest* one. An attacker who can serve bytes (a stale CDN cache, a MITM
 * replaying an older signed bundle) could re-serve an OLD but validly-signed bundle,
 * which verifies and swaps in — a downgrade to a superseded ruleset. There is no
 * version/monotonicity/expiry field yet. Freshness pinning (a signed, monotonic
 * `ruleset_version` or a corpus↔rules SHA pin, N5/#638) is tracked with the CDN wiring
 * under #192; until then the swap is authenticity-verified but not rollback-protected.
 *
 * @param keysBySource source id → its pinned public key. Constructed only via
 *   [fromConfig] (the primary constructor is private) from base64-DER config; a source
 *   absent here has no key and is always rejected.
 */
class RulesetVerifier private constructor(private val keysBySource: Map<String, PublicKey>) {

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
                // A remote source must NEVER claim the `asset:` prefix: RuleCapabilityGrants
                // AUTO-GRANTS capabilities for asset-prefixed sources (#417). A config entry
                // that pinned a key for an `asset:`-prefixed id would let a remote bundle's
                // taps auto-grant. Drop such entries so the id can never resolve a key
                // (verify() re-checks this too — defense in depth).
                if (entry.sourceId.startsWith(RuleCapabilityGrants.ASSET_SOURCE_PREFIX)) {
                    Timber.tag(RulesetCrypto.TAG).e(
                        "dropping pinned key for source '%s' — the '%s' prefix is reserved for bundled " +
                            "assets (auto-grant); a remote source must not claim it (fail closed)",
                        entry.sourceId, RuleCapabilityGrants.ASSET_SOURCE_PREFIX,
                    )
                    continue
                }
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

    /**
     * Max accepted length of the base64 signature string, checked BEFORE decode. A real
     * P-256 DER signature base64-encodes to ~100 chars (raw DER ≤ ~72 bytes); 512 is a
     * generous ceiling that still rejects a hostile multi-MB "signature" before it
     * allocates in the base64 decoder.
     */
    const val MAX_SIGNATURE_B64_CHARS = 512
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
         * enforced here — even the in-module caller can't feed an unbounded blob into
         * crypto.
         *
         * **`internal` on purpose:** the ONLY production caller is [RulesetVerifier.verify],
         * which binds the CONFIGURED key so app code can never present an arbitrary key
         * (self-signed key confusion — a key delivered alongside the bundle). Exposing a
         * public `verify(key, …)` would make the key binding disciplinary rather than
         * structural; keeping this internal + [RulesetVerifier]'s constructor private forces
         * every path through [fromConfig]'s pinned keys. Reachable from `:core:pipeline`
         * tests (same module) which generate their own keypair.
         */
        internal fun verify(
            key: PublicKey,
            source: String,
            bundleBytes: ByteArray,
            signatureBase64: String,
        ): VerifiedRulesetBytes? {
            // Defense in depth (fromConfig also drops these): a remote source must never
            // claim the `asset:` prefix, which RuleCapabilityGrants auto-grants (#417).
            if (source.startsWith(RuleCapabilityGrants.ASSET_SOURCE_PREFIX)) {
                Timber.tag(RulesetCrypto.TAG).e(
                    "rejected remote bundle: source '%s' uses the reserved '%s' auto-grant prefix",
                    source, RuleCapabilityGrants.ASSET_SOURCE_PREFIX,
                )
                return null
            }
            // Bounded ingestion BEFORE any crypto: an attacker-supplied blob must not
            // reach the (allocating) base64 decode / signature update.
            if (bundleBytes.size > RulesetCrypto.MAX_BUNDLE_BYTES) {
                Timber.tag(RulesetCrypto.TAG).e(
                    "rejected remote bundle for source '%s': %d bytes exceeds cap %d (before verify)",
                    source, bundleBytes.size, RulesetCrypto.MAX_BUNDLE_BYTES,
                )
                return null
            }
            // Cap the signature string BEFORE base64-decode — a real P-256 DER sig is
            // ~100 chars; a multi-MB "signature" must not allocate in the decoder.
            if (signatureBase64.length > RulesetCrypto.MAX_SIGNATURE_B64_CHARS) {
                Timber.tag(RulesetCrypto.TAG).e(
                    "rejected remote bundle for source '%s': signature length %d exceeds cap %d (before decode)",
                    source, signatureBase64.length, RulesetCrypto.MAX_SIGNATURE_B64_CHARS,
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
            } catch (e: Exception) {
                // Broad catch on PURPOSE (still fail-closed + logged): the input is a
                // HOSTILE, attacker-shaped signature/DER. Beyond the expected
                // IllegalArgumentException (bad base64) and GeneralSecurityException, some
                // JCE providers throw unchecked (ArrayIndexOutOfBounds / NegativeArraySize /
                // provider-internal RuntimeExceptions) on malformed ASN.1 DER — a narrower
                // catch would let one of those escape and fail OPEN into the loader's caller.
                Timber.tag(RulesetCrypto.TAG).e(
                    e, "rejected remote bundle for source '%s': verify failed (%s)", source, e.javaClass.simpleName,
                )
                null
            }
        }
    }
}
