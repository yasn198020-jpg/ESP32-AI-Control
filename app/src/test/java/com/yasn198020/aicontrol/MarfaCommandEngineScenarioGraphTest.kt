package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MarfaCommandEngineScenarioGraphTest {

    @Test
    fun graphReceivesOnlyAlreadyResolvedSemanticTarget() {
        val door = WidgetState("door", "закрытие двери", WidgetState.Type.TOGGLE, "0")
        val vent = WidgetState("vent", "закрытие форточки", WidgetState.Type.TOGGLE, "0")
        val cucumber = WidgetState("button", "Кнопка 🥒", WidgetState.Type.TOGGLE, "0")
        val device = Device("d", "Теплица", true, listOf(door, vent, cucumber))

        var received = emptyList<MarfaAnalyticalEngine.ControlCandidate>()
        val engine = MarfaCommandEngine(
            scenarioGraphResolver = { candidates, _, _ ->
                received = candidates
                ScenarioGraphCommandResolution(
                    target = candidates.single(),
                    resolvedByScenario = true
                )
            }
        )

        val result = engine.parse("закрой дверь огурцов", listOf(device))

        assertEquals(1, received.size)
        assertEquals("door", received.single().widget.id)
        assertEquals("door", result.widgetId)
    }
}
