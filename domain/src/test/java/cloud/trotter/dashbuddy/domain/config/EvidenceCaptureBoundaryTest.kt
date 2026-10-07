package cloud.trotter.dashbuddy.domain.config

import cloud.trotter.dashbuddy.domain.config.EvidenceCaptureBoundary.Verdict
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceCaptureBoundaryTest {
    private val platforms = Platform.entries.filter { it != Platform.Unknown }.toSet()

    @Test
    fun `every enabled delivery platform can capture a readable clean window`() {
        platforms.forEach { platform ->
            assertEquals(Verdict.CAPTURE, decide(frontPlatform = platform))
        }
    }

    @Test
    fun `disabled wins over every other denial`() {
        listOf<Platform?>(null, Platform.Unknown, *platforms.toTypedArray()).forEach { platform ->
            listOf(false, true).forEach { readable ->
                listOf(null, "bank account").forEach { marker ->
                    assertEquals(
                        Verdict.SKIP_DISABLED,
                        decide(false, platform, emptySet(), marker, readable),
                    )
                }
            }
        }
    }

    @Test
    fun `unreadable wins over platform and sensitive denials`() {
        listOf<Platform?>(null, Platform.Unknown, *platforms.toTypedArray()).forEach { platform ->
            listOf(emptySet(), platforms).forEach { enabled ->
                listOf(null, "bank account").forEach { marker ->
                    assertEquals(
                        Verdict.SKIP_UNREADABLE,
                        decide(true, platform, enabled, marker, false),
                    )
                }
            }
        }
    }

    @Test
    fun `null and unknown platforms are denied even if unknown is enabled`() {
        listOf(null, Platform.Unknown).forEach { platform ->
            assertEquals(
                Verdict.SKIP_NOT_DELIVERY_APP,
                decide(frontPlatform = platform, enabledPlatforms = Platform.entries.toSet()),
            )
        }
    }

    @Test
    fun `disabled platforms are denied`() {
        platforms.forEach { platform ->
            assertEquals(
                Verdict.SKIP_NOT_DELIVERY_APP,
                decide(frontPlatform = platform, enabledPlatforms = platforms - platform),
            )
        }
    }

    @Test
    fun `platform denial wins over a sensitive marker`() {
        listOf<Platform?>(null, Platform.Unknown, *platforms.toTypedArray()).forEach { platform ->
            assertEquals(
                Verdict.SKIP_NOT_DELIVERY_APP,
                decide(frontPlatform = platform, enabledPlatforms = emptySet(), sensitiveMarker = "bank account"),
            )
        }
    }

    @Test
    fun `any non-null marker skips an enabled readable window`() {
        listOf("bank account", "normalize_failed", "").forEach { marker ->
            assertEquals(Verdict.SKIP_SENSITIVE, decide(sensitiveMarker = marker))
        }
    }

    private fun decide(
        allowedNow: Boolean = true,
        frontPlatform: Platform? = platforms.first(),
        enabledPlatforms: Set<Platform> = platforms,
        sensitiveMarker: String? = null,
        frontReadable: Boolean = true,
    ): Verdict = EvidenceCaptureBoundary.decide(
        allowedNow, frontPlatform, enabledPlatforms, sensitiveMarker, frontReadable,
    )
}
