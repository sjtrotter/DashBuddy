package cloud.trotter.dashbuddy.domain.region

/** Display reference only; deriving a cell never initializes the titles resource. */
internal object CellTitles {
    private val titles: Map<RegionCell, String> by lazy {
        val stream = checkNotNull(
            CountyCellMap::class.java.getResourceAsStream("/geo/cbsa_titles_2023.csv"),
        ) { "Missing CBSA titles" }
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            require(reader.readLine() == "cbsa,title,kind") { "Invalid CBSA titles header" }
            // Titles contain commas; CSV quotes and doubled quotes are significant.
            val rowPattern = Regex("([0-9]{5}),(?:\"((?:[^\"]|\"\")*)\"|([^\",]*)),(metro|micro)")
            val result = mutableMapOf<RegionCell, String>()
            val seen = mutableSetOf<String>()
            reader.lineSequence().forEach { line ->
                // The pinned export includes two Census workbook footers, not CBSA entries.
                if (line.startsWith("Note: The Office of Management and Budget's (OMB's)") ||
                    line.startsWith("\"Source: File prepared by U.S. Census Bureau, Population Division,")
                ) {
                    require(line.endsWith(",,micro")) { "Invalid Census title footer" }
                    return@forEach
                }
                val match = requireNotNull(rowPattern.matchEntire(line)) { "Invalid CBSA title row" }
                val (id, quoted, unquoted, kind) = match.destructured
                val title = quoted.ifEmpty { unquoted }.replace("\"\"", "\"")
                val cell = requireNotNull(RegionCell.parse("$kind:$id@${RegionCell.VINTAGE}"))
                require(title.isNotBlank() && seen.add(id)) { "Empty or duplicate CBSA title" }
                result[cell] = title
            }
            require(result.isNotEmpty()) { "Empty CBSA titles" }
            result
        }
    }

    fun titleOf(cell: RegionCell): String? {
        if (cell.kind == RegionCell.Kind.STATE || cell.vintage != RegionCell.VINTAGE) return null
        return titles[cell]
    }
}
