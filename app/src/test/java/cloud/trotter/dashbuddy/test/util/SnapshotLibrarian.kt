package cloud.trotter.dashbuddy.test.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)

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
     */
    fun pruneFolder(folder: File) {
        val files = folder.listFiles { _, name -> name.endsWith(".json") } ?: return

        // Newest first — within a fingerprint group we always keep the newest
        val sorted = files.sortedByDescending { it.name }

        val seenFingerprints = mutableSetOf<String>()
        val toDelete = mutableListOf<File>()

        for (file in sorted) {
            val fingerprint = contentFingerprint(file)

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

        if (toDelete.isNotEmpty()) {
            println("     🧹 CLEANUP: Pruning ${toDelete.size} redundant/overflow snapshots...")
            toDelete.forEach {
                it.delete()
                println("        🗑️ Deleted: ${it.name}")
            }
        }
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