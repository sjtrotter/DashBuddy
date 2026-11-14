package cloud.trotter.log.gig.state

import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** #1625: [ShiftActivity] from the state machine, through the one owner of "a shift is running" ([shiftRunning]). */
@Singleton
class StateShiftActivity @Inject constructor(stateManager: StateManagerV2) : ShiftActivity {
    override val shiftLive: Flow<Boolean> = stateManager.state.map(::shiftRunning).distinctUntilChanged()
}
