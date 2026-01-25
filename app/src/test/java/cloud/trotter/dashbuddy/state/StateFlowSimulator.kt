package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.OfferMatcher
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import cloud.trotter.dashbuddy.test.LogPreProcessor
import cloud.trotter.dashbuddy.test.LogToUiNodeParser

class StateFlowSimulator {

    private val matchers: List<ScreenMatcher> = listOf(
        OfferMatcher(),
        DeliverySummaryMatcher(),
        IdleMapMatcher(),
    )

    // Start generically
    private var currentState: AppStateV2 = AppStateV2.PostDelivery(
        dashId = "SIMULATION_DASH",
        phase = AppStateV2.PostDelivery.Phase.STABILIZING
    )

    private val activeTimeouts = mutableMapOf<TimeoutType, Long>()
    private var virtualTime = 0L

    fun runSimulation(logFileContent: String) {
        println("🚀 STARTING FULL FIDELITY SIMULATION")
        println("----------------------------------------------------------------")

        val lines = logFileContent.lines()
        println("Scanning ${lines.size} lines of log data...")

        var matchesFound = 0
        var frameCount = 0

        lines.forEachIndexed { index, line ->
            if (line.contains("UI Node Tree:")) {
                // Pass the frame index directly
                val foundMatch = processFrame(frameCount, line)
                if (foundMatch) matchesFound++
                frameCount++
            }
        }

        println("----------------------------------------------------------------")
        println("🏁 SIMULATION COMPLETE")
        println("   - Total Frames Processed: $frameCount")
        println("   - Matches Found: $matchesFound")
    }

    private fun processFrame(frameIndex: Int, logLine: String): Boolean {
        val multiLineTree = LogPreProcessor.process(logLine) ?: return false
        val rootNode = LogToUiNodeParser.parseLog(multiLineTree) ?: return false

        val screenInput = matchers.firstNotNullOfOrNull { it.matches(rootNode) }
            ?: ScreenInfo.Simple(Screen.UNKNOWN)

        val isMatch = screenInput !is ScreenInfo.Simple

        // 3. Advance Time
        virtualTime += 300
        checkTimeouts(frameIndex)

        // 4. ROUTING LOGIC (The Fix)
        // We decide what to do based on the CURRENT state type.
        val transition: Reducer.Transition? = when (val state = currentState) {

            // A. If we are already in the flow, let the reducer handle it
            is AppStateV2.PostDelivery -> {
                PostDeliveryReducer.reduce(state, screenInput)
            }

            // B. If we are Idle/Other, check if we should ENTER the flow
            else -> {
                if (screenInput is ScreenInfo.DeliverySummaryCollapsed) {
                    // Simulate the App triggering the start of the flow
                    PostDeliveryReducer.transitionTo(state, screenInput, isRecovery = false)
                } else if (screenInput is ScreenInfo.DeliveryCompleted) {
                    // Simulate manual recovery entry
                    PostDeliveryReducer.transitionTo(state, screenInput, isRecovery = true)
                } else {
                    // Otherwise, stay in current state (do nothing)
                    null
                }
            }
        }

        // 5. Handle Output
        if (transition != null) {
            val oldState = currentState
            currentState = transition.newState

            // Print if state changed OR if we have effects (even if phase is same)
            // AND specifically if we are in/entering PostDelivery to filter out noise
            if (currentState is AppStateV2.PostDelivery || oldState is AppStateV2.PostDelivery) {
                printStep(frameIndex, oldState, currentState, screenInput, transition.effects)
                transition.effects.forEach { handleEffect(it) }
            }
        }

        return isMatch
    }

    private fun handleEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.ScheduleTimeout -> {
                val triggerTime = virtualTime + effect.durationMs
                activeTimeouts[effect.type] = triggerTime
            }

            is AppEffect.ClickNode -> println("      👆 CLICK: ${effect.description}")
            is AppEffect.LogEvent -> println("      💾 DATABASE SAVE: ${effect.event.eventPayload}")
            is AppEffect.UpdateBubble -> println(
                "      💬 BUBBLE: '${
                    effect.text.replace(
                        "\n",
                        " "
                    )
                }'"
            )

            else -> {}
        }
    }

    private fun checkTimeouts(frameIndex: Int) {
        val iterator = activeTimeouts.iterator()
        while (iterator.hasNext()) {
            val (type, triggerTime) = iterator.next()
            if (virtualTime >= triggerTime) {
                iterator.remove()

                // Only inject timeouts if we are in the correct state to handle them
                // (Prevents crashing if a timer fires after we've exited the flow)
                val state = currentState
                if (state is AppStateV2.PostDelivery) {
                    val transition = PostDeliveryReducer.onTimeout(state, TimeoutEvent(type = type))

                    val oldState = currentState
                    currentState = transition.newState

                    println("Frame #$frameIndex | ⏰ TIMEOUT ($type)")
                    printStep(
                        frameIndex,
                        oldState,
                        currentState,
                        ScreenInfo.Simple(Screen.UNKNOWN),
                        transition.effects
                    )

                    transition.effects.forEach { handleEffect(it) }
                }
            }
        }
    }

    private fun printStep(
        frame: Int,
        from: AppStateV2,
        to: AppStateV2,
        input: ScreenInfo,
        effects: List<AppEffect>
    ) {
        val fromStr =
            if (from is AppStateV2.PostDelivery) "PostDelivery.${from.phase}" else from::class.simpleName
        val toStr =
            if (to is AppStateV2.PostDelivery) "PostDelivery.${to.phase}" else to::class.simpleName

        println("Frame #$frame | Time: ${virtualTime}ms")
        println("   Input: ${input::class.simpleName}")
        println("   State: $fromStr -> $toStr")
        for (effect in effects) {
            println("      $effect")
            handleEffect(effect)
        }
    }
}