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

import java.security.MessageDigest
import java.util.Locale

/**
 * The ONE sha256 (#362 SSOT) — owned by the contract because
 * `CensusHash`/`CensusFingerprint` are defined over it.
 *
 * FAIL CLOSED: returns null on digest failure. The old duplicated copies returned the un-hashed
 * input — a privacy hash whose failure mode is the plaintext. Callers either tolerate null or
 * substitute a non-reversible fallback; none may ever see the input echoed back.
 */
fun sha256OrNull(input: String): String? = sha256OrNull(input.toByteArray(Charsets.UTF_8))

/**
 * The byte-level form of [sha256OrNull] (#1145): the census fingerprint digests a canonical BYTE
 * encoding, not a string, and must not grow a second digest + hex site. Same fail-closed contract.
 */
fun sha256OrNull(input: ByteArray): String? = try {
    MessageDigest.getInstance("SHA-256")
        .digest(input)
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
} catch (_: Exception) {
    null
}
