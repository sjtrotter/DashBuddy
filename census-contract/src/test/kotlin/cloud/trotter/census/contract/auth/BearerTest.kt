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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BearerTest {
    private val installId = "12345678-1234-4abc-8def-123456789abc"
    private val secret = RequestSignerTest.SECRET

    @Test
    fun `format and parse round trip with case insensitive scheme`() {
        assertEquals("Bearer $installId.$secret", Bearer.format(installId, secret))
        listOf("Bearer", "bearer", "BEARER", "bEaReR").forEach {
            val parsed = requireNotNull(Bearer.parse("$it $installId.$secret"))
            assertEquals(installId, parsed.installId)
            assertEquals(secret, parsed.secret)
            assertFalse("toString must never render the secret", parsed.toString().contains(secret))
        }
    }

    @Test
    fun `format interpolates without normalizing or validating`() {
        assertEquals("Bearer ID.SECRET", Bearer.format("ID", "SECRET"))
    }

    @Test
    fun `parse rejects malformed schemes separators ids and secrets`() {
        listOf(
            null, "", "Bearer", "Bearer ", "Basic $installId.$secret",
            " Bearer $installId.$secret", "Bearer  $installId.$secret", "Bearer\t$installId.$secret",
            "Bearer $installId$secret", "Bearer ${installId.dropLast(1)}.$secret",
            "Bearer ${installId}0.$secret", "Bearer $installId..$secret", "Bearer $installId.$secret.extra",
            "Bearer ${installId.uppercase()}.$secret", "Bearer ${installId.replace("4abc", "1abc")}.$secret",
            "Bearer ${installId.replace("8def", "7def")}.$secret", "Bearer $installId.",
            "Bearer $installId.${InstallSecret.encode(ByteArray(31))}", "Bearer $installId.$secret=",
            "Bearer $installId.${secret.dropLast(1)}9", "Bearer $installId.$secret ", "Bearer $installId.$secret\n",
        ).forEach { assertNull(it, Bearer.parse(it)) }
    }

    @Test
    fun `header constants match the protocol`() {
        assertEquals("X-Census-Timestamp", CensusHeaders.TIMESTAMP)
        assertEquals("X-Census-Signature", CensusHeaders.SIGNATURE)
        assertEquals("Authorization", CensusHeaders.AUTHORIZATION)
    }
}
