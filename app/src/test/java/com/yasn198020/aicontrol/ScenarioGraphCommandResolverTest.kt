package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioGraphCommandResolverTest {

    private fun doorDevices(): List<Device> = listOf(
        Device(
            "door-esp",
            "Теплица",
            true,
            listOf(
                WidgetState("vbtn90", "автомат управление", WidgetState.Type.TOGGLE, "0"),
                WidgetState("vbtn78", "дверь", WidgetState.Type.TOGGLE, "0"),
                WidgetState("btn43", "выход", WidgetState.Type.BUTTON, "0"),
                WidgetState("btn42", "выход закрытия", WidgetState.Type.BUTTON, "0"),
                WidgetState("TEMP", "температура", WidgetState.Type.VALUE, "20")
            )
        )
    )

    private fun model(source: String): Pair<StoredDeviceScenario, DeviceScenarioModel> {
        val parsed = IoTScenarioParser.parse(source)
        val model = IoTScenarioSemanticAnalyzer.analyze(parsed)
        assertTrue(model.parserErrors.isEmpty())
        return StoredDeviceScenario(title = "test", source = source) to model
    }

    private fun candidate(id: String, score: Int = 10): MarfaAnalyticalEngine.ControlCandidate {
        val widget = doorDevices().single().widgets.single { it.id == id }
        return MarfaAnalyticalEngine.ControlCandidate(
            doorDevices().single(),
            widget,
            score,
            emptyList()
        )
    }

    @Test
    fun physicalActuatorResolvesToHighestLogicalControllerAndFindsModeBlocker() {
        val source = """
            if vbtn90 == 0 then {
                if TEMP > 25 & vbtn78 == 0 then { btn43 = 1; vbtn78 = 1; }
            }
        """.trimIndent()

        val result = ScenarioGraphCommandResolver().resolve(
            candidates = listOf(candidate("btn43")),
            desiredValue = "1",
            devices = doorDevices(),
            models = listOf(model(source))
        )

        assertNotNull(result.target)
        assertEquals("vbtn78", result.target!!.widget.id)
        assertEquals(listOf("vbtn90"), result.prerequisites.map { it.widgetId })
        assertEquals("1", result.prerequisites.single().value)
        assertTrue(result.blockedReason == null)
        assertTrue(result.resolvedByScenario)
    }

    @Test
    fun graphClimbsThroughMultipleControllableLevels() {
        val devices = listOf(
            Device(
                "d",
                "Теплица",
                true,
                listOf(
                    WidgetState("top", "главное управление", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("middle", "управление дверью", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("relay", "реле", WidgetState.Type.BUTTON, "0")
                )
            )
        )
        val source = """
            if top == 1 then { middle = 1; }
            if middle == 1 then { relay = 1; }
        """.trimIndent()
        val result = ScenarioGraphCommandResolver().resolve(
            listOf(
                MarfaAnalyticalEngine.ControlCandidate(
                    devices.single(),
                    devices.single().widgets.single { it.id == "relay" },
                    10,
                    emptyList()
                )
            ),
            "1",
            devices,
            listOf(model(source))
        )

        assertEquals("top", result.target?.widget?.id)
    }

    @Test
    fun twoIndependentControllersAreNotGuessed() {
        val devices = listOf(
            Device(
                "d",
                "Теплица",
                true,
                listOf(
                    WidgetState("a", "управление A", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("b", "управление B", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("relay", "реле", WidgetState.Type.BUTTON, "0")
                )
            )
        )
        val source = """
            if a == 1 then { relay = 1; }
            if b == 1 then { relay = 1; }
        """.trimIndent()
        val d = devices.single()
        val relay = d.widgets.single { it.id == "relay" }
        val result = ScenarioGraphCommandResolver().resolve(
            listOf(
                MarfaAnalyticalEngine.ControlCandidate(d, relay, 10, emptyList())
            ),
            "1",
            devices,
            listOf(model(source))
        )

        assertTrue(result.target == null)
        assertTrue(result.blockedReason?.contains("несколько") == true)
        assertEquals(2, result.alternatives.size)
    }

    @Test
    fun duplicateElementIdNeverCrossBindsGraph() {
        val devices = listOf(
            Device("a", "A", true, listOf(WidgetState("relay", "реле A", WidgetState.Type.BUTTON, "0"))),
            Device("b", "B", true, listOf(WidgetState("relay", "реле B", WidgetState.Type.BUTTON, "0")))
        )
        val d = devices.first()
        val result = ScenarioGraphCommandResolver().resolve(
            listOf(MarfaAnalyticalEngine.ControlCandidate(d, d.widgets.single(), 10, emptyList())),
            "1",
            devices,
            listOf(model("if control == 1 then relay = 1;"))
        )

        assertTrue(result.target == null)
        assertTrue(result.blockedReason == null)
    }
    @Test
    fun conditionOnlyMeasurementDoesNotCompeteWithScenarioController() {
        val devices = listOf(
            Device(
                "d",
                "Теплица",
                true,
                listOf(
                    WidgetState("tempClose", "🌡 закрытия двери", WidgetState.Type.TOGGLE, "0"),
                    WidgetState("closeDoor", "закрыть дверь", WidgetState.Type.BUTTON, "0"),
                    WidgetState("relay", "реле закрытия", WidgetState.Type.BUTTON, "0")
                )
            )
        )
        val d = devices.single()
        val source = """
            if closeDoor == 1 then { relay = 1; closeDoor = 1; }
            if tempClose == 1 then { relay = 1; }
        """.trimIndent()

        val result = ScenarioGraphCommandResolver().resolve(
            candidates = listOf(
                MarfaAnalyticalEngine.ControlCandidate(d, d.widgets[0], 30, emptyList()),
                MarfaAnalyticalEngine.ControlCandidate(d, d.widgets[1], 29, emptyList()),
                MarfaAnalyticalEngine.ControlCandidate(d, d.widgets[2], 10, emptyList())
            ),
            desiredValue = "1",
            devices = devices,
            models = listOf(model(source))
        )

        assertEquals("closeDoor", result.target?.widget?.id)
        assertTrue(result.blockedReason == null)
    }

    @Test
    fun uncontrolledRelayRemainsDirectTargetWhenOnlySensorConditionExists() {
        val devices = listOf(
            Device(
                "d",
                "Теплица",
                true,
                listOf(
                    WidgetState("temp", "температура", WidgetState.Type.VALUE, "20"),
                    WidgetState("relay", "реле", WidgetState.Type.BUTTON, "0")
                )
            )
        )
        val d = devices.single()
        val source = """
            if temp > 25 then { relay = 1; }
        """.trimIndent()

        val result = ScenarioGraphCommandResolver().resolve(
            candidates = listOf(
                MarfaAnalyticalEngine.ControlCandidate(d, d.widgets[1], 10, emptyList())
            ),
            desiredValue = "1",
            devices = devices,
            models = listOf(model(source))
        )

        assertEquals("relay", result.target?.widget?.id)
        assertTrue(result.blockedReason == null)
    }
