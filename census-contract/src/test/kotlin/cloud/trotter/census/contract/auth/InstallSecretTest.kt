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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class InstallSecretTest {
    @Test
    fun `32 random bytes encode to a valid canonical secret`() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = InstallSecret.encode(bytes)
        assertEquals(43, secret.length)
        assertTrue(InstallSecret.isValid(secret))
        assertArrayEquals(bytes, Base64.getUrlDecoder().decode(secret))
        assertEquals(RequestSignerTest.SECRET, InstallSecret.encode(ByteArray(32) { it.toByte() }))
    }

    @Test
    fun `31 bytes are too short and 96 bytes are the upper bound`() {
        assertFalse(InstallSecret.isValid(InstallSecret.encode(ByteArray(31))))
        val maximum = InstallSecret.encode(ByteArray(96) { -1 })
        assertEquals(128, maximum.length)
        assertTrue(InstallSecret.isValid(maximum))
        assertFalse(InstallSecret.isValid(InstallSecret.encode(ByteArray(97))))
    }

    @Test
    fun `padding noncanonical unused bits and malformed encodings are rejected`() {
        val secret = RequestSignerTest.SECRET
        val nonCanonical = secret.dropLast(1) + "9"
        assertArrayEquals(Base64.getUrlDecoder().decode(secret), Base64.getUrlDecoder().decode(nonCanonical))
        listOf(
            "", secret + "=", nonCanonical, "A".repeat(45), "A".repeat(129),
            "+" + secret.drop(1), "/" + secret.drop(1), " $secret", "$secret\n", "é" + secret.drop(1),
        ).forEach { assertFalse(it, InstallSecret.isValid(it)) }
        assertTrue(InstallSecret.isValid(InstallSecret.encode(ByteArray(32) { -1 })))
        assertTrue(InstallSecret.isValid(InstallSecret.encode(ByteArray(32) { -5 })))
    }

    @Test
    fun `hash is the stored lowercase SHA256 of the encoded secret string`() {
        val hash = InstallSecret.hash(RequestSignerTest.SECRET)
        assertEquals("ea866a757e4c38babfa8127cbe9a409d3e1f93a00ff1488ff735fcf917afffd0", hash)
        assertTrue(Regex("[0-9a-f]{64}").matches(hash))
    }
}
