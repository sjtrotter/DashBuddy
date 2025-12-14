package cloud.trotter.dashbuddy.statev2

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.StateContext
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log
import android.accessibilityservice.AccessibilityService
import android.view.Display
import cloud.trotter.dashbuddy.services.accessibility.EventHandler
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object StateManagerV2 {

    private const val TAG = "StateManagerV2"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gson = Gson().newBuilder()
        .registerTypeAdapterFactory(
            cloud.trotter.dashbuddy.util.RuntimeTypeAdapterFactory.of(
                AppStateV2::class.java,
                "type"
            )
                .registerSubtype(AppStateV2.Initializing::class.java)
                .registerSubtype(AppStateV2.IdleOffline::class.java)
                .registerSubtype(AppStateV2.AwaitingOffer::class.java)
                .registerSubtype(AppStateV2.OfferPresented::class.java)
                .registerSubtype(AppStateV2.OnPickup::class.java)
                .registerSubtype(AppStateV2.OnDelivery::class.java)
                .registerSubtype(AppStateV2.PostDelivery::class.java)
                .registerSubtype(AppStateV2.PostDash::class.java)
                .registerSubtype(AppStateV2.PausedOrInterrupted::class.java)
        )
        .create()

    // CHANGED: Input Queue now holds the full Context (for Odometer, etc.)
    private val inputChannel = Channel<StateContext>(Channel.UNLIMITED)

    // Output State (UI observes this)
    private val _state = MutableStateFlow<AppStateV2>(AppStateV2.Initializing)
    val state = _state.asStateFlow()

    fun initialize() {
        Log.i(TAG, "Initializing V2 State Machine...")
        restoreState()
        startProcessor()
    }

    fun dispatch(context: StateContext) {
        // We still only care if there is valid screen info, but we send the WHOLE context.
        if (context.screenInfo != null) {
            inputChannel.trySend(context)
        }
    }

    private fun startProcessor() {
        scope.launch {
            for (context in inputChannel) { // Now receiving StateContext
                val currentState = _state.value

                // CHANGED: Pass full context to Reducer
                val transition = Reducer.reduce(currentState, context)

                // 2. Update State (if changed)
                if (transition.newState != currentState) {
                    val oldClass = currentState::class.simpleName
                    val newClass = transition.newState::class.simpleName

                    if (oldClass != newClass) {
                        Log.i(TAG, ">>> TRANSITION: $oldClass -> $newClass")
                    } else {
                        // Verbose logging for data updates
                        Log.v(TAG, "    Update within $newClass: ${transition.newState}")
                    }

                    _state.value = transition.newState
                    saveState(transition.newState)
                }

                // 3. Execute Effects (Side Effects)
                transition.effects.forEach { effect ->
                    executeEffect(effect)
                }
            }
        }
    }

    private fun executeEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.LogEvent -> {
                scope.launch(Dispatchers.IO) {
                    Log.v(TAG, "Logging Event: ${effect.event.eventType}")
                    DashBuddyApplication.appEventRepo.insert(effect.event)
                }
            }

            is AppEffect.UpdateBubble -> {
                Log.i(TAG, "Bubble Update: ${effect.text}")
                // DashBuddyApplication.sendBubbleMessage(effect.text)
            }

            is AppEffect.CaptureScreenshot -> {
                captureScreenshot(effect)
            }

            is AppEffect.PlayNotificationSound -> {
                // Play sound logic
            }
        }
    }

    // --- Helper: Screenshot Execution ---

    private fun captureScreenshot(effect: AppEffect.CaptureScreenshot) {
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
                            saveBitmapToFile(result, effect.filenamePrefix)

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
    private fun saveBitmapToFile(result: AccessibilityService.ScreenshotResult, filename: String) {
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
            // Ensure filename ends in .png
            val safeName = if (filename.endsWith(".png")) filename else "$filename.png"
            val file = File(dir, safeName)

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
    // --- Persistence (Crash Recovery) ---

    private fun saveState(state: AppStateV2) {
        try {
            val json = gson.toJson(state)
            DashBuddyApplication.saveCrashRecoveryState(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save state", e)
        }
    }

    private fun restoreState() {
        val json = DashBuddyApplication.getCrashRecoveryState()
        if (json != null) {
            try {
                // Note regarding Gson & Sealed Classes:
                // Standard Gson doesn't handle polymorphic deserialization (Sealed Classes) automatically.
                // It will likely deserialize to the Base Type (AppStateV2) and lose the data fields
                // unless you register a RuntimeTypeAdapterFactory.

                // For this Draft/Test phase, if it fails, we just reset.
                Log.i(TAG, "Restoring state from storage...")
                _state.value = gson.fromJson(json, AppStateV2::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore state. Starting fresh.", e)
                _state.value = AppStateV2.Initializing
            }
        } else {
            _state.value = AppStateV2.Initializing
        }
    }
}