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
package cloud.trotter.census.contract.authoring

import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleAuthoringVocabularyTest {
    @Test
    fun `value shapes are anchored bounded portable regexes`() {
        assertEquals(12, Vocabulary.VALUE_SHAPES_BY_TRANSFORM.size)
        for ((transform, shape) in Vocabulary.VALUE_SHAPES_BY_TRANSFORM) {
            assertTrue(transform, transform in Vocabulary.TRANSFORMS)
            assertTrue(transform, shape.startsWith("^") && shape.endsWith("$"))
            assertTrue(transform, shape.length <= 120)
            assertTrue(transform, listOf("(?=", "(?!", "(?<").none { it in shape })
            assertTrue(transform, !Regex("""\\(?:[1-9]|k[<{])""").containsMatchIn(shape))
            Regex(shape)
        }
        assertTrue(Vocabulary.EMITTED_PREDICATES.containsAll(listOf("hasTextMatchesRegex", "siblingOf")))
        assertEquals(listOf("stripPrefixes"), Vocabulary.EMITTED_PARAMETERIZED_TRANSFORMS)
    }

    @Test
    fun `field defaults and requirements are closed over the vocabulary`() {
        assertEquals(Vocabulary.SHAPES.toSet(), Vocabulary.FIELDS_BY_SHAPE.keys)
        for ((shape, fields) in Vocabulary.FIELDS_BY_SHAPE) {
            assertEquals("duplicate field in $shape", fields.size, fields.map { it.name }.toSet().size)
            for (field in fields) {
                assertTrue("$shape.${field.name} default transform", Vocabulary.TRANSFORMS.containsAll(field.defaultTransform))
            }
            val required = Vocabulary.REQUIRED_FIELDS_BY_SHAPE[shape].orEmpty() +
                Vocabulary.REQUIRED_ONE_OF_BY_SHAPE[shape].orEmpty().flatten()
            assertTrue("$shape required fields", fields.map { it.name }.containsAll(required))
        }
    }

    @Test
    fun `defaults cover every class and actions are available`() {
        assertEquals(Vocabulary.SCREEN_CLASSES.toSet(), Vocabulary.DEFAULT_SHAPE_BY_CLASS.keys)
        assertTrue(Vocabulary.SHAPES.containsAll(Vocabulary.DEFAULT_SHAPE_BY_CLASS.values))
        assertTrue(Vocabulary.BIND_TARGETS.isNotEmpty())
        assertEquals(Vocabulary.TRANSFORMS.size, Vocabulary.TRANSFORMS.toSet().size)
    }
}
