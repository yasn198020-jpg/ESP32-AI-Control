package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IoTScenarioLanguageTest {
    @Test
    fun parsesBranchesAndBuildsGenericModel() {
        val source = """
            # automatic/manual selector
            if MODE == 1 then { DOOR = 1; AUX = 0; } else DOOR = 0;
            if TEMP > 28 then FAN = 1;
        """.trimIndent()

        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        assertTrue(model.parserErrors.isEmpty())
        assertEquals(3, model.rules.size)
        assertTrue(model.identifiers.containsAll(setOf("MODE", "DOOR", "AUX", "TEMP", "FAN")))
        assertEquals("DOOR", model.rules[0].actions.first().targetId)
        assertEquals("AUX", model.rules[1].actions.first().targetId)
        assertEquals("FAN", model.rules[2].actions.first().targetId)
    }

    @Test
    fun evaluatesConditionFromLiveWidgetValues() {
        val model = IoTScenarioSemanticAnalyzer.analyze(
            IoTScenarioParser.parse("if TEMP > 28 then WINDOW = 1;")
        )
        val rule = model.rules.single()
        val context = IoTScenarioEvaluationContext(
            mapOf("TEMP" to "29.2", "WINDOW" to "0")
        )

        val value = IoTScenarioEvaluator.evaluate(rule.condition.expression, context)

        assertEquals(IoTValue.BooleanValue(true), value)
        assertEquals(IoTValue.Number(1.0), IoTScenarioEvaluator.evaluate(
            rule.actions.single().expression, context
        ))
    }

    @Test
    fun plannerAddsOnlyControllableEqualityPrerequisite() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState(
                        id = "MODE",
                        title = "Режим",
                        type = WidgetState.Type.TOGGLE,
                        value = "0"
                    ),
                    WidgetState(
                        id = "DOOR",
                        title = "Дверь",
                        type = WidgetState.Type.TOGGLE,
                        value = "0"
                    ),
                    WidgetState(
                        id = "TEMP",
                        title = "Температура",
                        type = WidgetState.Type.VALUE,
                        value = "25"
                    )
                )
            )
        )

        val model = IoTScenarioSemanticAnalyzer.analyze(
            IoTScenarioParser.parse(
                "if MODE == 1 then DOOR = 1;"
            )
        )

        val plan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "greenhouse",
            targetWidgetId = "DOOR",
            desiredValue = "1",
            baseActions = listOf(LocalCommandActionItem("greenhouse", "DOOR", "1")),
            devices = devices,
            models = listOf("greenhouse" to model)
        )

        assertEquals(2, plan.actions.size)
        assertEquals("MODE", plan.actions[0].widgetId)
        assertEquals("1", plan.actions[0].value)
        assertEquals("DOOR", plan.actions[1].widgetId)
        assertEquals(1, plan.prerequisites.size)
    }

    @Test
    fun sensorConditionIsNotTurnedIntoAWriteAction() {
        val devices = listOf(
            Device(
                "g",
                "Теплица",
                true,
                listOf(
                    WidgetState("TEMP", "Температура", WidgetState.Type.VALUE, "24"),
                    WidgetState("DOOR", "Дверь", WidgetState.Type.TOGGLE, "0")
                )
            )
        )
        val model = IoTScenarioSemanticAnalyzer.analyze(
            IoTScenarioParser.parse("if TEMP > 28 then DOOR = 1;")
        )

        val plan = IoTScenarioCommandPlanner.plan(
            "g",
            "DOOR",
            "1",
            listOf(LocalCommandActionItem("g", "DOOR", "1")),
            devices,
            listOf("g" to model)
        )

        assertEquals(1, plan.actions.size)
        assertEquals("DOOR", plan.actions.single().widgetId)
        assertEquals(0, plan.prerequisites.size)
    }
}
