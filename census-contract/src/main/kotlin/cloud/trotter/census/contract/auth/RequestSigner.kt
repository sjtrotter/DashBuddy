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
/**
 * This package is the ONE owner of the wire-auth bytes for both the Android client and the
 * `dashbuddy-census` server; the server delegates to it.
 */
package cloud.trotter.census.contract.auth

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Unlike sha256OrNull, wire authentication preserves the server's digest-failure exception.
internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

// ONE lowercase-hex renderer in this package, shared by credential/body digests and HMAC.
private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Shared wire algorithm. HMAC uses the base64url-decoded secret bytes. */
object RequestSigner {
    fun canonical(method: String, path: String, timestamp: String, rawBody: ByteArray = byteArrayOf()): String =
        "$method\n${path.substringBefore('?')}\n$timestamp\n${sha256Hex(rawBody)}"

    fun sign(secret: String, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
        return "v1=" + mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    fun verify(secret: String, canonical: String, header: String): Boolean =
        verify(secret, canonical, header) { expected, actual -> MessageDigest.isEqual(expected, actual) }

    /** Test seam checks that well-formed signatures take the constant-time comparison path. */
    internal fun verify(secret: String, canonical: String, header: String, equal: (ByteArray, ByteArray) -> Boolean): Boolean {
        if (header.length != 67 || !header.startsWith("v1=") || header.drop(3).any { it !in "0123456789abcdef" }) return false
        return equal(sign(secret, canonical).toByteArray(Charsets.US_ASCII), header.toByteArray(Charsets.US_ASCII))
    }

    fun timestampInWindow(timestamp: String, nowEpochSeconds: Long, windowSeconds: Long = 300): Boolean {
        if (!Regex("-?(0|[1-9][0-9]*)").matches(timestamp)) return false
        val seconds = timestamp.toLongOrNull() ?: return false
        // Overflow-safe (review): compare the DISTANCE, never `now ± window` — a value near Long.MAX_VALUE wrapped the bound.
        val distance = try {
            Math.abs(Math.subtractExact(seconds, nowEpochSeconds))
        } catch (_: ArithmeticException) {
            return false
        }
        return distance >= 0 && distance <= windowSeconds
    }
}
