package cloud.trotter.dashbuddy.test.suites

import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashAlongTheWayRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashPausedRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashSummaryRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DeliverySummaryCollapsedRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DeliverySummaryExpandedRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DropoffNavigationRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DropoffPreArrivalRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.IdleMapRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.LoadingScreenRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.OfferMatcherRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.OnDashMapRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupArrivalRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupNavigationRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupPreArrivalRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupShoppingRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.ScheduleRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.SensitiveScreenRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.SetDashEndTimeRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.TimelineRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.WaitingForOfferRegressionTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    // Map screens
    LoadingScreenRegressionTest::class,
    IdleMapRegressionTest::class,
    OnDashMapRegressionTest::class,
    WaitingForOfferRegressionTest::class,
    DashAlongTheWayRegressionTest::class,

    // Offer flow
    OfferMatcherRegressionTest::class,

    // Pickup flow
    PickupNavigationRegressionTest::class,
    PickupPreArrivalRegressionTest::class,
    PickupArrivalRegressionTest::class,
    PickupShoppingRegressionTest::class,

    // Dropoff flow
    DropoffNavigationRegressionTest::class,
    DropoffPreArrivalRegressionTest::class,

    // Delivery completion
    DeliverySummaryCollapsedRegressionTest::class,
    DeliverySummaryExpandedRegressionTest::class,

    // Dash lifecycle
    SetDashEndTimeRegressionTest::class,
    DashPausedRegressionTest::class,
    DashSummaryRegressionTest::class,

    // UI / Navigation screens
    ScheduleRegressionTest::class,
    TimelineRegressionTest::class,

    // Safety / Sensitive
    SensitiveScreenRegressionTest::class,
)
class AllMatchersSuite
