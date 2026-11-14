package cloud.trotter.log.gig.core.data.wipe

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1579: the stored commitment to wipe. A plain file in the app's private storage, read synchronously at app start
 * before anything else runs; Android's clear deletes it with everything else. Excluded from backup and transfer.
 *
 * It exists only whole: [commit] writes and syncs a temporary file, then renames it to the final name, so a failed
 * write or sync never leaves a commitment behind, and [isCommitted] accepts only the final name with its exact content.
 */
@Singleton
class WipeIntent internal constructor(
    private val file: File,
    private val write: (FileOutputStream, ByteArray) -> Unit = { out, bytes ->
        out.write(bytes)
        out.fd.sync()
    },
    private val remove: (File) -> Boolean = File::delete,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private val temp = File(file.parentFile, "$FILE_NAME.tmp")

    fun isCommitted(): Boolean = file.isFile && file.readBytes().contentEquals(MAGIC)

    /** Throws when the commitment is not stored; nothing is left behind then. */
    fun commit() {
        try {
            FileOutputStream(temp).use { write(it, MAGIC) }
            if (!temp.renameTo(file)) throw IOException("Wipe commitment rename failed")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        // The rename is the commitment; syncing the directory makes it survive a power cut where the platform allows.
        runCatching {
            FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** Removes the commitment; true only when the final file is verifiably gone. Never throws. */
    fun clear(): Boolean = try {
        if (temp.exists()) remove(temp)
        if (file.exists()) remove(file)
        !file.exists()
    } catch (_: Exception) {
        false
    }

    companion object {
        const val FILE_NAME = "wipe_committed"
        private val MAGIC = "gig.log wipe committed v1".toByteArray(Charsets.US_ASCII)
    }
}
