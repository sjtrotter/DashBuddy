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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallIdGrammarTest {
    @Test
    fun `canonical lowercase v4 accepts all RFC variant prefixes`() {
        listOf('8', '9', 'a', 'b').forEach {
            assertTrue(InstallIdGrammar.isCanonicalV4("12345678-1234-4abc-${it}def-123456789abc"))
        }
    }

    @Test
    fun `uppercase other versions and non RFC variants are rejected`() {
        val id = "12345678-1234-4abc-8def-123456789abc"
        listOf(
            "", id.uppercase(), id.replace("4abc", "1abc"), id.replace("8def", "7def"),
            id.replace("8def", "cdef"), id.replace("8def", "fdef"), id.replace("4abc", "gabc"),
            id.dropLast(1), id + "0", " $id", "$id\n", "{$id}", id.replace("-", ""),
        ).forEach { assertFalse(it, InstallIdGrammar.isCanonicalV4(it)) }
    }
}
