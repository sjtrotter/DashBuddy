package cloud.trotter.dashbuddy.test.util

import java.io.File

object SnapshotLibrarian {
    private const val PROJECT_ROOT = "src/test/resources/snapshots"

    /**
     * Maximum number of *distinct content variants* to keep per folder.
     * Two snapshots from the same store count as one variant; two different stores count as two.
     */
    private const val RETENTION_LIMIT = 15

    /**
     * Tokens that are purely dynamic (prices, times, distances, percentages).
     * These are stripped before fingerprinting so that "$7.50" vs "$6.25" doesn't
     * make two Chipotle snapshots look like different content.
     */
    private val DYNAMIC_TOKEN_PATTERN = Regex("""^[\$\d:.,/%\- ]+$""")

    /**
     * Moves a file from Source Folder -> Target Folder.
     * Returns the Target Folder directory.
     */
    fun archiveSnapshot(filename: String, sourceFolder: String, targetFolder: String): File {
        val source = File(PROJECT_ROOT, "$sourceFolder/$filename")
        val destDir = File(PROJECT_ROOT, targetFolder)

        if (!destDir.exists()) destDir.mkdirs()

        val dest = File(destDir, filename)
        // Scrub customer PII before the capture becomes a committed fixture.
        dest.writeText(SnapshotRedactor.redact(source.readText()))
        source.delete()

        return destDir
    }

    /**
     * Enforces a diversity-aware retention policy on the given folder.
     *
     * Rather than keeping the N newest files regardless of content, this keeps
     * the newest snapshot per unique content fingerprint, up to [RETENTION_LIMIT]
     * distinct variants. Concretely: 5 Chipotle snapshots collapse to 1 (newest),
     * while Chipotle + Subway + Wendy's each count as distinct and are all kept.
     *
     * Distinct content naturally protects itself — no manual golden marking needed.
     *
     * ### "Newest" means the CAPTURE timestamp, not the filename (#929)
     *
     * This used to sort by raw filename descending, and the corpus carries two capture
     * naming conventions whose lexical order is INVERTED against their real chronology:
     * legacy `20260128_155954_491_…` sorts ABOVE current `2026-07-17_18-08-53-720__…`
     * (`'0'` > `'-'`), and a handful of bare-hash files (`c46728d0.json`) sort above both.
     * So on a saturated folder the prune kept the OLDEST representatives and evicted the
     * newest ones. The PR #926 corpus pass deleted 8 `dropoff_pre_arrival` + 1 `ratings`
     * fixture before it was caught by hand. [captureTimestampOf] parses both conventions,
     * so the "keep the newest" invariant now holds regardless of naming.
     *
     * ### The at-cap safe (#941/#929)
     *
     * [addedFilename] names the file the CURRENT run just archived in. When the folder was
     * already at [RETENTION_LIMIT] distinct variants BEFORE that file arrived, there is no
     * room for it and something must go — and the only safe answer is the newcomer. Evicting
     * an incumbent to admit it would silently drop a committed representative in exchange for
     * one that has not earned its place (the "capped-additions-only, never evict an existing
     * representative" discipline from PR #800). So in that case the newcomer, and ONLY the
     * newcomer, is deleted. Below cap, the normal newest-per-fingerprint dedup still runs and
     * may replace an incumbent with a fresher capture of the same content — that is the
     * intended refresh, not an eviction.
     *
     * Passing `addedFilename = null` (a bare cleanup pass) disables the safe and prunes by
     * policy alone.
     *
     * @param addedFilename the file this run just added to [folder], if any.
     */
    fun pruneFolder(folder: File, addedFilename: String? = null) {
        val files = folder.listFiles { _, name -> name.endsWith(".json") } ?: return

        // Newest first — within a fingerprint group we always keep the newest.
        // Filename is the tie-break so the order is total and reproducible.
        val sorted = files.sortedWith(
            compareByDescending<File> { captureTimestampOf(it.name) }.thenByDescending { it.name },
        )

        // One parse per file — the at-cap safe below needs the same fingerprints.
        val fingerprints = sorted.associateWith { contentFingerprint(it) }

        val seenFingerprints = mutableSetOf<String>()
        val toDelete = mutableListOf<File>()

        for (file in sorted) {
            val fingerprint = fingerprints[file]

            when {
                fingerprint == null -> {
                    // Can't parse — keep it to be safe
                }
                fingerprint in seenFingerprints -> {
                    // Duplicate content variant — older copy, delete it
                    toDelete.add(file)
                }
                seenFingerprints.size < RETENTION_LIMIT -> {
                    seenFingerprints.add(fingerprint)
                }
                else -> {
                    // Over the distinct-variant limit
                    toDelete.add(file)
                }
            }
        }

        val guarded = addedFilename != null && wasAtCapBefore(fingerprints, addedFilename)
        val effective = if (guarded && toDelete.isNotEmpty()) {
            println(
                "     🛡️ AT CAP: folder already held $RETENTION_LIMIT distinct variants before " +
                    "$addedFilename arrived — dropping the newcomer, keeping every incumbent.",
            )
            sorted.filter { it.name == addedFilename }
        } else {
            toDelete
        }

        if (effective.isNotEmpty()) {
            println("     🧹 CLEANUP: Pruning ${effective.size} redundant/overflow snapshots...")
            effective.forEach {
                it.delete()
                println("        🗑️ Deleted: ${it.name}")
            }
        }
    }

