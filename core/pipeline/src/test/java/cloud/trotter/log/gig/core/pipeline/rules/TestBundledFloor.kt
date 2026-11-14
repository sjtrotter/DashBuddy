package cloud.trotter.log.gig.core.pipeline.rules

/**
 * A minimal bundled floor for loader tests (#1568): remote candidates are only prepared on a live
 * bundled generation. Uber-only, so a DoorDash remote file in a test still carries its own
 * coverage and replaces nothing bundled.
 */
object TestBundledFloor {
    val uber = RuleFile.Bundled(
        "rules/uber.json",
        """
        { "format_version": 2, "platform_id": "uber.driver", "screens": [
          { "id": "uber.screen.sensitive.known", "priority": 0, "overrideable": false,
            "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Uber Wallet Balance" } } } ] }
        """.trimIndent(),
    )
}
