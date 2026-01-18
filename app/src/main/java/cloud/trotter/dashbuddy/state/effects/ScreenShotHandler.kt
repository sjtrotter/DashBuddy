package cloud.trotter.dashbuddy.state.effects

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.Display
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.pipeline.inputs.AccessibilityListener
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenShotHandler {

    const val TAG = "ScreenShotEffect"

    fun capture(scope: CoroutineScope, effect: AppEffect.CaptureScreenshot) {
        scope.launch(Dispatchers.IO) {
            val service = AccessibilityListener.instance ?: return@launch

            val mainExecutor =
                androidx.core.content.ContextCompat.getMainExecutor(DashBuddyApplication.context)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            // 1. Save using MediaStore (Synchronously on this background thread)
                            saveToGallery(result, effect.filenamePrefix)

                            // 2. CLEANUP: Critical!
                            result.hardwareBuffer.close()
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot Failed: Error $errorCode")
                        }
                    }
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Screenshot Failed: Permission denied.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot Failed: Unexpected error.", e)
            }
        }
    }

    /**
     * Saves the screenshot to the public "Pictures/DashBuddy" folder using MediaStore.
     * This makes it immediately visible in Gallery apps.
     */
    private fun saveToGallery(
        result: AccessibilityService.ScreenshotResult,
        filenamePrefix: String
    ) {
        val context = DashBuddyApplication.context
        val resolver = context.contentResolver

        try {
            // 1. Wrap Buffer
            val bitmap = Bitmap.wrapHardwareBuffer(
                result.hardwareBuffer,
                result.colorSpace
            )
            if (bitmap == null) {
                Log.e(TAG, "Failed to wrap hardware buffer.")
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
                Log.e(TAG, "Failed to create MediaStore entry.")
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

            Log.i(TAG, "Screenshot saved to Gallery: Pictures/DashBuddy/$displayName")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to write screenshot to MediaStore", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error saving screenshot", e)
        }
    }
}