package cloud.trotter.dashbuddy.core.data.event

import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OdometerReadingPayload
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The sequenced read deduplicates the DAO rows BEFORE decoding payloads (#1333 S2f review r6). */
class AppEventRepoSequencedTest {
    private val dao: AppEventDao = mock()

    private fun row(sequenceId: Long, at: Long) = AppEventEntity(
        sequenceId = sequenceId,
        aggregateId = null,
        eventType = AppEventType.ODOMETER_READING,
        eventPayload = AppEventCodec.encodePayload(OdometerReadingPayload("civic", 52_388, 123.5)),
        occurredAt = at,
    )

    @Test fun `an unchanged row list decodes once and keeps the sequence`() = runTest {
        val rows = listOf(row(42, 200))
        whenever(dao.getEventsByType(AppEventType.ODOMETER_READING)).thenReturn(flowOf(rows, rows.toList()))
        val repo = AppEventRepo(mock(), dao, mock())
        val emissions = repo.getSequencedEventsByType(AppEventType.ODOMETER_READING).toList()
        assertEquals(1, emissions.size)
        assertEquals(42L, emissions.single().single().sequenceId)
        assertEquals(OdometerReadingPayload("civic", 52_388, 123.5), emissions.single().single().event.payload)
    }

    @Test fun `a changed row list emits again`() = runTest {
        whenever(dao.getEventsByType(AppEventType.ODOMETER_READING))
            .thenReturn(flowOf(listOf(row(42, 200)), listOf(row(42, 200), row(43, 300))))
        val repo = AppEventRepo(mock(), dao, mock())
        assertEquals(listOf(1, 2), repo.getSequencedEventsByType(AppEventType.ODOMETER_READING).toList().map { it.size })
    }
}