    /**
     * True when the folder held [RETENTION_LIMIT] or more distinct content variants
     * *excluding* [addedFilename] — i.e. it was full before this run.
     */
    private fun wasAtCapBefore(fingerprints: Map<File, String?>, addedFilename: String): Boolean =
        fingerprints.entries
            .filterNot { it.key.name == addedFilename }
            .mapNotNull { it.value }
            .toSet()
            .size >= RETENTION_LIMIT

    /** `2026-07-17_18-08-53-720__doordash__…` — the current device capture convention. */
    private val CURRENT_NAME_PATTERN =
        Regex("""^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})-(\d{3})""")

    /** `20260128_155954_491_MAIN_MAP_IDLE.json` — the legacy DiskCaptureBus convention. */
    private val LEGACY_NAME_PATTERN =
        Regex("""^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})_(\d{3})""")

    /**
     * Capture instant embedded in a snapshot filename, as a sortable comparable, or
     * [Long.MIN_VALUE] when the name embeds none.
     *
     * Both conventions encode the same `yyyy MM dd HH mm ss SSS` fields in the same order,
     * so once the separators are stripped the digits concatenate into a directly comparable
     * number — no date parsing, no zone, no locale.
     *
     * Un-timestamped names (the bare-hash `c46728d0.json` files) sort as OLDEST, which is the
     * honest reading: they predate both conventions. That makes them eviction-preferred, which
     * only matters below cap (where losing one means a fresher capture of *identical* content
     * took its place) — at cap the safe in [pruneFolder] protects every incumbent, dated or not.
     */
    internal fun captureTimestampOf(filename: String): Long {
        val m = CURRENT_NAME_PATTERN.find(filename) ?: LEGACY_NAME_PATTERN.find(filename)
        ?: return Long.MIN_VALUE
        return m.groupValues.drop(1).joinToString("").toLongOrNull() ?: Long.MIN_VALUE
    }

    /**
     * Produces a content fingerprint for a snapshot file by extracting the node's
     * text content and stripping dynamic tokens (prices, times, distances) that
     * change between dashes but don't indicate meaningfully different screen content.
     * Returns null if the file can't be parsed.
     */
    private fun contentFingerprint(file: File): String? {
        return try {
            val node = TestResourceLoader.loadNode(file)
            node.allText
                .filterNot { it.matches(DYNAMIC_TOKEN_PATTERN) }
                .map { it.lowercase().trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString("|")
        } catch (_: Exception) {
            null
        }
    }
}