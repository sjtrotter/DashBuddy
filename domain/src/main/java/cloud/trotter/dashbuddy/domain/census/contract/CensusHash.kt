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
 *
 * Census wire contract (ADR-0011). This package depends on nothing but the JDK,
 * kotlinx-serialization and `domain.util.sha256OrNull`, so that extracting it to a
 * standalone Apache-2.0 `census-contract/` build is a move, not a rewrite.
 */
package cloud.trotter.dashbuddy.domain.census.contract

import cloud.trotter.dashbuddy.domain.util.sha256OrNull

/**
 * The census token hash (ADR-0011 §3): `sha256("census.v1:" + trimmed)`, first [HEX_LENGTH] hex.
 *
 * Unsalted on purpose — cross-install equality is what the server's k rule counts. The `census.v1:`
 * prefix separates the digest DOMAIN from the parse-side `customerNameHash` and the redact masks
 * (`[redacted:<4hex>]`), so a census hash never EQUALS either; it does not make them unjoinable by
 * dictionary (ADR residual risk 1), which is why the §2 filter withholds low-entropy PII shapes
 * BEFORE anything is hashed.
 *
 * FAIL CLOSED (#362): a digest failure returns null and the caller emits `withheld` — the plaintext
 * is never echoed. Callers hash only what the §2 filter admitted; this object does not filter.
 */
object CensusHash {

    /** The digest-domain prefix (hash version 1). */
    const val PREFIX: String = "census.v1:"

    /** The explicit wire discriminator for [PREFIX] (`hashDomain` in the envelope). */
    const val HASH_DOMAIN: Int = 1

    /** Hex characters kept from the full sha256. */
    const val HEX_LENGTH: Int = 16

    /** The census hash of [text] (trimmed first — the builder hashes the trimmed canonical value), or null. */
    fun of(text: String): String? = of(text, ::sha256OrNull)

    /** Test seam: the same computation over an injected digest, so the null path is provable. */
    internal fun of(text: String, digest: (String) -> String?): String? {
        val hex = digest(PREFIX + text.trim()) ?: return null
        if (hex.length < HEX_LENGTH) return null
        return hex.substring(0, HEX_LENGTH)
    }

    /** True when [h] has the wire shape of a census hash: exactly [HEX_LENGTH] lowercase hex. */
    fun isWellFormed(h: String): Boolean =
        h.length == HEX_LENGTH && h.all { it in '0'..'9' || it in 'a'..'f' }
}
