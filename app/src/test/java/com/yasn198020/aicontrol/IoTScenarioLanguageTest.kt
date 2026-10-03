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
            models = listOf(
                StoredDeviceScenario(
                    title = "Тест",
                    source = "if MODE == 1 then DOOR = 1;"
                ) to model
            )
        )

        assertEquals("Unexpected planned actions: " + plan.actions, 2, plan.actions.size)
        assertEquals("MODE", plan.actions[0].widgetId)
        assertEquals("1", plan.actions[0].value)
        assertEquals("DOOR", plan.actions[1].widgetId)
        assertEquals("Unexpected prerequisites: " + plan.prerequisites, 1, plan.prerequisites.size)
    }


    @Test
    fun plannerUsesModelIdentifiersWhenSensorMetadataIsEmpty() {
        val devices = listOf(
            Device(
                "dev-a",
                "Устройство",
                true,
                listOf(
                    WidgetState("MODE", "Автоматический режим", WidgetState.Type.TOGGLE, "1"),
                    WidgetState("TARGET", "Привод", WidgetState.Type.TOGGLE, "0")
                )
            )
        )
        val source = "if MODE == 0 then TARGET = 1;"
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        val plan = IoTScenarioCommandPlanner.plan(
            "dev-a",
            "TARGET",
            "1",
            listOf(LocalCommandActionItem("dev-a", "TARGET", "1")),
            devices,
            listOf(
                StoredDeviceScenario(
                    title = "Без списка датчиков",
                    source = source,
                    sensorIds = emptyList()
                ) to model
            )
        )

        assertEquals(listOf("TARGET"), plan.actions.map { it.widgetId })
        assertTrue(plan.prerequisites.isEmpty())
    }

    @Test
    fun plannerCanChooseCheaperBranchOfOrCondition() {
        val devices = listOf(
            Device(
                "dev-a",
                "Устройство",
                true,
                listOf(
                    WidgetState("MODE_A", "Режим A", WidgetState.Type.TOGGLE, "1"),
                    WidgetState("MODE_B", "Режим B", WidgetState.Type.TOGGLE, "1"),
                    WidgetState("TARGET", "Привод", WidgetState.Type.TOGGLE, "0")
                )
            )
        )
        val source = "if MODE_A == 0 | MODE_B == 0 then TARGET = 1;"
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        val plan = IoTScenarioCommandPlanner.plan(
            "dev-a",
            "TARGET",
            "1",
            listOf(LocalCommandActionItem("dev-a", "TARGET", "1")),
            devices,
            listOf(StoredDeviceScenario(title = "OR", source = source) to model)
        )

        assertEquals(2, plan.actions.size)
        assertTrue(
            plan.prerequisites.single().widgetId == "MODE_A" ||
                plan.prerequisites.single().widgetId == "MODE_B"
        )
        assertEquals("0", plan.prerequisites.single().value)
    }

    @Test
    fun plannerHandlesNegatedBooleanDependency() {
        val devices = listOf(
            Device(
                "dev-a",
                "Устройство",
                true,
                listOf(
                    WidgetState("AUTO", "Автоматика", WidgetState.Type.TOGGLE, "1"),
                    WidgetState("TARGET", "Привод", WidgetState.Type.TOGGLE, "0")
                )
            )
        )
        val source = "if !AUTO then TARGET = 1;"
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        val plan = IoTScenarioCommandPlanner.plan(
            "dev-a",
            "TARGET",
            "1",
            listOf(LocalCommandActionItem("dev-a", "TARGET", "1")),
            devices,
            listOf(StoredDeviceScenario(title = "NOT", source = source) to model)
        )

        assertEquals("AUTO", plan.prerequisites.single().widgetId)
        assertEquals("0", plan.prerequisites.single().value)
        assertEquals("TARGET", plan.actions.last().widgetId)
    }

    @Test
    fun manualModeUsesScenarioDiscoveredActuator() {
        val devices = listOf(
            Device(
                "door",
                "Дверь",
                true,
                listOf(
                    WidgetState("vbtn90", "Ручной режим", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("btn42", "закрыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("dstmp31", "Температура", WidgetState.Type.VALUE, "20"),
                    WidgetState("value22", "Порог", WidgetState.Type.VALUE, "25")
                )
            )
        )
        val source = """
            if vbtn90 == 0 then {
                if dstmp31 > value22 & vbtn78 == 0 then { btn43 = 1; vbtn78 = 1; }
            }
            if vbtn90 == 1 then { value37 := 1; }
            if value37 == 1 then {
                if vbtn78 == 0 then { btn42 = 1; }
                if vbtn78 == 1 then { btn43 = 1; }
            }
        """.trimIndent()
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        val plan = IoTScenarioCommandPlanner.plan(
            "door",
            "vbtn78",
            "1",
            listOf(LocalCommandActionItem("door", "vbtn78", "1")),
            devices,
            listOf(StoredDeviceScenario(title = "Door", source = source) to model)
        )

        assertEquals(listOf("vbtn90", "vbtn78"), plan.actions.map { it.widgetId })
        assertEquals(listOf("1", "1"), plan.actions.map { it.value })
        assertEquals("vbtn90", plan.prerequisites.first().widgetId)
        assertEquals("1", plan.prerequisites.first().value)
    }

    @Test
    fun reverseResolutionAlsoHandlesCloseActuatorState() {
        val devices = listOf(
            Device(
                "door",
                "Дверь",
                true,
                listOf(
                    WidgetState("vbtn90", "автомат управление", WidgetState.Type.TOGGLE, "1"),
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "1"),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("btn42", "закрыть дверь", WidgetState.Type.BUTTON, "0")
                )
            )
        )
        val source = """
            if vbtn90 == 1 then {
                if vbtn78 == 0 then { btn42 = 1; btn43 = 0; vbtn78 = 0; }
                if vbtn78 == 1 then { btn43 = 1; btn42 = 0; vbtn78 = 1; }
            }
        """.trimIndent()
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        val resolved = IoTScenarioCommandPlanner.resolveLogicalTargetFromActuator(
            targetWidgetId = "btn43",
            desiredValue = "0",
            devices = devices,
            models = listOf(StoredDeviceScenario(title = "Door", source = source) to model)
        )

        assertEquals("vbtn78", resolved?.first)
        assertEquals("0", resolved?.second)
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
            listOf(StoredDeviceScenario(title = "Sensor", source = "if TEMP > 28 then DOOR = 1;") to model)
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


    @Test
    fun realDoorScenarioPlansStateThenRelayAndEnablesManualModeWhenAutoIsOn() {
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("vbtn90", "автомат управление", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("btn42", "закрыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("value37", "служебная переменная", WidgetState.Type.VALUE, "0"),
                    WidgetState("dstmp31", "Помидоры", WidgetState.Type.VALUE, "25"),
                    WidgetState("value22", "температура открытия двери", WidgetState.Type.INPUT, "25")
                )
            )
        )

        val source = """
            scenario=>if vbtn90 == 0 then {
                if dstmp31 > value22 & vbtn78 == 0 then { btn43 = 1; vbtn78 = 1; }
                if dstmp31 < value96 & vbtn78 == 1 then { btn42 = 1; vbtn78 = 0; }
            }
            if vbtn90 == 1 then {
                value37 := 1;
                if vbtn78 == 0 then { btn42 = 1; }
                if vbtn78 == 1 then { btn43 = 1; }
            }
        """.trimIndent()

        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))
        assertTrue(model.parserErrors.isEmpty())

        val plan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "greenhouse",
            targetWidgetId = "vbtn78",
            desiredValue = "1",
            baseActions = listOf(LocalCommandActionItem("greenhouse", "vbtn78", "1")),
            devices = devices,
            models = listOf(StoredDeviceScenario(title = "Дверь", source = source) to model)
        )

        assertEquals(
            listOf("vbtn90", "vbtn78"),
            plan.actions.map { it.widgetId }
        )
        assertEquals(listOf("1", "1"), plan.actions.map { it.value })
        assertEquals("vbtn90", plan.prerequisites.single().widgetId)
        assertEquals("1", plan.prerequisites.single().value)

        val closePlan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "greenhouse",
            targetWidgetId = "vbtn78",
            desiredValue = "0",
            baseActions = listOf(LocalCommandActionItem("greenhouse", "vbtn78", "0")),
            devices = devices,
            models = listOf(StoredDeviceScenario(title = "Дверь", source = source) to model)
        )

        assertEquals(
            listOf("vbtn90", "vbtn78"),
            closePlan.actions.map { it.widgetId }
        )
    }


    @Test
    fun plannerDoesNotUseTargetDeviceToDisambiguateDuplicateElementIds() {
        val devices = listOf(
            Device(
                "device-a",
                "Первая ESP",
                true,
                listOf(
                    WidgetState("vbtn90", "Ручной режим", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("DOOR", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0")
                )
            ),
            Device(
                "device-b",
                "Вторая ESP",
                true,
                listOf(
                    WidgetState("DOOR", "другая дверь", WidgetState.Type.TOGGLE, "0")
                )
            )
        )

        val source = "if vbtn90 == 1 then { if DOOR == 1 then { btn43 = 1; } }"
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))
        assertTrue(model.parserErrors.isEmpty())

        val plan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "device-b",
            targetWidgetId = "DOOR",
            desiredValue = "1",
            baseActions = listOf(LocalCommandActionItem("device-b", "DOOR", "1")),
            devices = devices,
            models = listOf(
                StoredDeviceScenario(title = "Чужой сценарий", source = source) to model
            )
        )

        assertTrue(
            "Duplicate element ID must never be resolved by targetDeviceId: " + plan,
            plan.blockedReason != null || plan.actions.isEmpty()
        )
    }

    @Test
    fun plannerDoesNotCrossBindScenarioActuatorToAnotherDevice() {
        val devices = listOf(
            Device(
                "door-device",
                "Дверь",
                true,
                listOf(
                    WidgetState("DOOR", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("vbtn90", "Ручной режим", WidgetState.Type.TOGGLE, "0")
                )
            ),
            Device(
                "other-device",
                "Другая ESP",
                true,
                listOf(
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0")
                )
            )
        )

        val source = "if DOOR == 1 then btn43 = 1;"
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))
        assertTrue(model.parserErrors.isEmpty())

        val plan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "door-device",
            targetWidgetId = "DOOR",
            desiredValue = "1",
            baseActions = listOf(LocalCommandActionItem("door-device", "DOOR", "1")),
            devices = devices,
            models = listOf(
                StoredDeviceScenario(title = "Перекрёстная привязка", source = source) to model
            )
        )

        assertEquals(
            "An actuator on another ESP must not become Marfa's command",
            listOf("DOOR"),
            plan.actions.map { it.widgetId }
        )
    }


    @Test
    fun plannerSwitchesAutomaticGateToManualBeforeActuatorCommand() {
        val devices = listOf(
            Device(
                "door-esp",
                "Дверь",
                true,
                listOf(
                    WidgetState("vbtn90", "Ручной режим", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("btn42", "закрыть дверь", WidgetState.Type.BUTTON, "0")
                )
            )
        )
        val source = """
            if vbtn90 == 0 then {
                if vbtn78 == 0 then { btn43 = 1; vbtn78 = 1; }
                if vbtn78 == 1 then { btn42 = 1; vbtn78 = 0; }
            }
        """.trimIndent()
        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

        val plan = IoTScenarioCommandPlanner.plan(
            "door-esp",
            "vbtn78",
            "1",
            listOf(LocalCommandActionItem("door-esp", "vbtn78", "1")),
            devices,
            listOf(StoredDeviceScenario(title = "Дверь", source = source) to model)
        )

        assertEquals(listOf("vbtn90", "vbtn78"), plan.actions.map { it.widgetId })
        assertEquals(listOf("1", "1"), plan.actions.map { it.value })
        assertEquals("vbtn90", plan.prerequisites.single().widgetId)
        assertEquals("1", plan.prerequisites.single().value)
    }


    @Test
    fun plannerReversesPhysicalDoorButtonToLogicalState() {
        val devices = listOf(
            Device(
                "door-esp",
                "Теплица",
                true,
                listOf(
                    WidgetState("vbtn90", "автоматическое управление", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("vbtn78", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("btn43", "открыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("btn42", "закрыть дверь", WidgetState.Type.BUTTON, "0")
                )
            )
        )

        val source = """
            if vbtn90 == 0 then {
                if vbtn78 == 0 then { btn43 = 1; vbtn78 = 1; }
                if vbtn78 == 1 then { btn42 = 1; vbtn78 = 0; }
            }
            if vbtn90 == 1 then {
                if vbtn78 == 0 then { btn42 = 1; }
                if vbtn78 == 1 then { btn43 = 1; }
            }
        """.trimIndent()

        val model = IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))
        assertTrue(model.parserErrors.isEmpty())

        // Simulate the natural-language engine having selected the physical
        // "open door" button by its title. The planner must undo that early
        // choice using the scenario and issue the logical state command.
        val result = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "door-esp",
            targetWidgetId = "btn43",
            desiredValue = "1",
            baseActions = listOf(LocalCommandActionItem("door-esp", "btn43", "1")),
            devices = devices,
            models = listOf(StoredDeviceScenario(title = "Дверь", source = source) to model)
        )

        assertEquals(
            "Physical btn43 must never become Marfa's direct command",
            listOf("vbtn90", "vbtn78"),
            result.actions.map { it.widgetId }
        )
        assertEquals(listOf("1", "1"), result.actions.map { it.value })
        assertEquals("vbtn90", result.prerequisites[0].widgetId)
        assertEquals("1", result.prerequisites[0].value)
    }

    @Test
    fun plannerFallsBackToDirectControlWhenTargetIdIsAbsentFromScenario() {
        val devices = listOf(
            Device(
                "plain-device",
                "Обычная ESP",
                true,
                listOf(
                    WidgetState("relay99", "включить реле", WidgetState.Type.BUTTON, "0")
                )
            )
        )

        val scenarioSource = "if OTHER_ID == 1 then OTHER_RELAY = 1;"
        val model = IoTScenarioSemanticAnalyzer.analyze(
            IoTScenarioParser.parse(scenarioSource)
        )
        assertTrue(model.parserErrors.isEmpty())
        assertTrue(!model.identifiers.contains("relay99"))

        val plan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "plain-device",
            targetWidgetId = "relay99",
            desiredValue = "1",
            baseActions = listOf(
                LocalCommandActionItem("plain-device", "relay99", "1")
            ),
            devices = devices,
            models = listOf(
                StoredDeviceScenario(title = "Чужой сценарий", source = scenarioSource) to model
            )
        )

        assertEquals(
            "Missing scenario ID must keep the original direct command",
            listOf("relay99"),
            plan.actions.map { it.widgetId }
        )
        assertEquals("1", plan.actions.single().value)
        assertTrue(plan.prerequisites.isEmpty())
        assertTrue(plan.blockedReason == null)
        assertTrue(!plan.resolvedByScenario)
    }

    @Test
    fun plannerUsesScenarioIdWhenItContainsTheTargetControl() {
        val devices = listOf(
            Device(
                "scenario-device",
                "ESP со сценарием",
                true,
                listOf(
                    WidgetState("relay99", "включить реле", WidgetState.Type.BUTTON, "0")
                )
            )
        )

        val scenarioSource = "if relay99 == 0 then relay99 = 1;"
        val model = IoTScenarioSemanticAnalyzer.analyze(
            IoTScenarioParser.parse(scenarioSource)
        )
        assertTrue(model.parserErrors.isEmpty())
        assertTrue(model.identifiers.contains("relay99"))

        val plan = IoTScenarioCommandPlanner.plan(
            targetDeviceId = "scenario-device",
            targetWidgetId = "relay99",
            desiredValue = "1",
            baseActions = listOf(
                LocalCommandActionItem("scenario-device", "relay99", "1")
            ),
            devices = devices,
            models = listOf(
                StoredDeviceScenario(title = "Сценарий", source = scenarioSource) to model
            )
        )

        assertEquals(listOf("relay99"), plan.actions.map { it.widgetId })
        assertEquals("1", plan.actions.single().value)
        assertTrue(plan.blockedReason == null)
    }


}
