package cloud.trotter.dashbuddy.domain.region

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import java.io.BufferedReader

/**
 * Offline nearest-county-internal-point approximation, NOT point-in-polygon. Near a county border,
 * a fix can land in the neighbouring county; this matters when the neighbour is in a different
 * CBSA. The rolling 28-day [PrimaryCellPolicy] smooths these observations.
 *
 * A simple linear scan is intended once per GPS-anchored activity event, not once per GPS fix.
 * The distance guard rejects remote fixes; it does not establish a US boundary or coastline.
 */
class CountyCellMap private constructor(internal val rows: List<Row>) {
    internal data class Row(val countyFips: String, val coordinates: Coordinates, val cell: RegionCell)

    fun cellAt(coordinates: Coordinates): RegionCell? = nearest(coordinates)?.cell

    /** Local test diagnostic only; county FIPS must never be sent as the REGION cell. */
    internal fun countyOf(coordinates: Coordinates): String? = nearest(coordinates)?.countyFips

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
        val V2023: CountyCellMap by lazy { load("/geo/county_cells_2023.csv") }
        const val MAX_CENTROID_DISTANCE_METERS = 120_000.0
        private val FIPS = Regex("[0-9]{5}")
        private val DECIMAL = Regex("-?[0-9]+(?:\\.[0-9]+)?")

        private fun load(resource: String): CountyCellMap {
            val stream = checkNotNull(CountyCellMap::class.java.getResourceAsStream(resource)) {
                "Missing county cell map: $resource"
            }
            return stream.bufferedReader(Charsets.UTF_8).use { read(it) }
        }

        /** Shared by the classpath loader and corruption tests; shipped data must fail loud. */
        internal fun read(reader: BufferedReader): CountyCellMap {
            require(reader.readLine() == "county_fips,state,lat,lon,cbsa,kind") { "Invalid county map header" }
            val seen = mutableSetOf<String>()
            val rows = reader.lineSequence().mapIndexed { index, line ->
                val fields = line.split(',')
                require(fields.size == 6) { "Invalid county map row ${index + 2}" }
                val (fips, state, lat, lon, cbsa) = fields
                require(FIPS.matches(fips) && fips != "00000" && seen.add(fips)) {
                    "Invalid or duplicate county FIPS at row ${index + 2}"
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
                Row(fips, coordinates, cell)
            }.toList()
            require(rows.isNotEmpty()) { "Empty county cell map" }
            return CountyCellMap(rows)
        }
    }
}
