package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.runtime.*
import cloud.trotter.dashbuddy.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.*

typealias NumberSave = (Double) -> Deferred<WriteResult>

fun stagedSave(action: () -> Unit): Deferred<WriteResult> {
    action()
    return CompletableDeferred(WriteResult.SAVED)
}

val LocalEditSession = staticCompositionLocalOf<EditSession> { error("An editor needs its host's EditSession") }

/** The host owns buffers, even when accordion contents leave composition. */
class EditSession(private val scope: CoroutineScope) {
    class Field internal constructor(stored: String) {
        var text by mutableStateOf(stored)
            internal set
        var edited by mutableStateOf(false)
            internal set
        var focused = false
            internal set
        var error by mutableStateOf(false)
            internal set
        var awaiting by mutableStateOf<String?>(null)
            internal set
        internal var stored = stored
        internal var valid: (String) -> Boolean = { true }
        internal var save: (String) -> Deferred<WriteResult> = { CompletableDeferred(WriteResult.REJECTED) }
        internal var revision = 0
        internal var callbackEpoch = 0
        internal var attempt: Deferred<WriteResult>? = null
    }
    private val fields = linkedMapOf<String, Field>()
    private var generation = 0
    var acceptingEdits = true

    fun field(key: String, stored: String, valid: (String) -> Boolean,
        save: (String) -> Deferred<WriteResult>): Field = fields.getOrPut(key) { Field(stored) }.also {
        it.stored = stored
        it.valid = valid
        it.save = save
        if (!it.edited && it.awaiting == null) it.text = stored
    }

    fun edit(key: String, text: String) {
        if (!acceptingEdits) return
        val field = fields.getValue(key)
        if (field.text == text) return
        field.text = text
        field.edited = true
        field.error = !field.valid(text)
        field.revision++
        field.attempt = null
    }

    fun focus(key: String, focused: Boolean) { fields[key]?.focused = focused }

    /** Captures an epoch so Reset/Discard invalidate callbacks already held by widgets. */
    fun finisher(key: String): () -> Unit {
        val epoch = generation
        val fieldEpoch = fields[key]?.callbackEpoch
        return { if (epoch == generation && fieldEpoch == fields[key]?.callbackEpoch) finish(key) }
    }

    fun finish(key: String): Deferred<WriteResult> {
        val field = fields[key] ?: return CompletableDeferred(WriteResult.SAVED)
        field.attempt?.let { return it }
        if (!field.edited) return CompletableDeferred(WriteResult.SAVED)
        if (!field.valid(field.text)) {
            field.error = true
            return CompletableDeferred(WriteResult.FAILED)
        }
        val epoch = generation
        val revision = field.revision
        val text = field.text
        // Invoke before launching: leave-time submissions are accepted before owner cancellation.
        val submission = field.save(text)
        field.attempt = submission
        field.edited = false
        field.awaiting = text
        CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
            val result = submission.await()
            if (epoch == generation && revision == field.revision) {
                field.awaiting = null
                if (result == WriteResult.SAVED) {
                    field.attempt = null
                    field.text = field.stored
                } else field.edited = true
            }
        }
        return submission
    }

    /** Accept all fields synchronously; hosts can then close their edit admission gate. */
    fun submitAll(): List<Deferred<WriteResult>> = fields.keys.toList().map(::finish)

    suspend fun finishAll(): Boolean = submitAll().map { it.await() }.all { it == WriteResult.SAVED }

    /** Retry only previously submitted buffers; unrelated unfinished text stays unfinished. */
    fun retryFailed() {
        val failed = fields.filterValues { it.edited && it.attempt?.isCompleted == true }.keys.toList()
        failed.forEach { key ->
            fields.getValue(key).attempt = null
            finish(key)
        }
    }

    fun discard(key: String) {
        fields[key]?.let {
            it.revision++
            it.callbackEpoch++
            it.edited = false
            it.awaiting = null
            it.attempt = null
            it.error = false
            it.text = it.stored
        }
    }

    fun discardAll() {
        generation++
        fields.values.forEach {
            it.revision++
            it.edited = false
            it.awaiting = null
            it.attempt = null
            it.error = false
            it.text = it.stored
        }
    }
}
