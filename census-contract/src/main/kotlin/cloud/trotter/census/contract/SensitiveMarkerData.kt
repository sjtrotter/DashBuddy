/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cloud.trotter.census.contract

/**
 * Cross-platform DATA (principle 8) the phone's scan and the census server's re-scan share;
 * the UiNode scan LOGIC stays in `:core:pipeline` (`SensitiveTextMarkers`).
 */
object SensitiveMarkerData {

    /**
     * Keywords inlined from the production sensitive screen rules.
     * Case-insensitive substring match.
     */
    val KEYWORDS: List<String> = listOf(
        // doordash.screen.sensitive.known
        "Bank Account",
        "Routing Number",
        "Social Security",
        "Direct Deposit",
        "Visa",
        "Mastercard",
        "ending in",
        "Linked accounts",
        "Statements & documents",
        "Emergency contact details",
        // doordash.screen.sensitive.catchall
        "Crimson",
        "DasherDirect",
        "Biometric",
        "Available Balance",
        "Tax Form",
        "Expiry",
        "Enter the code we sent",
        "t=completed_view",
        // platform-agnostic payout/identity surfaces (#432)
        "Instant Pay",
        "Cash out",
        "CVV",
        // DasherDirect Savings flow (#463) — leaked dollar balances to UNKNOWN
        // capture on the 2026-06-12 dash; the rule-side block is the
        // sensitive.savings branch, these markers are the fail-closed backstop.
        "Savings jar",
        "You transferred",
        // DasherDirect "Transfer out" balance screen (#794) — leaked the plaintext
        // balance to two UNKNOWN captures on the 2026-07-17 dash (copy "Transfer out"
        // / "$X.XX available" is missed by "Transfer to bank" and "Available Balance").
        // The rule-side block is the sensitive.transfer_out branch; this marker is the
        // fail-closed backstop and the shareable-log scrub anchor. "Transfer out" appears
        // on no recognized customer-facing surface in the corpus (verified per the #738
        // uniqueness discipline), so it cannot over-block a delivery screen.
        "Transfer out",
        // The DasherDirect transfer surface's OTHER direction (#884): the "Transfer in"
        // heading of the deposit-to-DasherDirect flow. The rule side already blocks it
        // (the `sensitive.savings` branch's all["Transfer in", "available"] alternative),
        // but the keyword was missing, so a "Transfer in" CLICK — whose envelope serializes
        // the tapped node in ISOLATION, with no co-present "available" balance line for the
        // AND-pair rule to fire on — had no backstop and reached disk on the 2026-07-24 build.
        // Like "Transfer out", the phrase appears on no recognized customer-facing surface in
        // the corpus (#738 uniqueness discipline), so it cannot over-block a delivery screen.
        "Transfer in",
        // Alcohol-delivery DOCUMENT-capture surfaces only (#463): the license-scan
        // camera (an image of a government ID) and the signature pad/handoff.
        // The ID-CHECK instruction screen and the alcohol arrival card are NOT
        // here — they carry no document image, only customer name/address (which
        // the dropoff parse hashes); we recognize them (alcohol_id_check /
        // dropoff_pre_arrival), we don't block them. We block the *dasher's* own
        // sensitive data, and image captures of IDs/signatures — not customers.
        "Scan barcode on the back",
        "Driver's License",
        "provide their signature",
        "A recipient signature is required",
        // uber.screen.sensitive.* (#762 D10) — Uber's sensitive rules (matchers/rules/uber.json5)
        // anchor on these strings, which had NO overlapping keyword above (case-insensitive
        // substring checked): the wallet balance card ("Uber Pro Card"), the cashout destination
        // screen ("Transfer to bank"), and the tax-document identity screen ("Tax information").
        // The rules were DoorDash-seeded, so an Uber banking screen whose only sensitive wording
        // was one of these phrases (no co-occurring "Instant Pay"/"Cash out"/"Social Security")
        // was invisible to every scrub layer this list backs: the UNKNOWN-capture scan when the
        // uber ruleset fails to load or misses a variant, and the shareable-log scrub sink
        // (#551), where rules never apply at all. "Card number" closes the same gap for the
        // low-confidence `uber.screen.sensitive.catchall` net (its `allTextContainsAny` list
        // carries "card number" verbatim, previously its one entry with no keyword overlap).
        // Drift-guarded by SensitiveMarkerAssetCoverageTest (asset-derived, all platforms).
        "Uber Pro Card",
        "Transfer to bank",
        "Tax information",
        "Card number",
        // Uber's selfie identity-verification CAMERA (#861) — the biometric-image capture
        // surface the `sensitive.id_verification` branch of `uber.screen.sensitive.known`
        // blocks. Its primary rule anchor is the platform's own face-camera component view
        // ids (structurally invisible to a text backstop), so these are the flow's two
        // text-bearing frames — the guide prompt and the success confirmation — which no
        // keyword above overlapped. Same fail-closed reasoning as the group above: the
        // marker list must still drop the frame when the ruleset misses a variant or fails
        // to load. Drift-guarded by SensitiveMarkerAssetCoverageTest.
        "Fit your face in the guide",
        "Thanks for verifying",
        // The DASHER's own DoorDash-side identity/payment surfaces (#1059), all three fielded
        // 2026-08-27/28 reaching UNKNOWN capture with no rule and no keyword behind them. The
        // rule-side block is `doordash.screen.sensitive.known`'s `sensitive.selfie_verification` /
        // `sensitive.red_card` branches and the passport alternatives of `sensitive.id_verification`;
        // those anchor on view ids first (locale-immune, #938/#924), and these keywords are the
        // rules-independent backstop + the shareable-log scrub anchor for the frames that DO render
        // text.
        //  - "verifying your selfie" — the Persona retry screen ("We're having trouble verifying
        //    your selfie"). The apostrophe is deliberately outside the marker (the surface renders a
        //    curly U+2019, which NFKC does not fold to ASCII).
        //  - "Activate a physical card" / "Request a physical card" — the two Red Card wallet
        //    buttons, present on every fielded frame of that screen. The screen's own headline,
        //    "Your Red Card", is deliberately NOT a marker: the ordinary shopping-order pickup
        //    instructions legitimately say "pay with your Red Card" (36 committed corpus fixtures
        //    carry the phrase), so it would over-block a delivery surface — the #738 uniqueness
        //    discipline. Each phrase above has ZERO hits across the committed corpus.
        //  - "Align the character strip" — the ID-scan camera's hint on the PASSPORT variant; the
        //    two pre-existing scanner markers ("Scan barcode on the back", "Driver's License") are
        //    licence-specific, so this document-image surface had no backstop at all.
        // Drift-guarded by SensitiveMarkerAssetCoverageTest.
        "verifying your selfie",
        "Activate a physical card",
        "Request a physical card",
        "Align the character strip",
    )

