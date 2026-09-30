package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarfaAnalyticalEngineTest {
    private fun devices() = listOf(
        Device("g1", "Теплица", true, listOf(
            WidgetState("t1", "Температура", WidgetState.Type.VALUE, "25", page = "Помидоры", unit = "°C", order = 1),
            WidgetState("t2", "Температура", WidgetState.Type.VALUE, "26", page = "Огурцы", unit = "°C", order = 2),
            WidgetState("h1", "Влажность", WidgetState.Type.VALUE, "61", page = "Помидоры", unit = "%", order = 3)
        ))
    )

    @Test
    fun identicalSensorsRequireClarification() {
        val result = MarfaAnalyticalEngine().resolveSensor("какая температура", devices())
        assertEquals(null, result.candidate)
        assertTrue(result.clarification!!.contains("Помидоры"))
        assertTrue(result.clarification!!.contains("Огурцы"))
    }

    @Test
    fun pageResolvesIdenticalSensors() {
        val result = MarfaAnalyticalEngine().resolveSensor("какая температура на вкладке Огурцы", devices())
        assertEquals("t2", result.candidate!!.widget.id)
    }

    @Test
    fun differentSensorTypesAreResolvedAnalytically() {
        val result = MarfaAnalyticalEngine().resolveSensor("какая влажность", devices())
        assertEquals("h1", result.candidate!!.widget.id)
    }

    @Test
    fun explicitIdIsAuthoritative() {
        val result = MarfaAnalyticalEngine().resolveSensor("покажи датчик t2", devices())
        assertEquals("t2", result.candidate!!.widget.id)
    }

    @Test
    fun commandEngineAsksWhenSensorIsAmbiguous() {
        val result = MarfaCommandEngine().parse("какая температура", devices())
        assertEquals(LocalCommandAction.CLARIFY, result.action)
    }
}
