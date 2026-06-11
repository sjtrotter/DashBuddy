package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.domain.state.TimelineTaskEntry
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality

/**
 * #353 — the Gson→kotlinx.serialization migration's safety net.
 *
 * 1. Every [ParsedFields] subtype round-trips through the polymorphic sealed
 *    serializer (the journal's parsed column) — the compile-time [coverageGuard]
 *    breaks when a subtype is added until it's covered here too.
 * 2. A fully-populated [AppState] round-trips losslessly (the snapshot path).
 * 3. Schema drift decodes with defaults instead of Gson's silent nulls; true
 *    corruption throws (callers handle loudly).
 */
class SerializationRoundTripTest {

    private val parsedTime = ParsedTime(text = "5:39 PM", time = 1_000_000L)

    private fun allSubtypes(): List<ParsedFields> = listOf(
        ParsedFields.None,
        ParsedFields.IdleFields(zoneName = "Cypress", sessionPay = 12.5, spotSaveDeadline = 99L),
        ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = "h1", payAmount = 7.5, distanceMiles = 3.2,
                badges = setOf(OfferBadge.HIGH_PAYING),
                orders = listOf(
                    ParsedOrder(
                        orderIndex = 0, orderType = OrderType.PICKUP, storeName = "HEB",
                        itemCount = 3, isItemCountEstimated = false, badges = emptySet(),
                    )
                ),
            ),
        ),
        ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
            storeName = "HEB", deadline = parsedTime, itemsShopped = 2,
        ),
        ParsedFields.PostTaskFields(totalPay = 9.75, customerTips = 3.25, isExpanded = true),
        ParsedFields.SessionEndedFields(totalEarnings = 55.25, offersAccepted = 7, offersTotal = 9),
        ParsedFields.PausedFields(remainingText = "29 min", remainingMillis = 1_740_000L),
        ParsedFields.TimelineFields(
            sessionEarnings = 12.0,
            tasks = listOf(TimelineTaskEntry("pickup", "abc", parsedTime, "HEB", isCurrent = true)),
        ),
        ParsedFields.RatingsFields(customerRating = 4.97, lifetimeDeliveries = 812),
        ParsedFields.SensitiveFields(),
        ParsedFields.NoiseFields(),
        ParsedFields.ClickFields(intent = "accept_offer", nodeId = "accept_button"),
        ParsedFields.NotificationFields(intent = "tip", amount = 3.0, storeName = "HEB"),
    )

    /**
     * Compile-time completeness guard: adding a ParsedFields subtype makes this
     * exhaustive `when` fail to compile until it (and [allSubtypes]) cover it.
     */
    @Suppress("unused")
    private fun coverageGuard(p: ParsedFields): String = when (p) {
        is ParsedFields.None -> "covered"
        is ParsedFields.IdleFields -> "covered"
        is ParsedFields.OfferFields -> "covered"
        is ParsedFields.TaskFields -> "covered"
        is ParsedFields.PostTaskFields -> "covered"
        is ParsedFields.SessionEndedFields -> "covered"
        is ParsedFields.PausedFields -> "covered"
        is ParsedFields.TimelineFields -> "covered"
        is ParsedFields.RatingsFields -> "covered"
        is ParsedFields.SensitiveFields -> "covered"
        is ParsedFields.NoiseFields -> "covered"
        is ParsedFields.ClickFields -> "covered"
        is ParsedFields.NotificationFields -> "covered"
    }

    @Test
    fun `every ParsedFields subtype round-trips polymorphically`() {
        val subtypes = allSubtypes()
        assertEquals(13, subtypes.size)

        subtypes.forEach { original ->
            val json = StateJson.encodeToString<ParsedFields>(original)
            val decoded = StateJson.decodeFromString<ParsedFields>(json)
            assertEquals(original, decoded)
            assertEquals(original::class, decoded::class)
        }
    }

    @Test
    fun `a fully-populated AppState snapshot round-trips losslessly`() {
        val evaluation = OfferEvaluation(
            action = OfferAction.ACCEPT, score = 74.0, qualityLevel = OfferQuality.GOOD, payAmount = 7.50,
            fuelCostEstimate = 0.5, netPayAmount = 6.50, distanceMiles = 3.2,
            dollarsPerMile = 2.03, dollarsPerHour = 22.0, estimatedTimeMinutes = 18.0,
            itemCount = 3.0, merchantName = "HEB", warnings = listOf("target high"),
        )
        val offerFields = allSubtypes().filterIsInstance<ParsedFields.OfferFields>().first()
        val state = AppState(
            regions = Regions(
                flow = FlowRegion(
                    flow = Flow.OfferPresented,
                    pendingOffer = PendingOffer(
                        offerHash = "h1", offerFields = offerFields,
                        presentedAt = 1_000L, evaluation = evaluation,
                        returnFlow = Flow.Idle, lastClickIntent = "accept_offer",
                    ),
                    sourceRuleId = "doordash.screen.offer_popup",
                    activePlatform = Platform.DoorDash,
                    lastObservedAt = 1_000L,
                ),
                platforms = mapOf(
                    Platform.DoorDash to PlatformRegion(
                        platform = Platform.DoorDash,
                        mode = Mode.Online,
                        session = Session("session-doordash-1000-0", startedAt = 100L, runningEarnings = 41.25),
                        activeJob = Job(
                            jobId = "job-doordash-1000-1",
                            offerStoreHint = listOf("HEB"),
                            parentOfferHash = "h1",
                            startedAt = 200L,
                        ),
                        activeTask = Task(
                            taskId = "task-doordash-1000-2", jobId = "job-doordash-1000-1",
                            phase = TaskPhase.PICKUP, storeName = "HEB", startedAt = 300L,
                        ),
                        pendingDestructive = PendingDestructive(
                            DestructiveKind.TASK_RETIRE, since = 800L, deadline = 1_000L,
                        ),
                        ratings = RatingsSnapshot(customerRating = 4.97, capturedAt = 900L),
                        lastPostTaskFields = ParsedFields.PostTaskFields(totalPay = 9.75),
                        mintCounter = 3L,
                        lastObservedAt = 1_000L,
                    ),
                ),
            ),
            timestamp = 1_000L,
            correlationVersion = 42L,
        )

        val decoded = StateJson.decodeFromString<AppState>(StateJson.encodeToString(state))

        assertEquals(state, decoded)
    }

    @Test
    fun `schema drift decodes with defaults, not nulls`() {
        // Unknown future field + missing optional regions — must decode with defaults.
        val json = """{"timestamp":123,"correlationVersion":7,"someFutureField":"x"}"""

        val state = StateJson.decodeFromString<AppState>(json)

        assertEquals(7L, state.correlationVersion)
        assertEquals(Flow.Idle, state.regions.flow.flow) // defaulted, NOT null
        assertTrue(state.regions.platforms.isEmpty())
    }

    @Test
    fun `corrupted parsed json throws so callers can be loud`() {
        val result = runCatching {
            StateJson.decodeFromString<ParsedFields>("""{"definitely":"not-a-parsed-fields"}""")
        }
        assertTrue(result.isFailure)
    }
}
