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
import java.security.MessageDigest

class RequestSignerTest {
    @Test
    fun `canonical bytes and fixed HMAC match the client vector`() {
        val expected = "POST\n/v1/nonce\n1790899200\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val actual = RequestSigner.canonical("POST", "/v1/nonce?ignored=yes", "1790899200")
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        assertEquals(SIGNATURE, RequestSigner.sign(SECRET, actual))
        assertTrue(RequestSigner.verify(SECRET, actual, SIGNATURE))
        assertTrue(RequestSigner.verify(SECRET, actual, SIGNATURE)) // signatures are not replay prevention
    }

    @Test
    fun `tampered body path method or signature fails`() {
        val canonical = RequestSigner.canonical("POST", "/v1/nonce", "1790899200")
        listOf(
            RequestSigner.canonical("POST", "/v1/nonce", "1790899200", "{}".toByteArray()),
            RequestSigner.canonical("GET", "/v1/nonce", "1790899200"),
            RequestSigner.canonical("POST", "/v1/rotate", "1790899200"),
        ).forEach { assertFalse(RequestSigner.verify(SECRET, it, SIGNATURE)) }
        listOf("", SIGNATURE.uppercase(), SIGNATURE + "0", "v2=" + SIGNATURE.drop(3))
            .forEach { assertFalse(RequestSigner.verify(SECRET, canonical, it)) }
        var called = false
        assertTrue(RequestSigner.verify(SECRET, canonical, SIGNATURE) { expected, actual ->
            called = true
            MessageDigest.isEqual(expected, actual)
        })
        assertTrue(called)
    }

    @Test
    fun `timestamp boundaries are inclusive with overflow safe parsing`() {
        val now = 1790899200L
        listOf(-300L, 0L, 300L).forEach { assertTrue(RequestSigner.timestampInWindow((now + it).toString(), now)) }
        listOf(-301L, 301L).forEach { assertFalse(RequestSigner.timestampInWindow((now + it).toString(), now)) }
        listOf("1.0", "+1790899200", "01790899200", "", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), "999999999999999999999")
            .forEach { assertFalse(RequestSigner.timestampInWindow(it, now)) }
    }

    @Test
    fun `raw body bytes change the signature`() {
        val canonical = RequestSigner.canonical("POST", "/v1/nonce", "1790899200", byteArrayOf(0, -1))
        val changed = RequestSigner.canonical("POST", "/v1/nonce", "1790899200", byteArrayOf(0, -2))
        assertFalse(RequestSigner.sign(SECRET, canonical) == RequestSigner.sign(SECRET, changed))
        assertFalse(RequestSigner.verify(SECRET, changed, RequestSigner.sign(SECRET, canonical)))
    }

    @Test
    fun `signature shape rejects uppercase hex independently of prefix and rejects wrong secret`() {
        val canonical = RequestSigner.canonical("POST", "/v1/nonce", "1790899200")
        listOf(
            SIGNATURE.dropLast(1), "v1=" + SIGNATURE.drop(3).uppercase(),
            "V1=" + SIGNATURE.drop(3), "v1=" + "g".repeat(64), " $SIGNATURE", "$SIGNATURE\n",
        ).forEach { header ->
            var called = false
            assertFalse(RequestSigner.verify(SECRET, canonical, header) { _, _ ->
                called = true
                true
            })
            assertFalse(called)
        }
        val wrongSecret = InstallSecret.encode(ByteArray(32) { (it + 1).toByte() })
        assertFalse(RequestSigner.verify(wrongSecret, canonical, SIGNATURE))
    }

    @Test
    fun `query removal preserves method path escapes trailing slash and timestamp bytes`() {
        val expected = "post\n/v1/%6eonce/\n-0\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, RequestSigner.canonical("post", "/v1/%6eonce/?a=?b", "-0"))
    }

    @Test
    fun `timestamp grammar preserves zero negative zero and negative seconds`() {
        listOf("0", "-0", "-1").forEach { assertTrue(RequestSigner.timestampInWindow(it, 0)) }
        listOf("00", "-00", "-01", " 0", "0 ", "0\n", "\t0").forEach {
            assertFalse(RequestSigner.timestampInWindow(it, 0))
        }
    }

    @Test
    fun `caller supplied timestamp window is inclusive`() {
        listOf("90", "100", "110").forEach { assertTrue(RequestSigner.timestampInWindow(it, 100, 10)) }
        listOf("89", "111").forEach { assertFalse(RequestSigner.timestampInWindow(it, 100, 10)) }
        assertTrue(RequestSigner.timestampInWindow("100", 100, 0))
        assertFalse(RequestSigner.timestampInWindow("101", 100, 0))
    }

    companion object {
        const val SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val SIGNATURE = "v1=bcf991bb04da013f724bd200abe7e8352c68dbd7155aacbcb6a1a1dff94cbefd"
    }
}
