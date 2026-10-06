package cloud.trotter.dashbuddy.domain.region

/**
 * #1194: the canonical REGION cell an install is "in" — a Census CBSA (metro/micro) or, outside
 * every CBSA, the state. The ONLY location-shaped value that may ever leave the device
 * (cell id, never a coordinate or ZIP).
 */
data class RegionCell(val kind: Kind, val id: String, val vintage: String = VINTAGE) {
    enum class Kind(val wire: String) { METRO("metro"), MICRO("micro"), STATE("state") }

    init {
        require(when (kind) {
            Kind.METRO, Kind.MICRO -> CBSA_ID.matches(id) && id != "00000"
            Kind.STATE -> id in STATE_CODES
        }) { "Invalid region cell identifier" }
        require(VINTAGE_ID.matches(vintage) && vintage != "0000") { "Invalid region cell vintage" }
    }

    /** Stable wire form: `metro:12420@2023`, `state:ND@2023`. */
    val wire: String get() = "${kind.wire}:$id@$vintage"

    companion object {
        const val VINTAGE = "2023"
        internal val STATE_CODES = (
            "AL AK AZ AR CA CO CT DE DC FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN MS MO " +
                "MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY PR"
            ).split(' ').toSet()
        private val WIRE = Regex("(metro|micro|state):([0-9]{5}|[A-Z]{2})@([0-9]{4})")
        private val CBSA_ID = Regex("[0-9]{5}")
        private val VINTAGE_ID = Regex("[0-9]{4}")

        /** Inverse of [wire]; malformed kinds, identifiers, or vintages fail null. */
        fun parse(wire: String): RegionCell? {
            val match = WIRE.matchEntire(wire) ?: return null
            val (kindWire, id, vintage) = match.destructured
            val kind = Kind.entries.first { it.wire == kindWire }
            return runCatching { RegionCell(kind, id, vintage) }.getOrNull()
        }
    }
}
