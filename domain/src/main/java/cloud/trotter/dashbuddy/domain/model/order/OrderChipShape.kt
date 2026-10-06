package cloud.trotter.dashbuddy.domain.model.order

/**
 * #882 type-chip shape retained in orders[] to keep presentation identity stable while stores cycle.
 * The captured number is multiplicity, not a merchant name; only 1 proves a single-order entry.
 */
object OrderChipShape {
    private val pattern = Regex("""^\p{L}[\p{L} ]{0,30}\((\d{1,2})\)\s*$""")

    /** Null means no chip match; zero remains explicit, unproven evidence. */
    fun count(storeName: String): Int? = pattern.matchEntire(storeName)?.groupValues?.get(1)?.toInt()
}
