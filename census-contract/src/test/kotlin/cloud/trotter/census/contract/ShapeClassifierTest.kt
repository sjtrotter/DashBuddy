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
package cloud.trotter.census.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §13 (#1619): the closed shape list and its classifier, at the boundaries. */
class ShapeClassifierTest {

    /** The classifier's input is the census canonical form, exactly as the builder hands it. */
    private fun shapeOf(raw: String): ShapeTemplate? = CensusHash.canonical(raw)?.let { ShapeClassifier.classify(it) }

    @Test
    fun `every template has a value that reaches it, and the wire strings are distinct and digit-free`() {
        val examples = mapOf(
            ShapeTemplate.MONEY to "\$7.50",
            ShapeTemplate.MONEY_WHOLE to "\$7",
            ShapeTemplate.MONEY_SIGNED to "-\$1.25",
            ShapeTemplate.MONEY_SIGNED_WHOLE to "+\$2",
            ShapeTemplate.CLOCK_12H to "8:42 PM",
            ShapeTemplate.CLOCK_12H_TIGHT to "8:42PM",
            ShapeTemplate.CLOCK_12H_LOWER to "8:42 pm",
            ShapeTemplate.CLOCK_12H_LOWER_TIGHT to "8:42pm",
            ShapeTemplate.MILES to "12 mi",
            ShapeTemplate.MILES_1 to "3.4 mi",
            ShapeTemplate.MILES_2 to "0.25 mi",
            ShapeTemplate.KILOMETRES to "5 km",
            ShapeTemplate.KILOMETRES_1 to "5.5 km",
            ShapeTemplate.KILOMETRES_2 to "5.55 km",
            ShapeTemplate.MINUTES_SHORT to "12 min",
            ShapeTemplate.MINUTES_LONG to "45 minutes",
            ShapeTemplate.ITEMS to "3 items",
        )
        assertEquals(ShapeTemplate.entries.toSet(), examples.keys)
        examples.forEach { (template, value) -> assertEquals(value, template, shapeOf(value)) }
        assertEquals(ShapeTemplate.entries.size, ShapeTemplate.entries.map { it.wire }.toSet().size)
        ShapeTemplate.entries.forEach {
            assertFalse("${it.wire} carries a digit", it.wire.any(Char::isDigit))
            assertEquals(it, ShapeTemplate.fromWire(it.wire))
            assertTrue(ShapeTemplate.isWire(it.wire))
        }
        listOf("", "\$##.##", "#:## PM", "#:## AM", "words:1", "\$7.50", "# items").forEach {
            assertFalse("'$it' is not a template", ShapeTemplate.isWire(it))
        }
    }

    @Test
    fun `money keeps its decimal places and glyph position, nothing else`() {
        listOf("\$0.00", "\$7.00", "\$16.70", "\$9999.99", "\$1,234.56").forEach { assertEquals(it, ShapeTemplate.MONEY, shapeOf(it)) }
        // Missing cents is its own class, not money with cents; a half-written fraction fits nothing.
        listOf("\$0", "\$7", "\$45", "\$1,234").forEach { assertEquals(it, ShapeTemplate.MONEY_WHOLE, shapeOf(it)) }
        listOf("\$7.", "\$7.5", "\$7.505", "\$.50").forEach { assertNull(it, shapeOf(it)) }
        // The sign's direction is folded: a credit and a deduction share one class.
        assertEquals(shapeOf("+\$2.00"), shapeOf("-\$2.00"))
        assertEquals(ShapeTemplate.MONEY_SIGNED, shapeOf("−\$2.00"))
        // Thousands separators: the CurrencyShape ceiling holds (four integer digits, one single-digit group).
        listOf("\$12,345.00", "\$123,456.00", "\$1234,567.00", "\$1,2,3.45", "\$,.00", "\$016.70", "\$12345.00")
            .forEach { assertNull(it, shapeOf(it)) }
        // The glyph must lead, with nothing between it and the figure.
        listOf("7.50\$", "7.50 \$", "\$ 7.50", "USD 7.50", "€7.50", "£7.50", "7.50").forEach { assertNull(it, shapeOf(it)) }
    }

    @Test
    fun `a 12-hour clock is one class for AM and PM, noon and midnight included`() {
        listOf("12:00 AM", "12:00 PM", "12:30 AM", "12:59 PM", "1:00 AM", "1:00 PM", "11:59 AM", "11:59 PM", "08:05 AM", "8:05 PM")
            .forEach { assertEquals(it, ShapeTemplate.CLOCK_12H, shapeOf(it)) }
        listOf("12:00 am", "12:00 pm").forEach { assertEquals(it, ShapeTemplate.CLOCK_12H_LOWER, shapeOf(it)) }
        listOf("12:00AM", "12:00PM").forEach { assertEquals(it, ShapeTemplate.CLOCK_12H_TIGHT, shapeOf(it)) }
        listOf("0:00 AM", "00:30 AM", "13:00 PM", "12:60 PM", "12:5 PM", "12:00", "23:59", "12:00 Pm", "12:00 p.m.", "12:00 XM")
            .forEach { assertNull(it, shapeOf(it)) }
    }

    @Test
    fun `units fold singular and plural, and the count is bounded`() {
        assertEquals(shapeOf("1 item"), shapeOf("3 items"))
        assertEquals(shapeOf("1 min"), shapeOf("12 mins"))
        assertEquals(shapeOf("1 minute"), shapeOf("45 minutes"))
        listOf("0 items", "999 items").forEach { assertEquals(it, ShapeTemplate.ITEMS, shapeOf(it)) }
        listOf("1000 items", "07 items", "3 Items", "3 ITEMS", "3items", "3 itemss", "3 mile", "3 miles", "3.456 mi", "3. mi", ".5 mi")
            .forEach { assertNull(it, shapeOf(it)) }
    }

    @Test
    fun `non-breaking and other wide spaces fit only through the canonical form`() {
        listOf("12 min", "12 min", "12  min", " 12 min ", "8:42 PM").forEach {
            assertTrue("'$it' canonicalizes into a shape", shapeOf(it) != null)
        }
        // The classifier itself is strict: a raw NBSP fits nothing.
        assertNull(ShapeClassifier.classify("12 min"))
        assertNull(ShapeClassifier.classify("8:42 PM"))
    }

    @Test
    fun `Unicode digits fit only when NFKC maps them to ASCII`() {
        assertEquals(ShapeTemplate.MINUTES_SHORT, shapeOf("１２ min")) // fullwidth 12
        assertEquals(ShapeTemplate.MONEY, shapeOf("＄７.５０")) // fullwidth $7.50
        // Arabic-Indic, Devanagari and Bengali digits are decimal digits NFKC keeps: no shape on any engine.
        listOf("١٢ min", "\$٧.٥٠", "२:३० PM", "৩ items").forEach {
            assertNull(it, shapeOf(it))
            assertNull(it, ShapeClassifier.classify(it))
        }
    }

    @Test
    fun `free text and longer values never become a shape`() {
        listOf(
            "\$7.50 Guaranteed", "Deliver by 8:42 PM", "3 items from store", "Pay \$7.50", "about 12 min", "12 min away",
            "Accept", "4821", "Apt 12", "#1234", "12:00", "\$7.50\$7.50", "-", "\$", "mi", "items",
        ).forEach { assertNull(it, shapeOf(it)) }
        assertNull(ShapeClassifier.classify(""))
        assertNull(ShapeClassifier.classify("1".repeat(ShapeClassifier.MAX_LENGTH + 1)))
    }

    @Test
    fun `the money figure is the CurrencyShape figure (literal pin, the app test pins the constant)`() {
        assertEquals("(?:0|[1-9]\\d{0,3}|[1-9],\\d{3})\\.\\d{2}", ShapeClassifier.MONEY_FIGURE_CORE)
    }
}
