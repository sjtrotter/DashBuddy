package cloud.trotter.dashbuddy.test.util

import android.app.Application
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.state.effects.OfferActionReceiver
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import java.time.Clock

/**
 * #1271 — the minimal Robolectric application the end-to-end harness runs under
 * (`@Config(application = ReplayApplication::class)`).
 *
 * It is NOT `DashBuddyApplication`: no Hilt graph boots, no workers, no projector collector, no
 * network. It exists for one reason — the production [OfferActionReceiver.onReceive] resolves its
 * collaborators with `EntryPointAccessors.fromApplication`, and Hilt's accessor reads them from a
 * [GeneratedComponentManager]. [E2ESessionReplay] installs a component that hands the receiver the
 * harness's REAL [StateManagerV2] and its virtual clock, so a notification tap runs the receiver's
 * own code end to end (its immediate heads-up cancel included).
 */
class ReplayApplication : Application(), GeneratedComponentManager<Any> {

    /** The receiver's entry point, backed by one harness instance. */
    class Component(
        private val manager: StateManagerV2,
        private val clock: Clock,
    ) : GeneratedComponent, OfferActionReceiver.Deps {
        override fun stateManager(): StateManagerV2 = manager
        override fun clock(): Clock = clock
    }

    /** Installed by [E2ESessionReplay] on construction, cleared on close. */
    var component: Component? = null

    override fun generatedComponent(): Any =
        checkNotNull(component) { "no E2ESessionReplay is installed on this ReplayApplication" }
}
