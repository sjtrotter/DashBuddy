package cloud.trotter.dashbuddy.core.datastore.strategy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import cloud.trotter.dashbuddy.domain.evaluation.ShopRate
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #588 — the learned shop pace is keyed per platform. A rate learned under one platform must never
 * appear under another, and recording under A must not move B's entry (the un-unmixable global-mean
 * pollution the issue describes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrategyDataSourceShopRateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `two platforms learn independent means and recording under one never moves the other (#588)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "strategy1.preferences_pb") },
        )
        val src = StrategyDataSource(ds)

        // DoorDash: five 24-item / 30-min shops → 0.8/min, past the trust gate.
        repeat(5) { src.recordShopRate(Platform.DoorDash, items = 24, minutes = 30.0) }
        // Instacart: five 30-item / 20-min shops → 1.5/min.
        repeat(5) { src.recordShopRate(Platform.Instacart, items = 30, minutes = 20.0) }
        advanceUntilIdle()

        val rates = src.learnedShopRates.first()
        assertEquals(0.8, rates.getValue(Platform.DoorDash).itemsPerMin!!, 1e-9)
        assertEquals(5, rates.getValue(Platform.DoorDash).sampleCount)
        assertEquals(1.5, rates.getValue(Platform.Instacart).itemsPerMin!!, 1e-9)
        assertEquals(5, rates.getValue(Platform.Instacart).sampleCount)

        // A platform that never recorded is simply absent — the eval seam owns the seed fallback.
        assertNull(rates[Platform.Uber])
        assertNull(rates[Platform.WalmartSpark])

        // Recording MORE DoorDash shops must not perturb Instacart's mean or count.
        repeat(3) { src.recordShopRate(Platform.DoorDash, items = 12, minutes = 30.0) } // 0.4/min, drags DD down
        advanceUntilIdle()
        val after = src.learnedShopRates.first()
        assertEquals("Instacart untouched", 1.5, after.getValue(Platform.Instacart).itemsPerMin!!, 1e-9)
        assertEquals("Instacart untouched", 5, after.getValue(Platform.Instacart).sampleCount)
        assertEquals(8, after.getValue(Platform.DoorDash).sampleCount)
        assertTrue("DoorDash mean moved", after.getValue(Platform.DoorDash).itemsPerMin!! < 0.8)
    }

    @Test
    fun `a below-floor sample is a no-op and does not create a map entry (#556)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "strategy2.preferences_pb") },
        )
        val src = StrategyDataSource(ds)

        // Below ShopRate floors (items < MIN_ITEMS or minutes < MIN_MINUTES) — ignored.
        src.recordShopRate(Platform.DoorDash, items = ShopRate.MIN_ITEMS - 1, minutes = 30.0)
        src.recordShopRate(Platform.DoorDash, items = 24, minutes = ShopRate.MIN_MINUTES - 0.5)
        advanceUntilIdle()

        assertTrue("no entry from noise", src.learnedShopRates.first().isEmpty())
    }

    @Test
    fun `the old un-suffixed GLOBAL keys are dropped, not migrated onto any platform (#588 FIX 3a)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "strategy3.preferences_pb") },
        )
        // Seed the pre-#588 GLOBAL pair directly — as if this datastore file predates the
        // per-platform migration and still carries the old shared mean.
        ds.edit { p ->
            p[doublePreferencesKey("learned_shop_items_per_min")] = 0.8
            p[intPreferencesKey("shop_rate_sample_count")] = 12
        }
        advanceUntilIdle()

        val src = StrategyDataSource(ds)
        assertTrue(
            "the design decision is DROP, not migrate — the old global pair must not surface " +
                "under DoorDash or any other platform",
            src.learnedShopRates.first().isEmpty(),
        )
    }
}
