package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.data.pay.PayParser
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashAlongTheWayParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashPausedParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashSummaryParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DeliverySummaryParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DropoffNavigationParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DropoffPreArrivalParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.IdleMapParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.OfferParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupArrivalParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupNavigationParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupPreArrivalParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupShoppingParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.SensitiveScreenParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.SetDashEndTimeParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.WaitingForOfferParser

object TestParserFactory {

    // Mimics the 'injectedParsers' set provided by Hilt
    fun createAllParsers(): Set<ScreenParser> {
        val payParser = PayParser()
        return setOf(
            DashAlongTheWayParser(),
            DashPausedParser(),
            DashSummaryParser(),
            DeliverySummaryParser(payParser),
            DropoffNavigationParser(),
            DropoffPreArrivalParser(),
            IdleMapParser(),
            OfferParser(),
            PickupArrivalParser(),
            PickupNavigationParser(),
            PickupPreArrivalParser(),
            PickupShoppingParser(),
            SensitiveScreenParser(),
            SetDashEndTimeParser(),
            WaitingForOfferParser()
        )
    }

    fun createParserMap(): Map<Screen, ScreenParser> =
        createAllParsers().associateBy { it.targetScreen }
}
