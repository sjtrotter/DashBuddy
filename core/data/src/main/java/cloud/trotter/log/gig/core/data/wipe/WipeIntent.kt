package cloud.trotter.log.gig.core.data.wipe

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1579: the stored commitment to wipe. A plain file in the app's private storage, read synchronously at app start
 * before anything else runs; Android's clear deletes it with everything else. Excluded from backup and transfer.
 */
@Singleton
class WipeIntent internal constructor(private val file: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    fun isCommitted(): Boolean = file.isFile

    /** Written and synced to disk before anything is deleted. */
    fun commit() {
        FileOutputStream(file).use { out ->
            out.write(1)
            out.fd.sync()
        }
    }

    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("Wipe commitment removal failed")
    }

    companion object {
        const val FILE_NAME = "wipe_committed"
    }
}
