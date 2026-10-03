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

/** Wire bearer formatting and strict credential parsing. */
object Bearer {
    fun format(installId: String, secret: String): String = "Bearer $installId.$secret"

    /** A parsed bearer. `toString` never renders the secret (review: a `Pair` would have). */
    class Credential(val installId: String, val secret: String) {
        override fun toString(): String = "Bearer.Credential(installId=${installId.take(8)}…, secret=[redacted])"
        override fun equals(other: Any?): Boolean = other is Credential && other.installId == installId && other.secret == secret
        override fun hashCode(): Int = 31 * installId.hashCode() + secret.hashCode()
    }

    fun parse(header: String?): Credential? {
        if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) return null
        val credential = header.substring(7)
        val separator = credential.indexOf('.')
        if (separator != 36) return null
        val id = credential.substring(0, separator)
        if (!InstallIdGrammar.isCanonicalV4(id)) return null
        val secret = credential.substring(separator + 1)
        return if (InstallSecret.isValid(secret)) Credential(id, secret) else null
    }
}
