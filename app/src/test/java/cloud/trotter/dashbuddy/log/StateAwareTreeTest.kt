package cloud.trotter.dashbuddy.log

import android.util.Log
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * #551 — the priority must thread through [StateAwareTree] into [LogRepository.appendLog] so the
 * two-sink split can route INFO+ lines to the shareable sink. Without it every line would default to
 * DEBUG and the export would be empty.
 */
class StateAwareTreeTest {

    private fun tree(logRepo: LogRepository): StateAwareTree {
        val dev = mock<DevSettingsRepository> {
            // Pass the level gate for everything so routing is what's under test.
            whenever(it.minLogLevel).thenReturn(MutableStateFlow(Log.VERBOSE))
        }
        return StateAwareTree(logRepo, dev, Provider { "TestState" })
    }

    @Test
    fun `INFO priority threads through to appendLog`() {
        val repo = mock<LogRepository>()
        tree(repo).log(Log.INFO, "Milestone", "offer received", null)
        verify(repo).appendLog(any(), eq(Log.INFO))
    }

    @Test
    fun `DEBUG priority threads through to appendLog`() {
        val repo = mock<LogRepository>()
        tree(repo).log(Log.DEBUG, "Reducer", "PROCESSING Event", null)
        verify(repo).appendLog(any(), eq(Log.DEBUG))
    }
}
