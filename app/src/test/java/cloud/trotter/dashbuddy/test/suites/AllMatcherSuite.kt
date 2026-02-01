package cloud.trotter.dashbuddy.test.suites

import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashPausedRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.IdleMapRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.LoadingScreenRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.TimelineRegressionTest
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.WaitingForOfferRegressionTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    DashPausedRegressionTest::class,
    IdleMapRegressionTest::class,
    LoadingScreenRegressionTest::class,
    TimelineRegressionTest::class,
    WaitingForOfferRegressionTest::class,
    // Add new screen tests here...
)
class AllMatchersSuite