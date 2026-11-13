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

/**
 * The format classes a census value may be reported as (ADR-0011 §13, #1619). Each class names what a parse rule
 * needs to know about a slot and nothing about the value it held.
 */
enum class ShapeClass { MONEY, CLOCK_12H, DISTANCE, MINUTES, ITEM_COUNT }

/**
 * The CLOSED list of shape templates a `uinode.skeleton.v2` / `notification.skeleton.v2` slot of kind
 * [KindClassifier.SHAPE] may carry (ADR-0011 §13). A template is the value's format with its digits removed:
 * `#` stands for an integer of any length (its digit count is dropped, because the count reveals magnitude),
 * `.#` and `.##` for exactly one and two fraction digits. A template keeps only what a parse rule needs: the
 * currency glyph and its position, the number of fraction digits, the separator and the unit. It drops what the
 * value would tell: the hour's AM or PM ([ShapeClass.CLOCK_12H] is one class covering both), a sign's direction
 * (`±`), a unit's singular or plural form (`item(s)`), the digit count and any thousands separator.
 *
 * Only [ShapeClassifier] produces a template, and the DTO accepts nothing outside this list ([isWire]); the server
 * validates against the same list. Adding an entry is a wire change: a new schema version, and the server first.
 */
enum class ShapeTemplate(val wire: String, val shapeClass: ShapeClass) {
    MONEY("\$#.##", ShapeClass.MONEY),
    MONEY_WHOLE("\$#", ShapeClass.MONEY),
    MONEY_SIGNED("±\$#.##", ShapeClass.MONEY),
    MONEY_SIGNED_WHOLE("±\$#", ShapeClass.MONEY),
    CLOCK_12H("#:## AM/PM", ShapeClass.CLOCK_12H),
    CLOCK_12H_TIGHT("#:##AM/PM", ShapeClass.CLOCK_12H),
    CLOCK_12H_LOWER("#:## am/pm", ShapeClass.CLOCK_12H),
    CLOCK_12H_LOWER_TIGHT("#:##am/pm", ShapeClass.CLOCK_12H),
    MILES("# mi", ShapeClass.DISTANCE),
    MILES_1("#.# mi", ShapeClass.DISTANCE),
    MILES_2("#.## mi", ShapeClass.DISTANCE),
    KILOMETRES("# km", ShapeClass.DISTANCE),
    KILOMETRES_1("#.# km", ShapeClass.DISTANCE),
    KILOMETRES_2("#.## km", ShapeClass.DISTANCE),
    MINUTES_SHORT("# min(s)", ShapeClass.MINUTES),
    MINUTES_LONG("# minute(s)", ShapeClass.MINUTES),
    ITEMS("# item(s)", ShapeClass.ITEM_COUNT),
    ;

    companion object {
        private val BY_WIRE: Map<String, ShapeTemplate> = entries.associateBy { it.wire }

        fun fromWire(wire: String): ShapeTemplate? = BY_WIRE[wire]

        /** True when [wire] is one of the closed list's templates, byte for byte. */
        fun isWire(wire: String): Boolean = wire in BY_WIRE
    }
}

/**
 * Maps a CANONICAL value ([CensusHash.canonical]: folded, whitespace collapsed to one ASCII space, trimmed) to its
 * [ShapeTemplate], or null when it fits none (ADR-0011 §13). Whole value only: a template never matches inside a
 * longer value, so a word next to a figure (`$7.50 Guaranteed`, `Deliver by 8:42 PM`) fits nothing, and free text
 * can never become a shape. The caller runs the census PII steps first and consults this only for a `mixed` value
 * no step withheld.
 *
 * Every pattern sees at most [MAX_LENGTH] printable-ASCII characters: the cheap guards run first, so a Unicode
 * digit (`١٢ min`) fits nothing and the JVM (`\d` ASCII-only) and ICU (`\d` any decimal digit) cannot disagree.
 * Fullwidth digits reach here as ASCII, because NFKC in the canonical fold already mapped them.
 *
 * Money reuses the app's one definition of a well-formed figure: [MONEY_FIGURE_CORE] is byte-pinned to
 * `CurrencyShape.FIGURE_CORE` by the app's `ShapeClassifierCurrencyPinTest` (the contract cannot depend on the app,
 * ADR-0011 §1). The whole-dollar arm is the same integer part without the fraction.
 */
object ShapeClassifier {

