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
        assertEquals(listOf("DOOR", "AUX"), model.rules[0].actions.map { it.targetId })
        assertEquals("DOOR", model.rules[1].actions.single().targetId)
        assertEquals("FAN", model.rules[2].actions.single().targetId)
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

        assertEquals("Unexpected planned actions: " + plan.actions, 2, plan.actions.size)
        assertEquals("MODE", plan.actions[0].widgetId)
        assertEquals("1", plan.actions[0].value)
        assertEquals("DOOR", plan.actions[1].widgetId)
        assertEquals("Unexpected prerequisites: " + plan.prerequisites, 1, plan.prerequisites.size)
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
    @Test
    fun parsesRealIoTManagerScenarioShape() {
        val source = """
            scenario=>if onStart then {
                timer3 = 2
                timer33 = 2
                btn32 = 0
                btn33 = 0
                btn42 = 0
                btn43 = 0
            }

            if vbtn90 == 0 then {
                value27 := 0;
                value37 := 0;
                if dstmp31 > t31 & vbtn68 == 0 then { btn33 = 1; vbtn68 = 1; }
                if dstmp31 < t32 & vbtn68 == 1 then { value83 = 0; btn32 = 1; vbtn68 = 0; }
                if dstmp31 > value22 & vbtn78 == 0 then { btn43 = 1; vbtn78 = 1; }
            }

            if vbtn90 == 1 then { value27 := 1; value37 := 1; timer53 = 7200; }
            if btn59 == 1 & btn32 == 1 then { btn32 = 0; value83 = 1; }
            if btn32 == 1 | btn33 == 1 then timer3 = 6;
        """.trimIndent()

        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        assertTrue(model.parserErrors.isEmpty())
        assertEquals(8, model.rules.size)
        assertTrue(model.identifiers.containsAll(setOf("vbtn90", "dstmp31", "vbtn68", "vbtn78", "btn59", "timer3")))
        assertTrue(model.rules.any { it.actions.any { action -> action.targetId == "vbtn78" && action.rendered.contains("= 1") } })
        assertTrue(model.rules.any { it.actions.any { action -> action.targetId == "timer3" && action.rendered.contains("= 6") } })
    }

    @Test
    fun silentAssignmentWorksWithoutWhitespace() {
        val parsed = IoTScenarioParser.parse("if MODE==1 then VALUE:=2;")
        assertTrue(parsed.errors.isEmpty())
        val model = IoTScenarioSemanticAnalyzer.analyze(parsed)
        assertEquals("VALUE", model.rules.single().actions.single().targetId)
        assertEquals(":=", model.rules.single().actions.single().operator)
    }

}
