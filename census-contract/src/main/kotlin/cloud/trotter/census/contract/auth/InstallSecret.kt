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
 * Census wire contract (ADR-0011, #1173). This Apache-2.0 included build depends
 * on nothing but the JDK and kotlinx-serialization.
 */
package cloud.trotter.census.contract.auth

import java.util.Base64

/** Canonical base64url credential encoding and the server's stored credential digest. */
object InstallSecret {
    private val secretPattern = Regex("[A-Za-z0-9_-]{43,128}")

    fun isValid(secret: String): Boolean {
        if (!secretPattern.matches(secret)) return false
        val bytes = try {
            Base64.getUrlDecoder().decode(secret)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return bytes.size >= 32 && encode(bytes) == secret // constant-time: not credential material
    }

    /** Hash the UTF-8 encoded secret string, not the decoded HMAC key bytes. */
    fun hash(secret: String): String = sha256Hex(secret.toByteArray(Charsets.UTF_8))

    /** Pure encoding; callers supply at least 32 cryptographically random bytes for a credential. */
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
