package cloud.trotter.dashbuddy.statev2.effects

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Environment
import android.view.Display
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.EventHandler
import cloud.trotter.dashbuddy.statev2.AppEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

object ScreenShot {

    const val TAG = "ScreenShotEffect"

    fun capture(scope: CoroutineScope, effect: AppEffect.CaptureScreenshot) {
        scope.launch(Dispatchers.IO) {
            val service = EventHandler.getServiceInstance() ?: return@launch

            val mainExecutor =
                androidx.core.content.ContextCompat.getMainExecutor(DashBuddyApplication.context)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            // 1. Save the file (Synchronously on this background thread)
                            save(result, effect.filenamePrefix)

                            // 2. CLEANUP: Critical to prevent memory leaks!
                            result.hardwareBuffer.close()
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot Failed: Error $errorCode")
                        }
                    }
                )
            } catch (e: SecurityException) {
                Log.e(
                    TAG,
                    "Screenshot Failed: Permission denied. Missing android:canTakeScreenshot='true' in config.",
                    e
                )
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot Failed: Unexpected error.", e)
            }
        }
    }

    /**
     * Converts the hardware buffer to a PNG and saves it to the app's private "Screenshots" folder.
     */
    private fun save(result: AccessibilityService.ScreenshotResult, filename: String) {
        try {
            // 1. Wrap the hardware buffer in a Bitmap
            // We check for null because wrapping can fail if the buffer is invalid
            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            if (bitmap == null) {
                Log.e(TAG, "Failed to wrap hardware buffer in Bitmap.")
                return
            }

            // 2. Prepare the Directory: /Android/data/cloud.trotter.dashbuddy/files/Pictures/DashBuddyScreenshots
            val dir = File(
                DashBuddyApplication.context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "DashBuddyScreenshots"
            )
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // 3. Create the File
            // prefix the datetime format
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
            // Ensure filename ends in .png
            val safeName = if (filename.endsWith(".png")) filename else "$filename.png"
            val fullName = "$dateFormat $safeName"
            val file = File(dir, fullName)

            // 4. Write to Disk
            FileOutputStream(file).use { out ->
                // Compress format: PNG (Lossless) or JPEG (Smaller).
                // PNG is better for text/UI screenshots.
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.i(TAG, "Screenshot saved: ${file.absolutePath}")

            // Note: We do NOT recycle the bitmap here because it is backed by the HardwareBuffer,
            // which we close in the caller (onSuccess).

        } catch (e: IOException) {
            Log.e(TAG, "Failed to save screenshot to disk", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error saving screenshot", e)
        }
    }

}