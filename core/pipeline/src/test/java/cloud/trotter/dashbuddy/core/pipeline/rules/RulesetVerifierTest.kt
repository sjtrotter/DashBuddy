package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #416 — signature verification for a remote / side-loaded ruleset bundle.
 *
 * Exercises the [RulesetVerifier] gate directly (no interpreter): a valid signature
 * verifies; a tampered payload, wrong key, missing/garbage signature, unconfigured
 * source, and an oversized bundle all fail CLOSED (return null). All keypairs are
 * generated at runtime — no committed private key.
 */
class RulesetVerifierTest {

    private val source = "cdn:matchers/doordash"
    private val bundle = """{"format_version":2,"platform_id":"doordash.driver","screens":[]}"""
    private val bytes = bundle.toByteArray(Charsets.UTF_8)

    private fun verifierFor(vararg keys: RuleSourceKey) = RulesetVerifier.fromConfig(keys.toList())

    @Test
    fun `valid signature verifies and carries the exact bytes and source`() {
        val pair = TestRulesetSigning.keyPair()
        val verifier = verifierFor(RuleSourceKey(source, TestRulesetSigning.publicKeyBase64Der(pair)))

        val verified = verifier.verify(source, bytes, TestRulesetSigning.signBase64(bytes, pair.private))

        assertNotNull("a valid signature must verify", verified)
        assertEquals(bundle, verified!!.json)
        assertEquals(source, verified.source)
    }

    @Test
    fun `a single-byte-flipped payload is rejected`() {
        val pair = TestRulesetSigning.keyPair()
        val verifier = verifierFor(RuleSourceKey(source, TestRulesetSigning.publicKeyBase64Der(pair)))
        val sig = TestRulesetSigning.signBase64(bytes, pair.private)

        val tampered = bytes.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()

        assertNull("a tampered payload must not verify", verifier.verify(source, tampered, sig))
    }

    @Test
    fun `a signature from a different key is rejected`() {
        val pinned = TestRulesetSigning.keyPair()
        val attacker = TestRulesetSigning.keyPair()
        // The verifier is configured with the PINNED key; the bundle is signed by the ATTACKER.
        val verifier = verifierFor(RuleSourceKey(source, TestRulesetSigning.publicKeyBase64Der(pinned)))
        val sig = TestRulesetSigning.signBase64(bytes, attacker.private)

        assertNull("a wrong-key signature must not verify", verifier.verify(source, bytes, sig))
    }

    @Test
    fun `garbage and empty signatures are rejected`() {
        val pair = TestRulesetSigning.keyPair()
        val verifier = verifierFor(RuleSourceKey(source, TestRulesetSigning.publicKeyBase64Der(pair)))

        assertNull("non-base64 garbage must not verify", verifier.verify(source, bytes, "!!!not base64!!!"))
        assertNull("empty signature must not verify", verifier.verify(source, bytes, ""))
        // Well-formed base64 that decodes to non-DER bytes.
        assertNull("valid-base64 non-signature must not verify", verifier.verify(source, bytes, "AAAA"))
    }

    @Test
    fun `an unconfigured source is rejected even with a valid signature`() {
        val pair = TestRulesetSigning.keyPair()
        // Key pinned for a DIFFERENT source id than the one being loaded.
        val verifier = verifierFor(RuleSourceKey("cdn:other", TestRulesetSigning.publicKeyBase64Der(pair)))
        val sig = TestRulesetSigning.signBase64(bytes, pair.private)

        assertNull("no pinned key for this source => reject", verifier.verify(source, bytes, sig))
    }

    @Test
    fun `an oversized bundle is rejected before crypto`() {
        val pair = TestRulesetSigning.keyPair()
        val verifier = verifierFor(RuleSourceKey(source, TestRulesetSigning.publicKeyBase64Der(pair)))
        val huge = ByteArray(RulesetCrypto.MAX_BUNDLE_BYTES + 1)
        // A signature that WOULD verify the oversized bytes — proves the size cap
        // trips first (fail closed before the crypto path runs).
        val sig = TestRulesetSigning.signBase64(huge, pair.private)

        assertNull("over-cap bundle must be rejected before verify", verifier.verify(source, huge, sig))
    }

    @Test
    fun `a malformed pinned key drops that source to fail-closed`() {
        // fromConfig cannot decode the key => the source has no usable key.
        val verifier = verifierFor(RuleSourceKey(source, "not-a-real-der-key"))
        val pair = TestRulesetSigning.keyPair()
        val sig = TestRulesetSigning.signBase64(bytes, pair.private)

        assertNull("a source whose configured key failed to decode must fail closed", verifier.verify(source, bytes, sig))
    }
}
