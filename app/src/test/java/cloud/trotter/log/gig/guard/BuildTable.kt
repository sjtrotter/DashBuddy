package cloud.trotter.log.gig.guard

import java.io.File

/**
 * #1414 — reads the build table (`distribution` in `app/build.gradle.kts`) the way the guard tests need it: each
 * build type's (accessibilityTool, wideEventReceipt) row. One reader for every guard that keys on the table.
 */
object BuildTable {

    data class Row(val accessibilityTool: Boolean, val wideEventReceipt: Boolean)

    fun rows(root: File = RepoRoot.locate()): Map<String, Row> =
        ROW.findAll(File(root, "app/build.gradle.kts").readText()).associate { m ->
            m.groupValues[1] to Row(m.groupValues[2].toBoolean(), m.groupValues[3].toBoolean())
        }

    private val ROW = Regex(
        """"(\w+)"\s+to\s+Distribution\(\s*accessibilityTool\s*=\s*(true|false)\s*,\s*wideEventReceipt\s*=\s*(true|false)\s*,?\s*\)""",
    )
}