    /** The integer part of a money figure: `0`, 1–4 digits with no leading zero, or one single-digit thousands group. */
    const val MONEY_INTEGER_CORE: String = "(?:0|[1-9]\\d{0,3}|[1-9],\\d{3})"

    /** A money figure with two fraction digits; byte-identical to `CurrencyShape.FIGURE_CORE` (pinned app-side). */
    const val MONEY_FIGURE_CORE: String = MONEY_INTEGER_CORE + "\\.\\d{2}"

    /** No template's value is longer: `-$1,234.56` is 10, `999.99 km` 9, `999 minutes` 11. */
    const val MAX_LENGTH: Int = 16

    /** A short count: 0 or 1–3 digits with no leading zero. */
    private const val COUNT = "(?:0|[1-9][0-9]{0,2})"
    private const val HOUR_MINUTE = "(?:0?[1-9]|1[0-2]):[0-5][0-9]"

    private val MONEY = Regex("\\\$" + MONEY_FIGURE_CORE)
    private val MONEY_WHOLE = Regex("\\\$" + MONEY_INTEGER_CORE)
    private val MONEY_SIGNED = Regex("[+-]\\\$" + MONEY_FIGURE_CORE)
    private val MONEY_SIGNED_WHOLE = Regex("[+-]\\\$" + MONEY_INTEGER_CORE)
    private val CLOCK_12H = Regex("$HOUR_MINUTE [AP]M")
    private val CLOCK_12H_TIGHT = Regex("$HOUR_MINUTE[AP]M")
    private val CLOCK_12H_LOWER = Regex("$HOUR_MINUTE [ap]m")
    private val CLOCK_12H_LOWER_TIGHT = Regex("$HOUR_MINUTE[ap]m")
    private val MILES = Regex("$COUNT mi")
    private val MILES_1 = Regex("$COUNT\\.[0-9] mi")
    private val MILES_2 = Regex("$COUNT\\.[0-9]{2} mi")
    private val KILOMETRES = Regex("$COUNT km")
    private val KILOMETRES_1 = Regex("$COUNT\\.[0-9] km")
    private val KILOMETRES_2 = Regex("$COUNT\\.[0-9]{2} km")
    private val MINUTES_SHORT = Regex("$COUNT mins?")
    private val MINUTES_LONG = Regex("$COUNT minutes?")
    private val ITEMS = Regex("$COUNT items?")

    /** One pattern per template, in [ShapeTemplate] order; the patterns are disjoint, so order does not decide. */
    private val PATTERNS: List<Pair<Regex, ShapeTemplate>> = listOf(
        MONEY to ShapeTemplate.MONEY,
        MONEY_WHOLE to ShapeTemplate.MONEY_WHOLE,
        MONEY_SIGNED to ShapeTemplate.MONEY_SIGNED,
        MONEY_SIGNED_WHOLE to ShapeTemplate.MONEY_SIGNED_WHOLE,
        CLOCK_12H to ShapeTemplate.CLOCK_12H,
        CLOCK_12H_TIGHT to ShapeTemplate.CLOCK_12H_TIGHT,
        CLOCK_12H_LOWER to ShapeTemplate.CLOCK_12H_LOWER,
        CLOCK_12H_LOWER_TIGHT to ShapeTemplate.CLOCK_12H_LOWER_TIGHT,
        MILES to ShapeTemplate.MILES,
        MILES_1 to ShapeTemplate.MILES_1,
        MILES_2 to ShapeTemplate.MILES_2,
        KILOMETRES to ShapeTemplate.KILOMETRES,
        KILOMETRES_1 to ShapeTemplate.KILOMETRES_1,
        KILOMETRES_2 to ShapeTemplate.KILOMETRES_2,
        MINUTES_SHORT to ShapeTemplate.MINUTES_SHORT,
        MINUTES_LONG to ShapeTemplate.MINUTES_LONG,
        ITEMS to ShapeTemplate.ITEMS,
    )

    init {
        check(PATTERNS.map { it.second } == ShapeTemplate.entries) { "every template has exactly one pattern" }
    }

    /** The template [canonical] fits as a whole, or null. Never throws on any input. */
    fun classify(canonical: String): ShapeTemplate? {
        if (canonical.isEmpty() || canonical.length > MAX_LENGTH) return null
        if (canonical.any { it < ' ' || it > '~' }) return null
        if (canonical.none { it in '0'..'9' }) return null
        return PATTERNS.firstOrNull { (regex, _) -> regex.matches(canonical) }?.second
    }
}
