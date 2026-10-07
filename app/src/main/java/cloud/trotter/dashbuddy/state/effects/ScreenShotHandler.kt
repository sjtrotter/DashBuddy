package cloud.trotter.dashbuddy.state.effects

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.Display
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.config.EvidenceCaptureBoundary
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
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
    private val platformPreferences: PlatformPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    companion object {
        /** UI-settle delay before every capture — mirrors the click settle (500ms). */
        const val SETTLE_MS = 500L
    }

    fun capture(
        scope: CoroutineScope,
        effect: AppEffect.CaptureScreenshot,
        stillAllowed: () -> Boolean,
    ) {
        scope.launch(ioDispatcher) {
            // Let the third-party UI settle before grabbing the frame, so captures
            // aren't taken mid-transition. This is the only screenshot path, so the
            // delay applies to all screenshots everywhere.
            delay(SETTLE_MS)
            val service = accessibilitySource.getService() ?: return@launch
            val verdict = captureTimeVerdict(service, stillAllowed)
            if (verdict != EvidenceCaptureBoundary.Verdict.CAPTURE) {
                Timber.tag("Effects").i("Evidence capture skipped at capture time: %s", verdict)
                return@launch
            }

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
                            Timber.tag("Effects").e("Screenshot Failed: Error $errorCode")
                        }
                    }
                )
            } catch (e: SecurityException) {
                Timber.tag("Effects").e(e, "Screenshot Failed: Permission denied.")
            } catch (e: Exception) {
                Timber.tag("Effects").e(e, "Screenshot Failed: Unexpected error.")
            }
        }
    }

    private fun captureTimeVerdict(
        service: AccessibilityService,
        stillAllowed: () -> Boolean,
    ): EvidenceCaptureBoundary.Verdict {
        val enabledPlatforms = platformPreferences.enabledPlatforms.value
        var frontPlatform: Platform? = null
        var frontReadable = false
        var sensitiveMarker: String? = null
        try {
            val root = service.rootInActiveWindow
            if (root != null) {
                try {
                    frontPlatform = Platform.fromPackage(root.packageName?.toString())
                    frontReadable = true
                    // Other apps are identified by package only; do not inspect their content.
                    if (frontPlatform != Platform.Unknown && frontPlatform in enabledPlatforms) {
                        // The injected source uses AccessibilityNodeMapper's toUiNode() and
                        // TreeLimits, mapping this same root without another active-window read.
                        val tree = accessibilitySource.getCurrentRootSnapshot(root)?.tree
                        frontReadable = tree != null
                        sensitiveMarker = tree?.let(SensitiveTextMarkers::findMarker)
                    }
                } finally {
                    @Suppress("DEPRECATION") // Required on API 30-32; a no-op on newer Android.
                    root.recycle()
                }
            }
        } catch (_: Exception) {
            // A failed root/package/map read must never admit a whole-display capture.
            frontReadable = false
        }
        return EvidenceCaptureBoundary.decide(
            allowedNow = stillAllowed(),
            frontPlatform = frontPlatform,
            enabledPlatforms = enabledPlatforms,
            sensitiveMarker = sensitiveMarker,
            frontReadable = frontReadable,
        )
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
                Timber.tag("Effects").e("Failed to wrap hardware buffer.")
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
                Timber.tag("Effects").e("Failed to create MediaStore entry.")
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
            Timber.tag("Effects").i("Screenshot saved to Gallery (Pictures/DashBuddy)")
            Timber.tag("Effects").d("Screenshot file: %s", displayName)

        } catch (e: IOException) {
            Timber.tag("Effects").e(e, "Failed to write screenshot to MediaStore")
        } catch (e: Exception) {
            Timber.tag("Effects").e(e, "Unexpected error saving screenshot")
        }
    }
}
