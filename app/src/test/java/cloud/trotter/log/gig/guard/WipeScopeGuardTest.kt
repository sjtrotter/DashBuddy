package cloud.trotter.log.gig.guard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1579: Delete everything on this phone is Android's own Clear storage, which removes everything in the app's
 * private and app-specific storage and nothing outside it. Its confirmation names what stays outside: exports and
 * gallery screenshots (`wipe_confirm_stays`, `PRIVACY.md` §7). A new place gig.log writes outside its own storage
 * fails here until that line and §7 name it.
 */
class WipeScopeGuardTest {
    private val outsideAppStorage = Regex(
        """\b(MediaStore|getExternalStoragePublicDirectory|getExternalStorageDirectory|externalMediaDirs|openOutputStream|""" +
            """DocumentFile|OpenDocumentTree|CreateDocument|ACTION_CREATE_DOCUMENT|ACTION_OPEN_DOCUMENT_TREE)\b""",
    )

    /** Each file that writes outside the app's storage, and the "stays" item it is. */
    private val known = mapOf(
        "app/src/main/java/cloud/trotter/log/gig/state/effects/ScreenShotHandler.kt" to "screenshots in the gallery",
        "feature/settings/src/main/java/cloud/trotter/log/gig/feature/settings/DataExportScreen.kt" to "exported files",
        "feature/settings/src/main/java/cloud/trotter/log/gig/feature/settings/DataExportViewModel.kt" to "exported files",
    )

    @Test fun `every write outside the app's storage is on the wipe's stays list`() {
        val root = RepoRoot.locate()
        val writers = listOf("app", "core", "feature", "domain").flatMap { module ->
            File(root, module).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
                .filter { outsideAppStorage.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }.toSet()
        assertEquals(known.keys, writers)
    }
}
