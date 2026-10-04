package cloud.trotter.dashbuddy.domain.capture

import cloud.trotter.dashbuddy.domain.state.Platform

/** Debug trusted-install transport seam. Release is structurally inert. */
interface CensusEnvelopeSink {
    val isEnabled: Boolean
    /** Park an UNKNOWN screen envelope until its skeleton pairs it (same frame). Bounded; never throws. */
    fun hold(captureId: String, platform: Platform, envelopeJson: String)
    /** Pair a held envelope with its skeleton fingerprint. True when accepted for spooling. */
    fun pair(captureId: String, fingerprint: String): Boolean
}

object NoOpCensusEnvelopeSink : CensusEnvelopeSink {
    override val isEnabled = false
    override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
    override fun pair(captureId: String, fingerprint: String) = false
}
