package cloud.trotter.dashbuddy.state.effects

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.Display
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenShotHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accessibilitySource: AccessibilitySource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    companion object {
        /** UI-settle delay before every capture — mirrors the click settle (500ms). */
        const val SETTLE_MS = 500L
    }

    fun capture(scope: CoroutineScope, effect: AppEffect.CaptureScreenshot) {
        scope.launch(ioDispatcher) {
            // Let the third-party UI settle before grabbing the frame, so captures
            // aren't taken mid-transition. This is the only screenshot path, so the
            // delay applies to all screenshots everywhere.
            delay(SETTLE_MS)
            val service = accessibilitySource.getService() ?: return@launch

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    // IO-backed executor: the callback does PNG compression + MediaStore
                    // writes, which must never run on the main thread (#349).
                    ioDispatcher.asExecutor(),
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                saveToGallery(result, effect.filenamePrefix)
                            } finally {
                                // The bitmap wraps this buffer, so close only after the
                                // save completes — but on every path (#349).
                                result.hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Timber.e("Screenshot Failed: Error $errorCode")
                        }
                    }
                )
            } catch (e: SecurityException) {
                Timber.e(e, "Screenshot Failed: Permission denied.")
            } catch (e: Exception) {
                Timber.e(e, "Screenshot Failed: Unexpected error.")
            }
        }
    }

    /**
     * Saves the screenshot to the public "Pictures/DashBuddy" folder using MediaStore.
     * This makes it immediately visible in Gallery apps. Runs on the IO executor.
     */
    private fun saveToGallery(
        result: AccessibilityService.ScreenshotResult,
        filenamePrefix: String
    ) {
        val resolver = context.contentResolver

        try {
            // 1. Wrap Buffer
            val bitmap = Bitmap.wrapHardwareBuffer(
                result.hardwareBuffer,
                result.colorSpace
            )
            if (bitmap == null) {
                Timber.e("Failed to wrap hardware buffer.")
                return
            }

            // 2. Generate Name
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
            val timestamp = dateFormat.format(Date())
            // Strip extension if passed in, we add it automatically
            val cleanPrefix = filenamePrefix.removeSuffix(".png")
            val displayName = "$timestamp $cleanPrefix.png"

            // 3. Prepare Metadata
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                // Organized Subfolder in Pictures
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DashBuddy")

                // Mark as pending so gallery doesn't ignore partial file
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            // 4. Insert into MediaStore
            val collection =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val uri: Uri? = resolver.insert(collection, contentValues)

            if (uri == null) {
                Timber.e("Failed to create MediaStore entry.")
                return
            }

            // 5. Write Data
            resolver.openOutputStream(uri).use { out ->
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            // 6. Finish (Mark as not pending)
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            // #772: the filename embeds the rule-declared prefix, which can carry template-expanded
            // merchant text ("Offer - {storeName}") — the name stays on the DEBUG firehose only.
            Timber.i("Screenshot saved to Gallery (Pictures/DashBuddy)")
            Timber.d("Screenshot file: %s", displayName)

        } catch (e: IOException) {
            Timber.e(e, "Failed to write screenshot to MediaStore")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error saving screenshot")
        }
    }
}
