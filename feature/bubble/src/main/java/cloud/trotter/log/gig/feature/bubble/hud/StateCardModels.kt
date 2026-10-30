package cloud.trotter.log.gig.feature.bubble.hud

/** Display facts only. Customer identity never crosses into a state card. */
sealed interface StateCardModel {
    val cuesCashTip: Boolean get() = this is DropoffArrived || this is Receipt

    data class Waiting(
        val since: Long?, val sessionStartedAt: Long?, val deliveries: Int?,
        val accepted: Int?, val received: Int?, val lastOffer: LastOffer?,
    ) : StateCardModel

    sealed interface TaskCard : StateCardModel {
        val facts: JobFacts
        val leg: TrackLeg
    }
    data class PickupNav(override val facts: JobFacts, val anchorAt: Long) : TaskCard {
        override val leg get() = TrackLeg(LegKind.PICKUP_NAV, anchorAt, facts.deadlineAt)
    }
    data class PickupArrived(override val facts: JobFacts, val arrivedAt: Long) : TaskCard {
        override val leg get() = TrackLeg(LegKind.PICKUP_ARRIVED, arrivedAt, facts.deadlineAt)
    }
    data class Shopping(val facts: JobFacts, val arrivedAt: Long, val shopped: Int, val total: Int) : StateCardModel
    data class DropoffNav(override val facts: JobFacts, val anchorAt: Long) : TaskCard {
        override val leg get() = TrackLeg(LegKind.DROPOFF_NAV, anchorAt, facts.deadlineAt)
    }
    data class DropoffArrived(override val facts: JobFacts, val arrivedAt: Long) : TaskCard {
        override val leg get() = TrackLeg(LegKind.DROPOFF_ARRIVED, arrivedAt, facts.deadlineAt)
    }
    data class Receipt(
        val store: String, val deliveredAt: Long, val lines: List<ReceiptLine>,
        val tip: Double?, val total: Double?,
    ) : StateCardModel
}

data class JobFacts(val store: String, val deadlineAt: Long?, val pay: Double?, val miles: Double?)
data class LastOffer(val pay: Double?, val outcome: LastOfferOutcome)
enum class LastOfferOutcome { ACCEPTED, DECLINED, EXPIRED }
data class ReceiptLine(val label: String, val amount: Double)
data class TrackLeg(val kind: LegKind, val anchorAt: Long, val deadlineAt: Long?)
enum class LegKind { PICKUP_NAV, PICKUP_ARRIVED, DROPOFF_NAV, DROPOFF_ARRIVED }
enum class TrackIcon { NAV_PIN, STORE, HOUSE, DONE_PIN, STOPWATCH }
data class TrackModel(
    val anchorIcon: TrackIcon, val anchorAt: Long, val endIcon: TrackIcon?, val endAt: Long?,
    val markAt: Long?, val late: Boolean, val fill: Float, val markFraction: Float?,
)
sealed interface Headline {
    data class Clock(val ms: Long) : Headline
    data class Minutes(val n: Int) : Headline
    data object Unknown : Headline
}
