package cloud.trotter.dashbuddy.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilitySource @Inject constructor() {

    // --- 1. The Event Stream (Push) ---
    private val _events = MutableSharedFlow<AccessibilityEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun emit(event: AccessibilityEvent) {
        _events.tryEmit(event)
    }

    // --- 2. The Service Connection (Pull) ---
    // We use a WeakReference so we don't leak the Service if it restarts
    private var serviceRef: WeakReference<AccessibilityService>? = null

    fun registerService(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    /**
     * Fetches the current Root Node directly from the system.
     * * SAFE to call from background threads.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun getCurrentRootNode(): UiNode? {
        val service = serviceRef?.get() ?: return null

        // This is the heavy system call. We only make it when the Pipeline asks.
        val root = service.rootInActiveWindow ?: return null

        return try {
            UiNode.from(root)
        } catch (_: Exception) {
            null
        } finally {
            // nothing here... do we need it?
        }
    }
}