    /**
     * Shaped-value patterns no keyword list can cover: SSNs, card PANs, and the
     * amount-bearing DasherDirect transfer button.
     *
     * Matched against the normal form [SensitiveMarkerScan.normalize] produces, so every pattern is written in
     * lowercase with ASCII spaces — normalization already folded case, homoglyph whitespace,
     * fullwidth digits/`＄`, and zero-width injections before the scan runs.
     *
     * Conservative shapes to avoid flagging ordinary money/IDs, and deliberately BOUNDED
     * (no nested/unbounded quantifiers) so a hostile third-party string cannot make the scan
     * backtrack: each is a linear scan with fixed-count repetitions only.
     *
     * Public here as the data owner so the server can read it; re-exposed `internal` by
     * `SensitiveTextMarkers.SHAPE_PATTERNS` in `:core:pipeline`, where `MarkerLogIdTest` pins
     * the LIVE patterns (#862) — a shape added here is covered by the log-safety guard automatically.
     */
    val SHAPE_PATTERNS: List<Regex> = listOf(
        // SSN: 123-45-6789
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
        // Card PAN: 4 groups of 4 separated by space/dash (16 digits)
        Regex("""\b\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}\b"""),
        // DasherDirect transfer CONFIRM button (#884): the label interpolates the dasher's own
        // balance — "Transfer $45.66" / "Transfer $83.65" / "Transfer $68.52" all reached disk
        // as UNKNOWN CLICK envelopes on the 2026-07-24 build. No keyword can own this: the
        // amount is the variable part, and a bare "Transfer" keyword would be far too broad.
        // The shape is the amount ADJACENCY — "transfer", optional spaces, "$", a digit — which
        // is banking vocabulary wherever it appears, so a false hit costs at most one debug
        // capture (fail toward privacy). The bounded ` {0,4}` gaps absorb the per-character
        // space normalization (a tab/NBSP run normalizes to several ASCII spaces) and let the
        // sibling-split form ("Transfer" | "$45.66") rejoin across the allText join.
        //
        // Sibling shapes deliberately NOT added: "Cash out $<amt>" needs nothing — the bare
        // "Cash out" keyword above already substring-matches it. "Deposit $<amt>" is not
        // fielded (0 hits across all twelve 2026-07 pulls; the only "deposit" text on the
        // surface is the benign "deposited to your DoorDash …" earnings copy, which carries
        // no adjacent "$"), so it stays out per the #738 uniqueness discipline — a marker is
        // evidence-driven, not speculative. Revisit if a pull ever shows it.
        Regex("""transfer {0,4}\$ {0,4}\d"""),
    )
}
