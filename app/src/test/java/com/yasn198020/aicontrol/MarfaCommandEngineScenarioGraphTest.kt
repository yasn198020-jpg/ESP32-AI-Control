package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarfaCommandEngineScenarioGraphTest {

    @Test
    fun graphReceivesAllAnalyticalCandidatesAndSelectsTarget() {
        val door = WidgetState("door", "закрытие двери", WidgetState.Type.TOGGLE, "0")
        val vent = WidgetState("vent", "закрытие форточки", WidgetState.Type.TOGGLE, "0")
        val device = Device("d", "Теплица", true, listOf(door, vent))

        var received = emptyList<MarfaAnalyticalEngine.ControlCandidate>()
        val engine = MarfaCommandEngine(
            scenarioGraphResolver = { candidates, _, _ ->
                received = candidates
                val target = candidates.single { it.widget.id == "door" }
                ScenarioGraphCommandResolution(
                    target = target,
                    resolvedByScenario = true
                )
            }
        )

        val result = engine.parse("закрой дверь", listOf(device))

        assertTrue(received.size >= 2)
        assertTrue(received.any { it.widget.id == "door" })
        assertEquals("door", result.widgetId)
    }
}
