package cloud.trotter.dashbuddy.test.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object SnapshotLibrarian {
    private const val PROJECT_ROOT = "src/test/resources/snapshots"
    private const val RETENTION_LIMIT = 5

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
     * Enforces the retention policy on the given folder.
     */
    fun pruneFolder(folder: File) {
        val files = folder.listFiles { _, name -> name.endsWith(".json") } ?: return

        // Sort: Newest First
        val sorted = files.sortedByDescending { it.name }

        val toDelete = mutableListOf<File>()
        var quotaCount = 0

        for (file in sorted) {
            // "Golden" check (Logic moved here)
            if (TestResourceLoader.isGolden(file)) continue

            if (quotaCount < RETENTION_LIMIT) {
                quotaCount++
            } else {
                toDelete.add(file)
            }
        }

        if (toDelete.isNotEmpty()) {
            println("     🧹 CLEANUP: Pruning ${toDelete.size} old snapshots...")
            toDelete.forEach {
                it.delete()
                println("        🗑️ Deleted: ${it.name}")
            }
        }
    }
}