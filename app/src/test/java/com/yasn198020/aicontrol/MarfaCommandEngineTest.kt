package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarfaCommandEngineTest {
    private fun catalog(): List<Device> = listOf(
        Device(
            id = "greenhouse",
            name = "Теплица",
            online = true,
            widgets = listOf(
                WidgetState(
                    id = "temp1",
                    title = "Температура помидоров",
                    type = WidgetState.Type.VALUE,
                    value = "29.5",
                    page = "Помидоры",
                    unit = "°C",
                    order = 1
                ),
                WidgetState(
                    id = "vent1",
                    title = "Форточка помидоров",
                    type = WidgetState.Type.TOGGLE,
                    value = "0",
                    page = "Помидоры",
                    order = 2
                ),
                WidgetState(
                    id = "vent2",
                    title = "Форточка огурцов",
                    type = WidgetState.Type.TOGGLE,
                    value = "0",
                    page = "Огурцы",
                    order = 3
                ),
                WidgetState(
                    id = "pump",
                    title = "Насос полива",
                    type = WidgetState.Type.BUTTON,
                    value = "0",
                    page = "Общее",
                    order = 4
                )
            )
        )
    )

    @Test
    fun directControlResolvesRussianEntity() {
        val result = MarfaCommandEngine().parse("Открой, пожалуйста, форточку помидоров", catalog())

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("greenhouse", result.deviceId)
        assertEquals("vent1", result.widgetId)
        assertEquals("1", result.value)
        assertEquals(0L, result.delayMs)
    }

    @Test
    fun numericRelativeDelayIsParsedForTwentyMinutes() {
        val result = MarfaCommandEngine().parse("Открой форточку помидоров через 20 минут", catalog())

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("vent1", result.widgetId)
        assertEquals("1", result.value)
        assertTrue(result.delayMs in 19L * 60_000L..21L * 60_000L)
    }

    @Test
    fun relativeDelayIsParsedWithoutImmediateExecution() {
        val result = MarfaCommandEngine().parse("Выключи насос через двадцать минут", catalog())

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("pump", result.widgetId)
        assertEquals("0", result.value)
        assertTrue(result.delayMs in 19L * 60_000L..21L * 60_000L)
    }

    @Test
    fun standaloneDelayContinuesPreviousAction() {
        val engine = MarfaCommandEngine()
        engine.parse("Открой форточку помидоров", catalog())

        val result = engine.parse("через 20 минут", catalog())

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("vent1", result.widgetId)
        assertEquals("1", result.value)
        assertTrue(result.delayMs in 19L * 60_000L..21L * 60_000L)
    }

    @Test
    fun pronounContinuesPreviousTarget() {
        val engine = MarfaCommandEngine()
        engine.parse("Открой форточку помидоров", catalog())

        val result = engine.parse("закрой её", catalog())

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("vent1", result.widgetId)
        assertEquals("0", result.value)
    }

    @Test
    fun smartRuleCreatesExistingScenarioFields() {
        val result = MarfaCommandEngine().parse(
            "Если температура помидоров выше 28 градусов, открой форточку помидоров",
            catalog()
        )

        assertEquals(LocalCommandAction.SMART_RULE, result.action)
        assertEquals("temp1", result.conditionWidgetId)
        assertEquals(">", result.conditionOperator)
        assertEquals(28.0, result.conditionThreshold, 0.0001)
        assertEquals("vent1", result.actionWidgetId)
        assertEquals("1", result.actionValue)
    }

    @Test
    fun ordinalReferenceResolvesSecondControl() {
        val engine = MarfaCommandEngine()

        val result = engine.parse("закрой вторую", catalog())

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("vent2", result.widgetId)
        assertEquals("0", result.value)
    }

    @Test
    fun valueQuestionUsesSensorUnit() {
        val result = MarfaCommandEngine().parse("Какая температура у помидоров?", catalog())

        assertEquals(LocalCommandAction.READ_VALUE, result.action)
        assertEquals("temp1", result.widgetId)
        assertTrue(result.reply.contains("29,5"))
        assertTrue(result.reply.contains("градуса"))
    }
}
