package cloud.trotter.dashbuddy.core.data.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
    private class HeldStore(
        private val holdFirstOnly: Boolean = false,
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        val release = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        var edits = 0
        val editJobs = mutableListOf<Job>()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            edits++
            editJobs += currentCoroutineContext()[Job]!!
            if (!holdFirstOnly || edits == 1) release.await()
            failure?.let { throw it }
            return transform(data.value).also { data.value = it }
        }
    }

    @Test fun `rapid keys persist the latest`() = runTest {
        val store = HeldStore(holdFirstOnly = true)
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        val latest = "2026-10-18|8000"
        val persisted = mutableListOf<String?>()
        var logged = false
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "AppState") logged = true
            }
        }
        Timber.plant(tree)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                source.goalMetMark.collect { persisted += it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.goalMetMark.collect {}
            }
            repository.setGoalMetMark(key)
            runCurrent()
            assertEquals(1, store.edits)
            assertNull(source.goalMetMark.first())
            repository.setGoalMetMark(latest)
            runCurrent()
            assertEquals(2, store.edits)
            assertTrue(store.editJobs.first().isCancelled)
            assertEquals(latest, source.goalMetMark.first())
            store.release.complete(Unit)
            runCurrent()
            assertEquals(listOf(null, latest), persisted)
            assertEquals(latest, source.goalMetMark.first())
            assertEquals(latest, repository.goalMetMark.first())
            assertEquals(latest, AppStateRepository(source, backgroundScope).goalMetMark.first())
            assertFalse(logged)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `a request is visible before the write lands`() = runTest {
        val store = HeldStore()
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        // No caller coroutine runs between making the request and subscribing to the flow.
        repository.setGoalMetMark(key)
        assertEquals(key, repository.goalMetMark.first())
        assertNull(source.goalMetMark.first())
        repository.setGoalMetMark(key)
        runCurrent()
        assertEquals(1, store.edits)
        store.release.complete(Unit)
        runCurrent()
    }

    @Test fun `a request stays visible after persistence changes`() = runTest {
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
        val other = "2026-10-18|8000"
        source.setGoalMetMark(other)
        runCurrent()
        assertEquals(other, source.goalMetMark.first())
        assertEquals(key, observed.last())
        assertEquals(key, repository.goalMetMark.first())
        assertEquals(other, AppStateRepository(source, backgroundScope).goalMetMark.first())
    }

    @Test fun `a request back to the persisted key still cancels the held write`() = runTest {
        val store = HeldStore(
            holdFirstOnly = true,
            initial = preferencesOf(stringPreferencesKey("goal_met_mark") to key),
        )
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        val other = "2026-10-18|8000"
        val observed = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.goalMetMark.collect { observed += it }
        }
        runCurrent()
        assertEquals(key, observed.last())
        repository.setGoalMetMark(other)
        runCurrent()
        assertEquals(1, store.edits)
        val heldWrite = store.editJobs.single()
        assertTrue(heldWrite.isActive)
        assertEquals(other, observed.last())
        assertEquals(key, source.goalMetMark.first())

        repository.setGoalMetMark(key)
        runCurrent()
        assertTrue(heldWrite.isCancelled)
        assertEquals(2, store.edits)
        assertEquals(key, observed.last())
        assertEquals(key, repository.goalMetMark.first())
        assertEquals(key, source.goalMetMark.first())
        store.release.complete(Unit)
        runCurrent()
        assertEquals(key, source.goalMetMark.first())
        assertEquals(key, observed.last())
        assertEquals(key, repository.goalMetMark.first())
    }

    @Test fun `a read with no request in this process is the persisted value`() = runTest {
        val store = HeldStore(initial = preferencesOf(stringPreferencesKey("goal_met_mark") to key))
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        assertEquals(key, repository.goalMetMark.first())
        runCurrent()
        assertEquals(0, store.edits)

        val other = "2026-10-18|8000"
        store.release.complete(Unit)
        source.setGoalMetMark(other)
        assertEquals(other, repository.goalMetMark.first())
    }

    @Test fun `a failing write is logged and the request stays visible`() = runTest {
        val store = HeldStore()
        val failure = IllegalStateException("storage unavailable")
        store.failure = failure
        val source = AppStateDataSource(store)
        val repository = AppStateRepository(source, backgroundScope)
        var logged = false
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "AppState" && t === failure) {
                    assertFalse(message.contains(key))
                    logged = true
                }
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
            val write = store.editJobs.single()
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
