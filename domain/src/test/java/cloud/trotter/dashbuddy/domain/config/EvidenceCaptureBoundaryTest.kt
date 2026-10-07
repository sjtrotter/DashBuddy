package cloud.trotter.dashbuddy.domain.config

import cloud.trotter.dashbuddy.domain.config.EvidenceCaptureBoundary.Kind
import cloud.trotter.dashbuddy.domain.config.EvidenceCaptureBoundary.Verdict
import cloud.trotter.dashbuddy.domain.config.EvidenceCaptureBoundary.WindowSnapshot
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceCaptureBoundaryTest {
    private val platforms = Platform.entries.filter { it != Platform.Unknown }.toSet()
    private val delivery = window()

    @Test
    fun `every enabled platform and considered kind can capture`() {
        platforms.forEach { platform ->
            listOf(Kind.APPLICATION, Kind.OVERLAY, Kind.OTHER).forEach { kind ->
                assertEquals(Verdict.CAPTURE, decide(window(platform = platform, kind = kind)))
            }
        }
        assertEquals(Verdict.CAPTURE, decide(*platforms.map { window(platform = it) }.toTypedArray()))
    }

    @Test
    fun `disabled wins over every combination of denials`() {
        Kind.entries.forEach { kind ->
            Platform.entries.forEach { platform ->
                listOf(false, true).forEach { complete ->
                    listOf(null, "bank account").forEach { marker ->
                        assertEquals(
                            Verdict.SKIP_DISABLED,
                            decide(window(platform, kind, complete, marker), allowedNow = false, enabled = emptySet()),
                        )
                    }
                }
            }
        }
        assertEquals(Verdict.SKIP_DISABLED, decide(allowedNow = false))
    }

    @Test
    fun `no considered window is unreadable even with no enabled platform`() {
        listOf(
            emptyList(),
            listOf(delivery.copy(displayId = 1)),
            listOf(delivery.copy(isOwnApp = true)),
            listOf(delivery.copy(kind = Kind.SYSTEM)),
        ).forEach { windows ->
            assertEquals(Verdict.SKIP_UNREADABLE, decide(*windows.toTypedArray(), enabled = emptySet()))
        }
    }

    @Test
    fun `own bubble above DoorDash and system bars are ignored regardless of their contents`() {
        val bubble = window(Platform.Unknown, Kind.OVERLAY, false, "bank account").copy(isOwnApp = true)
        val bar = bubble.copy(isOwnApp = false, kind = Kind.SYSTEM)
        assertEquals(Verdict.CAPTURE, decide(bubble, bar, delivery))
        assertEquals(Verdict.CAPTURE, decide(delivery, bar, bubble))
    }

    @Test
    fun `keyboard denies even if its package is enabled and its tree is incomplete or sensitive`() {
        assertEquals(
            Verdict.SKIP_NOT_DELIVERY_APP,
            decide(delivery, window(kind = Kind.INPUT_METHOD, complete = false, marker = "bank account")),
        )
        assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(window(kind = Kind.INPUT_METHOD)))
    }

    @Test
    fun `unknown or disabled app denies for every considered kind before readability or sensitivity`() {
        listOf(Kind.APPLICATION, Kind.OVERLAY, Kind.OTHER).forEach { kind ->
            Platform.entries.forEach { platform ->
                listOf(false, true).forEach { complete ->
                    listOf(null, "bank account").forEach { marker ->
                        val foreign = window(platform, kind, complete, marker)
                        assertEquals(
                            Verdict.SKIP_NOT_DELIVERY_APP,
                            decide(delivery, foreign, enabled = platforms - platform),
                        )
                    }
                }
            }
        }
        assertEquals(
            Verdict.SKIP_NOT_DELIVERY_APP,
            decide(window(Platform.Unknown), enabled = Platform.entries.toSet()),
        )
    }

    @Test
    fun `unfocused Uber overlay above Maps denies in either window order`() {
        val uber = window(Platform.Uber, Kind.OVERLAY)
        val maps = window(Platform.fromPackage("com.google.android.apps.maps"))
        assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(uber, maps))
        assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(maps, uber))
    }

    @Test
    fun `split screen DoorDash and browser denies`() {
        assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(delivery, window(Platform.fromPackage("com.android.chrome"))))
    }

    @Test
    fun `foreign picture in picture or overlay denies`() {
        listOf(Kind.APPLICATION, Kind.OVERLAY, Kind.OTHER).forEach { kind ->
            assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(delivery, window(Platform.Unknown, kind)))
        }
    }

    @Test
    fun `phone display is judged when delivery app is on external display`() {
        val external = delivery.copy(displayId = 1)
        val banking = window(Platform.fromPackage("bank.app"), marker = "bank account")
        assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(external, banking))
        assertEquals(Verdict.SKIP_UNREADABLE, decide(external))
        assertEquals(Verdict.CAPTURE, decide(external, banking, targetDisplayId = 1))
    }

    @Test
    fun `foreign sensitive incomplete windows on another display are ignored`() {
        assertEquals(
            Verdict.CAPTURE,
            decide(delivery, window(Platform.Unknown, complete = false, marker = "bank account").copy(displayId = 1)),
        )
    }

    @Test
    fun `partial tree denies before a sensitive marker on any window regardless of order`() {
        val partial = delivery.copy(complete = false)
        val sensitive = delivery.copy(sensitiveMarker = "bank account")
        assertEquals(Verdict.SKIP_UNREADABLE, decide(partial))
        assertEquals(Verdict.SKIP_UNREADABLE, decide(partial, sensitive))
        assertEquals(Verdict.SKIP_UNREADABLE, decide(sensitive, partial))
    }

    @Test
    fun `any non-null sensitive marker on any considered window denies`() {
        listOf("bank account", "normalize_failed", "").forEach { marker ->
            val sensitive = delivery.copy(sensitiveMarker = marker)
            assertEquals(Verdict.SKIP_SENSITIVE, decide(delivery, sensitive))
            assertEquals(Verdict.SKIP_SENSITIVE, decide(sensitive, delivery))
        }
    }

    @Test
    fun `no enabled platform denies`() {
        assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(delivery, enabled = emptySet()))
    }

    private fun window(
        platform: Platform = Platform.DoorDash,
        kind: Kind = Kind.APPLICATION,
        complete: Boolean = true,
        marker: String? = null,
    ) = WindowSnapshot(0, platform, false, kind, complete, marker)

    private fun decide(
        vararg windows: WindowSnapshot,
        allowedNow: Boolean = true,
        enabled: Set<Platform> = platforms,
        targetDisplayId: Int = 0,
    ): Verdict = EvidenceCaptureBoundary.decide(allowedNow, enabled, windows.toList(), targetDisplayId)
}
