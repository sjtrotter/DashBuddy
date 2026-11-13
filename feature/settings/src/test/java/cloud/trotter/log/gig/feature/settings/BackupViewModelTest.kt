package cloud.trotter.log.gig.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import cloud.trotter.log.gig.core.data.backup.BackupRestorer
import cloud.trotter.log.gig.core.data.backup.BackupSummary
import cloud.trotter.log.gig.core.data.backup.BackupWriter
import cloud.trotter.log.gig.core.data.backup.RestoreOutcome
import cloud.trotter.log.gig.core.data.backup.RestoreRefusal
import cloud.trotter.log.gig.core.data.backup.StageResult
import cloud.trotter.log.gig.feature.settings.BackupViewModel.BackupState
import cloud.trotter.log.gig.feature.settings.BackupViewModel.RestoreState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId

/** #1615: each backup and restore result maps to its screen state. Mocks only; no file work runs here. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val writer: BackupWriter = mock()
    private val restorer: BackupRestorer = mock()
    private val resolver: ContentResolver = mock()
    private val context: Context = mock { on { contentResolver } doReturn resolver }
    private val uri: Uri = mock()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): BackupViewModel =
        BackupViewModel(writer, restorer, context, "1.2.3-test", dispatcher).also { advanceUntilIdle() }

    @Test
    fun `a written backup shows its event count`() = runTest(dispatcher) {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)
        whenever { writer.write(any(), any()) }.thenReturn(BackupSummary(42, 27, 1000))
        val vm = viewModel()
        vm.backUp(uri)
        assertEquals(BackupState.InProgress, vm.backup.value)
        advanceUntilIdle()
        assertEquals(BackupState.Done(42), vm.backup.value)
        verifyBlocking(writer) { write(out, "1.2.3-test") }
    }

    @Test
    fun `a failed backup shows the error class only`() = runTest(dispatcher) {
        whenever(resolver.openOutputStream(uri)).thenReturn(ByteArrayOutputStream())
        whenever { writer.write(any(), any()) }.thenAnswer { throw IOException("content://private/path") }
        val vm = viewModel()
        vm.backUp(uri)
        advanceUntilIdle()
        assertEquals(BackupState.Failed("IOException"), vm.backup.value)
    }

    @Test
    fun `no output stream is a failed backup`() = runTest(dispatcher) {
        whenever(resolver.openOutputStream(uri)).thenReturn(null)
        val vm = viewModel()
        vm.backUp(uri)
        advanceUntilIdle()
        assertEquals(BackupState.Failed("IllegalStateException"), vm.backup.value)
    }

    @Test
    fun `a staged backup asks to confirm with its date and version`() = runTest(dispatcher) {
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(ByteArray(0)))
        whenever { restorer.stage(any()) }.thenReturn(StageResult.Staged(1_700_000_000_000L, "0.9.0", 12))
        val vm = viewModel()
        vm.restore(uri)
        assertEquals(RestoreState.Checking, vm.restore.value)
        advanceUntilIdle()
        assertEquals(RestoreState.Confirm(1_700_000_000_000L, "0.9.0"), vm.restore.value)
    }

    @Test
    fun `each refusal maps to its own state`() = runTest(dispatcher) {
        for (reason in RestoreRefusal.entries) {
            whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(ByteArray(0)))
            whenever { restorer.stage(any()) }.thenReturn(StageResult.Refused(reason))
            val vm = viewModel()
            vm.restore(uri)
            advanceUntilIdle()
            assertEquals(RestoreState.Refused(reason), vm.restore.value)
        }
    }

    @Test
    fun `an unopenable document is a read failure`() = runTest(dispatcher) {
        whenever(resolver.openInputStream(uri)).thenThrow(SecurityException("denied"))
        val vm = viewModel()
        vm.restore(uri)
        advanceUntilIdle()
        assertEquals(RestoreState.Refused(RestoreRefusal.READ_FAILED), vm.restore.value)
    }

    @Test
    fun `confirm marks the stage ready and restarts`() = runTest(dispatcher) {
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(ByteArray(0)))
        whenever { restorer.stage(any()) }.thenReturn(StageResult.Staged(1L, "0.9.0", 1))
        whenever { restorer.confirm() }.thenReturn(true)
        val vm = viewModel()
        vm.restore(uri)
        advanceUntilIdle()
        vm.confirmRestore()
        advanceUntilIdle()
        assertEquals(RestoreState.Restarting, vm.restore.value)
    }

    @Test
    fun `a confirm that cannot mark the stage is a read failure`() = runTest(dispatcher) {
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(ByteArray(0)))
        whenever { restorer.stage(any()) }.thenReturn(StageResult.Staged(1L, "0.9.0", 1))
        whenever { restorer.confirm() }.thenReturn(false)
        val vm = viewModel()
        vm.restore(uri)
        advanceUntilIdle()
        vm.confirmRestore()
        advanceUntilIdle()
        assertEquals(RestoreState.Refused(RestoreRefusal.READ_FAILED), vm.restore.value)
    }

    @Test
    fun `confirm without a checked backup does nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.confirmRestore()
        advanceUntilIdle()
        assertEquals(RestoreState.Idle, vm.restore.value)
        verifyBlocking(restorer, never()) { confirm() }
    }

    @Test
    fun `cancel deletes the stage`() = runTest(dispatcher) {
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(ByteArray(0)))
        whenever { restorer.stage(any()) }.thenReturn(StageResult.Staged(1L, "0.9.0", 1))
        val vm = viewModel()
        vm.restore(uri)
        advanceUntilIdle()
        vm.cancelRestore()
        advanceUntilIdle()
        assertEquals(RestoreState.Idle, vm.restore.value)
        verifyBlocking(restorer) { discard() }
    }

    @Test
    fun `opening the screen reads the last restore once`() = runTest(dispatcher) {
        whenever { restorer.takeResult() }.thenReturn(RestoreOutcome.Restored(5L))
        assertEquals(RestoreOutcome.Restored(5L), viewModel().lastRestore.value)
        whenever { restorer.takeResult() }.thenReturn(RestoreOutcome.Failed)
        assertEquals(RestoreOutcome.Failed, viewModel().lastRestore.value)
    }

    @Test
    fun `the suggested name carries the local date and time`() {
        assertEquals(
            "giglog-backup-2023-11-14-2213.zip",
            BackupViewModel.suggestedFileName(1_700_000_000_000L, ZoneId.of("UTC")),
        )
    }
}
