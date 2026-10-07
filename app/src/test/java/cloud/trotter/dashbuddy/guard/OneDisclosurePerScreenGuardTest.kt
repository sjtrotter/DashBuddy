package cloud.trotter.dashbuddy.guard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1255: explanations have one screen owner; an empty per-card disclosure is still a violation. */
class OneDisclosurePerScreenGuardTest {
    private val repoRoot: File by lazy { RepoRoot.locate() }
    private val sources: List<File> by lazy {
        listOf("app", "feature", "core").flatMap { module ->
            File(repoRoot, module).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
                .toList()
        }
    }

    @Test
    fun eachHostHasExactlyOneFooterAndAtMostOneToggle() {
        val footerCall = Regex("""\bHowNumbersWorkFooter\s*\(""")
        val toggleCall = Regex("""\bMoreNumbersToggle\s*\(""")
        // SessionDetailScreen does not render a footer; these are the actual footer hosts.
        val hosts = listOf(
            "app/src/main/java/cloud/trotter/dashbuddy/ui/main/analytics/AnalyticsScreen.kt",
            "app/src/main/java/cloud/trotter/dashbuddy/ui/main/playbook/PlaybookScreen.kt",
            "app/src/main/java/cloud/trotter/dashbuddy/ui/main/dashboard/DashboardScreen.kt",
        )
        hosts.forEach { path ->
            val text = File(repoRoot, path).readText()
            assertEquals("$path must have exactly one footer", 1, footerCall.findAll(text).count())
            assertTrue("$path must have at most one toggle", toggleCall.findAll(text).count() <= 1)
        }
    }

    @Test
    fun disclosureCallsBelongOnlyToFooterAndToggle() {
        val call = Regex("""\bDisclosureRow\s*\(""")
        val declaration = Regex("""\bfun\s+DisclosureRow\s*\(""")
        val allowed = setOf(
            "app/src/main/java/cloud/trotter/dashbuddy/ui/components/HowNumbersWorkFooter.kt",
            "app/src/main/java/cloud/trotter/dashbuddy/ui/components/MoreNumbersToggle.kt",
        )
        val violations = sources.filter { file ->
            val text = file.readText().replace(declaration, "fun declaration(")
            call.containsMatchIn(text) && file.relativeTo(repoRoot).invariantSeparatorsPath !in allowed
        }
        assertTrue("DisclosureRow calls outside screen controls: $violations", violations.isEmpty())
    }

    @Test
    fun baseNotesBelongOnlyToFooter() {
        assertOwners(
            listOf("disclosure_cash_tips"),
            setOf("app/src/main/java/cloud/trotter/dashbuddy/ui/components/HowNumbersWorkFooter.kt"),
        )
    }

    @Test
    fun offerEstimateNoteBelongsOnlyToAnalytics() {
        assertOwners(
            listOf("offers_tab_frozen_disclosure"),
            setOf("app/src/main/java/cloud/trotter/dashbuddy/ui/main/analytics/AnalyticsScreen.kt"),
        )
    }

    @Test
    fun movedNotesBelongOnlyToScreenFooters() {
        assertOwners(
            listOf(
                "time_tab_rate_distinction",
                "time_tab_typical_hour_derivation",
                "time_tab_mileage_tax_disclosure",
                "time_tab_unattributed_note",
                "patterns_tab_stores_manual_note",
                "money_tab_pay_mix_partial_detail",
                "money_tab_where_went_no_split",
                "money_tab_recent_scope_note",
            ),
            setOf(
                "app/src/main/java/cloud/trotter/dashbuddy/ui/main/analytics/AnalyticsScreen.kt",
                "app/src/main/java/cloud/trotter/dashbuddy/ui/main/playbook/PlaybookScreen.kt",
            ),
        )
    }

    private fun assertOwners(keys: List<String>, allowed: Set<String>) {
        keys.forEach { key ->
            val reference = Regex("""\bR\.string\.$key\b""")
            val owners = sources.filter { reference.containsMatchIn(it.readText()) }
            assertTrue("$key has no owner", owners.isNotEmpty())
            val violations = owners.filter { it.relativeTo(repoRoot).invariantSeparatorsPath !in allowed }
            assertTrue("$key is repeated outside its footer: $violations", violations.isEmpty())
        }
    }
}
