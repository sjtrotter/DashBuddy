package cloud.trotter.dashbuddy.data.store

/**
 * A temporary data holder for information extracted by the parser.
 * Contains only the essential, unique information for a store.
 */
data class ParsedStore(
    val storeName: String,
    val address: String
)