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
    fun sensorClarificationAcceptsAll() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("temp-c", "Огурцы", WidgetState.Type.VALUE, "24", page = "Температура", unit = "°C"),
                    WidgetState("temp-s", "Улица", WidgetState.Type.VALUE, "12", page = "Температура", unit = "°C"),
                    WidgetState("temp-t", "Помидоры", WidgetState.Type.VALUE, "27", page = "Температура", unit = "°C")
                )
            )
        )
        val engine = MarfaCommandEngine()
        assertEquals(LocalCommandAction.CLARIFY, engine.parse("какая температура", devices).action)

        val result = engine.parse("все", devices)
        assertEquals(LocalCommandAction.READ_VALUE, result.action)
        assertTrue(result.reply.contains("Огурцы"))
        assertTrue(result.reply.contains("Улица"))
        assertTrue(result.reply.contains("Помидоры"))
        assertTrue(result.reply.contains("24"))
        assertTrue(result.reply.contains("12"))
        assertTrue(result.reply.contains("27"))
    }

    @Test
    fun clarificationIsMergedBackIntoOriginalCommand() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState(
                        id = "auto-tomatoes",
                        title = "Автомат управления",
                        type = WidgetState.Type.TOGGLE,
                        value = "1",
                        page = "🍅",
                        order = 1
                    ),
                    WidgetState(
                        id = "auto-cucumbers",
                        title = "Автомат управления",
                        type = WidgetState.Type.TOGGLE,
                        value = "1",
                        page = "🥒",
                        order = 2
                    )
                )
            )
        )

        val engine = MarfaCommandEngine()

        val clarification = engine.parse("Выключи автомат управления", devices)
        assertEquals(LocalCommandAction.CLARIFY, clarification.action)

        val result = engine.parse("огурцами", devices)
        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("auto-cucumbers", result.widgetId)
        assertEquals("0", result.value)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun clarificationAcceptsRussianInflectionAndEmojiPageContext() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState(
                        id = "auto-tomatoes",
                        title = "Автомат управления",
                        type = WidgetState.Type.TOGGLE,
                        value = "1",
                        page = "🍅",
                        order = 1
                    ),
                    WidgetState(
                        id = "auto-cucumbers",
                        title = "Автомат управления",
                        type = WidgetState.Type.TOGGLE,
                        value = "1",
                        page = "🥒",
                        order = 2
                    )
                )
            )
        )

        val engine = MarfaCommandEngine()
        assertEquals(
            LocalCommandAction.CLARIFY,
            engine.parse("Выключи автомат управления", devices).action
        )

        val result = engine.parse("у огурцов", devices)
        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("auto-cucumbers", result.widgetId)
        assertEquals("0", result.value)
    }

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

        assertEquals("smartRule result=$result", LocalCommandAction.SMART_RULE, result.action)
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
    @Test
    fun doorCommandSelectsLogicalStateWidget() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", order = 1),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0", order = 2),
                    WidgetState("btn42", "закрыть дверь", WidgetState.Type.BUTTON, "0", order = 3)
                )
            )
        )

        val open = MarfaCommandEngine().parse("Открой дверь", devices)
        assertEquals(LocalCommandAction.CONTROL, open.action)
        assertEquals("vbtn78", open.widgetId)
        assertEquals("1", open.value)
        assertEquals("Открываю: дверь", open.reply)

        val close = MarfaCommandEngine().parse("Закрой дверь", devices)
        assertEquals(LocalCommandAction.CONTROL, close.action)
        assertEquals("vbtn78", close.widgetId)
        assertEquals("0", close.value)
        assertEquals("Закрываю: дверь", close.reply)
    }


    @Test
    fun directRelayCommandStillSelectsActuator() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", order = 1),
                    WidgetState("btn43", "реле двери", WidgetState.Type.BUTTON, "0", order = 2)
                )
            )
        )

        val result = MarfaCommandEngine().parse("Включи реле двери", devices)

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("btn43", result.widgetId)
        assertEquals("1", result.value)
    }

    @Test
    fun explicitPageDisambiguatesIdenticalTitles() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1", order = 1),
                    WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2", order = 2)
                )
            )
        )

        val result = MarfaCommandEngine().parse(
            "Открой дверь на вкладке Теплица 2",
            devices
        )

        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("door2", result.widgetId)
        assertEquals("1", result.value)
    }

    @Test
    fun identicalTitlesWithoutPageRemainAmbiguous() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1", order = 1),
                    WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2", order = 2)
                )
            )
        )

        val result = MarfaCommandEngine().parse("Открой дверь", devices)

        assertEquals(LocalCommandAction.CLARIFY, result.action)
    }


}
