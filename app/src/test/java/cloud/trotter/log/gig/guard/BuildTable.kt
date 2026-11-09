package cloud.trotter.log.gig.guard

import java.io.File

/**
 * #1414 — reads the build table (`distribution` in `app/build.gradle.kts`) the way the guard tests need it: each
 * build type's row. One reader for every guard that keys on the table. #170 adds `readsUnboundPlatforms`.
 */
object BuildTable {

    data class Row(val accessibilityTool: Boolean, val wideEventReceipt: Boolean, val readsUnboundPlatforms: Boolean)

    fun rows(root: File = RepoRoot.locate()): Map<String, Row> =
        ROW.findAll(File(root, "app/build.gradle.kts").readText()).associate { m ->
            m.groupValues[1] to Row(m.groupValues[2].toBoolean(), m.groupValues[3].toBoolean(), m.groupValues[4].toBoolean())
        }

    /**
     * The `accessibility_service_config.xml` a build merges, mirroring the `sourceSets` wiring that
     * `AccessibilityToolFlagGuardTest` pins: the build type's own copy if it has one, else the `accessibilityTool`
     * overlay for a build that declares the flag, else main's.
     */
    fun serviceConfigPath(root: File, buildType: String, row: Row): String {
        val own = "app/src/$buildType/res/xml/accessibility_service_config.xml"
        return when {
            File(root, own).isFile -> own
            row.accessibilityTool -> "app/src/accessibilityTool/res/xml/accessibility_service_config.xml"
            else -> "app/src/main/res/xml/accessibility_service_config.xml"
        }
    }

    private val ROW = Regex(
        """"(\w+)"\s+to\s+Distribution\(\s*accessibilityTool\s*=\s*(true|false)\s*,\s*wideEventReceipt\s*=\s*(true|false)\s*,""" +
            """\s*readsUnboundPlatforms\s*=\s*(true|false)\s*,?\s*\)""",
    )
}
