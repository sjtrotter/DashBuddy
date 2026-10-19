package cloud.trotter.dashbuddy.feature.bubble.hud.keypad

/** Text buffer: preserves a trailing point and never rounds away entered digits. */
data class DecimalEntry(val digits: String = "", val integerDigits: Int = 5, val fractionDigits: Int = 3) {
    init {
        require(integerDigits > 0 && fractionDigits > 0)
        // A linear check, not a regex: a quantifier brace in a literal trips the ICU guard (#909).
        val head = digits.substringBefore('.')
        val tail = if ('.' in digits) digits.substringAfter('.') else ""
        require(digits.count { it == '.' } <= 1 && head.length <= integerDigits && tail.length <= fractionDigits &&
            (head + tail).all { it in '0'..'9' })
    }
    val positive: Boolean get() = digits.any { it in '1'..'9' }
    fun digit(n: Int): DecimalEntry {
        require(n in 0..9)
        if ('.' !in digits && digits.length >= integerDigits) return this
        if ('.' in digits && digits.substringAfter('.').length >= fractionDigits) return this
        return copy(digits = if (digits == "0") n.toString() else digits + n)
    }
    fun point() = if ('.' in digits) this else copy(digits = digits.ifEmpty { "0" } + ".")
    fun backspace() = copy(digits = digits.dropLast(1))
}

/** S3a's Other amount policy, now shared by cash tips and expenses. UI money stays Long cents. */
data class MoneyEntry(val digits: String = "") {
    private val decimal get() = DecimalEntry(digits, integerDigits = 6, fractionDigits = 2)
    val cents: Long get() = digits.toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: 0L
    fun digit(n: Int) = MoneyEntry(decimal.digit(n).digits)
    fun point() = MoneyEntry(decimal.point().digits)
    fun backspace() = MoneyEntry(decimal.backspace().digits)
}
