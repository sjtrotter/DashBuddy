package cloud.trotter.dashbuddy.core.data.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class GoalMetMarkRepositoryTest {
    private val key = "2026-10-18|7900"

    /** Real data-source edit, held before the transformed preferences become durable. */
    private class HeldStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        val release = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        var edits = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            edits++
            release.await()
            failure?.let { throw it }
            return transform(data.value).also { data.value = it }
        }
    }

    @Test fun `pending mark is visible before the write lands`() = runTest {
        val store = HeldStore()
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        // No caller coroutine runs between setting pending and subscribing to the flow.
        repository.setGoalMetMark(key)
        assertEquals(key, repository.goalMetMark.first())
        assertNull(source.goalMetMark.first())
        repository.setGoalMetMark(key)
        runCurrent()
        assertEquals(1, store.edits)
        store.release.complete(Unit)
        runCurrent()
    }

    @Test fun `pending clears once persisted`() = runTest {
        val store = HeldStore()
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        val observed = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.goalMetMark.collect { observed += it }
        }
        runCurrent()
        repository.setGoalMetMark(key)
        runCurrent()
        assertEquals(key, observed.last())
        store.release.complete(Unit)
        runCurrent()
        assertEquals(key, source.goalMetMark.first())
        assertEquals(key, observed.last())
        // A subsequent persisted change is visible only if the pending override was cleared.
        source.clear()
        runCurrent()
        assertNull(observed.last())
    }

    @Test fun `a failing write is logged and the pending mark stays`() = runTest {
        val store = HeldStore()
        val failure = IllegalStateException("storage unavailable")
        store.failure = failure
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        var logged = false
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "AppState" && t === failure) logged = true
            }
        }
        Timber.plant(tree)
        try {
            val observed = mutableListOf<String?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.goalMetMark.collect { observed += it }
            }
            repository.setGoalMetMark(key)
            store.release.complete(Unit)
            runCurrent()
            assertTrue(logged)
            assertNull(source.goalMetMark.first())
            assertEquals(key, observed.last())
            assertEquals(key, repository.goalMetMark.first())
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `a cancelled write is rethrown without logging`() = runTest {
        val store = HeldStore()
        val cancellation = CancellationException("cancel write")
        store.failure = cancellation
        val repository = AppStateRepository(AppStateDataSource(store), backgroundScope)
        var logged = false
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "AppState") logged = true
            }
        }
        Timber.plant(tree)
        try {
            repository.setGoalMetMark(key)
            runCurrent()
            val write = backgroundScope.coroutineContext[Job]!!.children.single()
            store.release.complete(Unit)
            runCurrent()
            assertTrue(write.isCancelled)
            assertFalse(logged)
            assertEquals(key, repository.goalMetMark.first())
        } finally {
            Timber.uproot(tree)
        }
    }
}
