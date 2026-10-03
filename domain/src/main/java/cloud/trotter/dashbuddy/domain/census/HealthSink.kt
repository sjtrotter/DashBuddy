package cloud.trotter.dashbuddy.domain.census

/** Fed by the pipeline per admitted SCREEN frame; implementations must never throw (the pipeline wraps anyway). */
interface HealthSink {
    fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?)
    fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?)

    object NoOp : HealthSink {
        override fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?) = Unit
        override fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?) = Unit
    }
}
