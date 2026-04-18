package cloud.trotter.dashbuddy.core.database

import cloud.trotter.dashbuddy.domain.model.dash.DashType
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.order.DropoffStatus
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DataTypeConvertersTest {

    private lateinit var converters: DataTypeConverters

    @Before
    fun setUp() {
        converters = DataTypeConverters()
    }

    // -------------------------------------------------------------------------
    // OfferBadgeSet
    // -------------------------------------------------------------------------

    @Test
    fun `OfferBadgeSet null converts to empty string`() {
        assertEquals("", converters.fromOfferBadgeSet(null))
    }

    @Test
    fun `OfferBadgeSet empty set converts to empty string`() {
        assertEquals("", converters.fromOfferBadgeSet(emptySet()))
    }

    @Test
    fun `OfferBadgeSet round-trips single value`() {
        val badges = setOf(OfferBadge.entries.first())
        val encoded = converters.fromOfferBadgeSet(badges)
        val decoded = converters.toOfferBadgeSet(encoded)
        assertEquals(badges, decoded)
    }

    @Test
    fun `OfferBadgeSet round-trips multiple values`() {
        val badges = OfferBadge.entries.take(2).toSet()
        val encoded = converters.fromOfferBadgeSet(badges)
        val decoded = converters.toOfferBadgeSet(encoded)
        assertEquals(badges, decoded)
    }

    @Test
    fun `toOfferBadgeSet blank string returns empty set`() {
        assertTrue(converters.toOfferBadgeSet("").isEmpty())
        assertTrue(converters.toOfferBadgeSet(null).isEmpty())
    }

    @Test
    fun `toOfferBadgeSet unknown value is silently ignored`() {
        val result = converters.toOfferBadgeSet("DEFINITELY_NOT_A_BADGE")
        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // OrderBadgeSet
    // -------------------------------------------------------------------------

    @Test
    fun `OrderBadgeSet null converts to empty string`() {
        assertEquals("", converters.fromOrderBadgeSet(null))
    }

    @Test
    fun `OrderBadgeSet round-trips multiple values`() {
        val badges = OrderBadge.entries.take(2).toSet()
        val encoded = converters.fromOrderBadgeSet(badges)
        val decoded = converters.toOrderBadgeSet(encoded)
        assertEquals(badges, decoded)
    }

    @Test
    fun `toOrderBadgeSet unknown value is silently ignored`() {
        assertTrue(converters.toOrderBadgeSet("GARBAGE").isEmpty())
    }

    // -------------------------------------------------------------------------
    // LongList
    // -------------------------------------------------------------------------

    @Test
    fun `LongList null converts to empty string`() {
        assertEquals("", converters.fromLongList(null))
    }

    @Test
    fun `LongList round-trips multiple values`() {
        val list = listOf(1L, 42L, 999L)
        val encoded = converters.fromLongList(list)
        val decoded = converters.toLongList(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `toLongList blank string returns empty list`() {
        assertTrue(converters.toLongList("").isEmpty())
        assertTrue(converters.toLongList(null).isEmpty())
    }

    @Test
    fun `toLongList skips non-numeric parts`() {
        val result = converters.toLongList("1,bad,3")
        assertEquals(listOf(1L, 3L), result)
    }

    // -------------------------------------------------------------------------
    // DashType
    // -------------------------------------------------------------------------

    @Test
    fun `DashType null converts to null`() {
        assertNull(converters.fromDashType(null))
    }

    @Test
    fun `DashType round-trips all values`() {
        for (type in DashType.entries) {
            val encoded = converters.fromDashType(type)
            val decoded = converters.toDashType(encoded)
            assertEquals(type, decoded)
        }
    }

    // -------------------------------------------------------------------------
    // PickupStatus
    // -------------------------------------------------------------------------

    @Test
    fun `PickupStatus round-trips all values`() {
        for (status in PickupStatus.entries) {
            val encoded = converters.fromPickupStatus(status)
            val decoded = converters.toPickupStatus(encoded)
            assertEquals(status, decoded)
        }
    }

    @Test
    fun `toPickupStatus unknown string returns UNKNOWN`() {
        assertEquals(PickupStatus.UNKNOWN, converters.toPickupStatus("GARBAGE"))
    }

    // -------------------------------------------------------------------------
    // DropoffStatus
    // -------------------------------------------------------------------------

    @Test
    fun `DropoffStatus round-trips all values`() {
        for (status in DropoffStatus.entries) {
            val encoded = converters.fromDropoffStatus(status)
            val decoded = converters.toDropoffStatus(encoded)
            assertEquals(status, decoded)
        }
    }

    @Test
    fun `toDropoffStatus unknown string returns UNKNOWN`() {
        assertEquals(DropoffStatus.UNKNOWN, converters.toDropoffStatus("GARBAGE"))
    }

    // -------------------------------------------------------------------------
    // AppEventType
    // -------------------------------------------------------------------------

    @Test
    fun `AppEventType round-trips all values`() {
        for (type in AppEventType.entries) {
            val encoded = converters.fromAppEventType(type)
            val decoded = converters.toAppEventType(encoded)
            assertEquals(type, decoded)
        }
    }

    @Test
    fun `toAppEventType unknown string returns ERROR_OCCURRED`() {
        assertEquals(AppEventType.ERROR_OCCURRED, converters.toAppEventType("GARBAGE"))
    }
}
