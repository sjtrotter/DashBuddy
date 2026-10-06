package cloud.trotter.dashbuddy.domain.region

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import java.io.BufferedReader
import java.util.zip.GZIPInputStream

/**
 * Offline county-subdivision internal points, county→CBSA join; NOT point-in-polygon. Near a county border,
 * a fix can land in the neighbouring county; this matters when the neighbour is in a different
 * CBSA. The rolling 28-day [PrimaryCellPolicy] cannot correct systematic nearest-point errors.
 *
 * A linear scan over ~36k rows is fine once per GPS-anchored EVENT, not once per GPS fix;
 * no additional precomputation is needed.
 * The distance guard rejects remote fixes; it does not establish a US boundary or coastline.
 */
class CountyCellMap private constructor(internal val rows: List<Row>) {
    internal data class Row(val geoid: String, val coordinates: Coordinates, val cell: RegionCell)

    fun cellAt(coordinates: Coordinates): RegionCell? = nearest(coordinates)?.cell

    /** Local test diagnostic only; county FIPS must never be sent as the REGION cell. */
    internal fun countyOf(coordinates: Coordinates): String? = nearest(coordinates)?.geoid?.substring(0, 5)

    private fun nearest(coordinates: Coordinates): Row? {
        if (coordinates.latitude !in -90.0..90.0 || coordinates.longitude !in -180.0..180.0) return null
        var nearest: Row? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for (row in rows) {
            val distance = coordinates.distanceTo(row.coordinates)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = row
            }
        }
        return nearest.takeIf { nearestDistance <= MAX_CENTROID_DISTANCE_METERS }
    }

    companion object {
        val V2023: CountyCellMap by lazy { load("/geo/cousub_cells_2023.csv.gz") }
        /**
         * Nearest-point reach. Key West is 33.7 km from its own subdivision point. The remaining US
         * nulls are the Alaska Arctic/Aleutian boroughs (Utqiagvik 239 km, Adak 256 km — no delivery
         * market). Known limitation: neighbouring-country border cities still resolve to a US cell
         * (Windsor 8.5 km → Detroit, Tijuana 27.8 km → San Diego, Toronto 52.4 km → Buffalo) — a wrong
         * cell, never a privacy leak. An offline US-containment guard replaces this cutoff before
         * the `region` field ships (tracked on #1194).
         */
        const val MAX_CENTROID_DISTANCE_METERS = 160_000.0
        private val GEOID = Regex("[0-9]{10}")
        private val DECIMAL = Regex("-?[0-9]+(?:\\.[0-9]+)?")

        private fun load(resource: String): CountyCellMap {
            val stream = checkNotNull(CountyCellMap::class.java.getResourceAsStream(resource)) {
                "Missing county cell map: $resource"
            }
            return stream.use { GZIPInputStream(it).bufferedReader(Charsets.UTF_8).use { reader -> read(reader) } }
        }

        /** Shared by the classpath loader and corruption tests; shipped data must fail loud. */
        internal fun read(reader: BufferedReader): CountyCellMap {
            require(reader.readLine() == "geoid,state,lat,lon,cbsa,kind") { "Invalid county map header" }
            val seen = mutableSetOf<String>()
            val rows = reader.lineSequence().mapIndexed { index, line ->
                val fields = line.split(',')
                require(fields.size == 6) { "Invalid county map row ${index + 2}" }
                val (geoid, state, lat, lon, cbsa) = fields
                require(GEOID.matches(geoid) && seen.add(geoid)) {
                    "Invalid or duplicate subdivision GEOID at row ${index + 2}"
                }
                require(state in RegionCell.STATE_CODES) { "Invalid state at row ${index + 2}" }
                require(DECIMAL.matches(lat) && DECIMAL.matches(lon)) { "Invalid county coordinate" }
                val coordinates = Coordinates(lat.toDouble(), lon.toDouble())
                require(coordinates.latitude in -90.0..90.0 && coordinates.longitude in -180.0..180.0) {
                    "County coordinate out of range"
                }
                val cell = if (cbsa.isEmpty()) {
                    require(fields[5] == "none") { "County without CBSA must have kind none" }
                    RegionCell(RegionCell.Kind.STATE, state)
                } else {
                    require(fields[5] == "metro" || fields[5] == "micro") { "Invalid CBSA kind" }
                    requireNotNull(RegionCell.parse("${fields[5]}:$cbsa@${RegionCell.VINTAGE}")) {
                        "Invalid CBSA identifier"
                    }
                }
                Row(geoid, coordinates, cell)
            }.toList()
            require(rows.isNotEmpty()) { "Empty county cell map" }
            return CountyCellMap(rows)
        }
    }
}
