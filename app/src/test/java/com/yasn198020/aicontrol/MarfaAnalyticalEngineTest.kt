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
    @Test
    fun spokenDoorDoesNotSelectGenericCloseButtons() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "закрой дверь",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("close1", "закрыть", WidgetState.Type.BUTTON, "0", page = "Теплица 1"),
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "1", page = "Теплица 1"),
                    WidgetState("close2", "закрыть", WidgetState.Type.BUTTON, "0", page = "Теплица 2"),
                    WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "1", page = "Теплица 2")
                ))
            ),
            "0"
        )
        assertEquals(null, result.candidate)
        assertTrue(result.candidates.all { it.widget.title.contains("дверь", ignoreCase = true) })
        assertTrue(result.clarification!!.contains("дверь", ignoreCase = true))
    }

    @Test
    fun duplicateControlsRequireClarificationWithoutPage() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь",
            listOf(
                Device("d1", "Теплица", true, listOf(
                    WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1"),
                    WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2")
                ))
            ),
            "1"
        )
        assertEquals(null, result.candidate)
        assertTrue(result.clarification!!.contains("Теплица 1"))
        assertTrue(result.clarification!!.contains("Теплица 2"))
    }

    @Test
    fun duplicateControlsResolveByExplicitPage() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь на вкладке Теплица 2",
            listOf(
                Device("d1", "Теплица", true, listOf(
                    WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1"),
                    WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2")
                ))
            ),
            "1"
        )
        assertEquals("door2", result.candidate!!.widget.id)
    }


    @Test
    fun spokenContextSelectsDoorOnMatchingTomatoTab() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь помидоры",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы"),
                    WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры")
                ))
            ),
            "1"
        )
        assertEquals("door2", result.candidate!!.widget.id)
        assertEquals("Помидоры", result.candidate!!.widget.page)
    }

    @Test
    fun russianTomatoNominativeResolvesTheTab() {
        val devices = listOf(
            Device("d1", "Дом", true, listOf(
                WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы"),
                WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры")
            ))
        )
                val result = MarfaAnalyticalEngine().resolveControl("открой дверь помидор", devices, "1")
        assertEquals("door2", result.candidate?.widget?.id)

    }
    @Test
    fun russianTomatoGenitiveResolvesTheTab() {
        val devices = listOf(
            Device("d1", "Дом", true, listOf(
                WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы"),
                WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры")
            ))
        )
                val result = MarfaAnalyticalEngine().resolveControl("открой дверь помидора", devices, "1")
        assertEquals("door2", result.candidate?.widget?.id)

    }
    @Test
    fun russianTomatoPluralResolvesTheTab() {
        val devices = listOf(
            Device("d1", "Дом", true, listOf(
                WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы"),
                WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры")
            ))
        )
                val result = MarfaAnalyticalEngine().resolveControl("открой дверь помидоры", devices, "1")
        assertEquals("door2", result.candidate?.widget?.id)

    }
    @Test
    fun russianTomatoInstrumentalResolvesTheTab() {
        val devices = listOf(
            Device("d1", "Дом", true, listOf(
                WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы"),
                WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры")
            ))
        )
                val result = MarfaAnalyticalEngine().resolveControl("открой дверь помидорами", devices, "1")
        assertEquals("door2", result.candidate?.widget?.id)

    }

    @Test
    fun unknownSpokenContextDoesNotFallBackToAnotherDoor() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь помидоры",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы")
                ))
            ),
            "1"
        )
        assertEquals(null, result.candidate)
        assertTrue(result.clarification!!.contains("помидоры"))
    }

    @Test
    fun arbitraryContextWordResolvesFromTheActualPageName() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь баклажана",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы"),
                    WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Баклажаны")
                ))
            ),
            "1"
        )
        assertEquals("door2", result.candidate!!.widget.id)
        assertEquals("Баклажаны", result.candidate!!.widget.page)
    }

    @Test
    fun arbitraryMultiWordContextMatchesRussianInflection() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь на первом этаже",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Первый этаж"),
                    WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Второй этаж")
                ))
            ),
            "1"
        )
        assertEquals("door1", result.candidate!!.widget.id)
        assertEquals("Первый этаж", result.candidate!!.widget.page)
    }

    @Test
    fun unknownArbitraryContextStillRequiresClarification() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь баклажана",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы")
                ))
            ),
            "1"
        )
        assertEquals(null, result.candidate)
        assertTrue(result.clarification!!.contains("баклажана"))
    }

    @Test
    fun arbitraryContextWordAlsoResolvesSensor() {
        val result = MarfaAnalyticalEngine().resolveSensor(
            "какая температура баклажана",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("t1", "Температура", WidgetState.Type.VALUE, "24", page = "Огурцы", unit = "°C"),
                    WidgetState("t2", "Температура", WidgetState.Type.VALUE, "25", page = "Баклажаны", unit = "°C")
                ))
            )
        )
        assertEquals("t2", result.candidate!!.widget.id)
    }

    @Test
    fun exactElementIdOverridesTitle() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь id door2",
            listOf(
                Device("d1", "Теплица", true, listOf(
                    WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1"),
                    WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2")
                ))
            ),
            "1"
        )
        assertEquals("door2", result.candidate!!.widget.id)
    }

    @Test
    fun commandEngineDelegatesControlResolutionToAnalyticalEngine() {
        val result = MarfaCommandEngine().parse(
            "открой дверь на вкладке Теплица 2",
            listOf(
                Device("d1", "Теплица", true, listOf(
                    WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1"),
                    WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2")
                ))
            )
        )
        assertEquals(LocalCommandAction.CONTROL, result.action)
        assertEquals("door2", result.widgetId)
    }

    @Test
    fun clarificationReplyByOrdinalResolvesThePreviouslyPresentedCandidate() {
        val devices = listOf(
            Device("d1", "Теплица", true, listOf(
                WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1", order = 1),
                WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2", order = 2)
            ))
        )
        val engine = MarfaCommandEngine()

        val first = engine.parse("открой дверь", devices)
        assertEquals(LocalCommandAction.CLARIFY, first.action)

        val clarified = engine.parse("вторую", devices)
        assertEquals(LocalCommandAction.CONTROL, clarified.action)
        assertEquals("door2", clarified.widgetId)
        assertEquals("1", clarified.value)
    }

    @Test
    fun clarificationCanUseAContextualPagePhraseAndKeepTheOriginalAction() {
        val devices = listOf(
            Device("d1", "Дом", true, listOf(
                WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Первый этаж", order = 1),
                WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Второй этаж", order = 2)
            ))
        )
        val engine = MarfaCommandEngine()

        assertEquals(LocalCommandAction.CLARIFY, engine.parse("открой дверь", devices).action)

        val clarified = engine.parse("на второй этаж", devices)
        assertEquals(LocalCommandAction.CONTROL, clarified.action)
        assertEquals("door2", clarified.widgetId)
        assertEquals("1", clarified.value)
    }

    @Test
    fun correctionCanSelectAnotherPreviouslyResolvedAlternative() {
        val devices = listOf(
            Device("d1", "Теплица", true, listOf(
                WidgetState("door1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 1", order = 1),
                WidgetState("door2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Теплица 2", order = 2)
            ))
        )
        val engine = MarfaCommandEngine()

        val first = engine.parse("открой дверь", devices)
        assertEquals(LocalCommandAction.CLARIFY, first.action)

        val selected = engine.parse("вторую", devices)
        assertEquals(LocalCommandAction.CONTROL, selected.action)
        assertEquals("door2", selected.widgetId)

        val corrected = engine.parse("нет, первую", devices)
        assertEquals(LocalCommandAction.CONTROL, corrected.action)
        assertEquals("door1", corrected.widgetId)
        assertEquals("1", corrected.value)
    }

    @Test
    fun sensorContextInflectionMatchesWidgetTitle() {
        val result = MarfaAnalyticalEngine().resolveSensor(
            "скажи температуру огурцов",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("t1", "Огурцы", WidgetState.Type.VALUE, "24", page = "Температура", unit = "°C"),
                    WidgetState("t2", "Помидоры", WidgetState.Type.VALUE, "27", page = "Температура", unit = "°C"),
                    WidgetState("t3", "Улица", WidgetState.Type.VALUE, "12", page = "Температура", unit = "°C")
                ))
            )
        )
        assertEquals("t1", result.candidate?.widget?.id)
    }

    @Test
    fun controlContextInflectionMatchesSemanticPage() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой дверь огурцов",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door1", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("door2", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🍅")
                ))
            ),
            "1"
        )
        assertEquals("door1", result.candidate?.widget?.id)
    }

    @Test
    fun openContextIgnoresAutomaticModeAndLimitSwitches() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "открой огурцы",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("vent", "закрыта открыта форточка", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("auto", "автомат управление", WidgetState.Type.TOGGLE, "1", page = "Теплиц 🥒"),
                    WidgetState("limitDoor", "концевик двери", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("limitVent", "концевик форточки", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒")
                ))
            ),
            "1"
        )
        assertEquals(null, result.candidate)
        assertTrue(result.candidates.all {
            it.widget.id == "door" || it.widget.id == "vent"
        })
        assertTrue(result.clarification!!.contains("дверь"))
        assertTrue(result.clarification!!.contains("форточка"))
        assertTrue(!result.clarification!!.contains("автомат"))
        assertTrue(!result.clarification!!.contains("концевик"))
    }

    @Test
    fun offContextIgnoresFeedbackWidgetsAndKeepsRealControl() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "выключи огурцы",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("door", "закрыта открыта дверь", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("vent", "закрыта открыта форточка", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("auto", "автомат управление", WidgetState.Type.TOGGLE, "1", page = "Теплиц 🥒"),
                    WidgetState("limitDoor", "концевик двери", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒"),
                    WidgetState("limitVent", "концевик форточки", WidgetState.Type.TOGGLE, "0", page = "Теплиц 🥒")
                ))
            ),
            "0"
        )
        assertEquals("auto", result.candidate?.widget?.id)
        assertTrue(result.candidates.none {
            it.widget.id == "door" ||
                it.widget.id == "vent" ||
                it.widget.id == "limitDoor" ||
                it.widget.id == "limitVent"
        })
    }

    @Test
    fun semanticContextSearchesAllMatchingTabs() {
        val result = MarfaAnalyticalEngine().resolveControl(
            "выключи огурцы",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("auto1", "автомат управление", WidgetState.Type.TOGGLE, "1", page = "Теплица 🥒"),
                    WidgetState("auto2", "автомат управление", WidgetState.Type.TOGGLE, "1", page = "⚙️ 🥒")
                ))
            ),
            "0"
        )
        assertEquals(null, result.candidate)
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.all { it.widget.title == "автомат управление" })
        assertTrue(result.clarification!!.contains("Теплица"))
        assertTrue(result.clarification!!.contains("⚙️"))
    }

    @Test
    fun genericContextWithoutUniqueTargetRequiresClarification() {
        val result = MarfaCommandEngine().parse(
            "включи огурцы",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("c1", "Кнопка", WidgetState.Type.TOGGLE, "0", page = "Огурцы 1", order = 1),
                    WidgetState("c2", "Кнопка", WidgetState.Type.TOGGLE, "0", page = "Огурцы 2", order = 2)
                ))
            )
        )
        assertEquals(LocalCommandAction.CLARIFY, result.action)
    }

    @Test
    fun valueQuestionWithObjectNameIsResolvedLocally() {
        val result = MarfaCommandEngine().parse(
            "скажи температуру огурцов",
            listOf(
                Device("d1", "Дом", true, listOf(
                    WidgetState("t1", "Огурцы", WidgetState.Type.VALUE, "24.5", page = "Температура", unit = "°C"),
                    WidgetState("t2", "Помидоры", WidgetState.Type.VALUE, "27", page = "Температура", unit = "°C")
                ))
            )
        )
        assertEquals(LocalCommandAction.READ_VALUE, result.action)
        assertEquals("t1", result.widgetId)
        assertTrue(result.reply.contains("24,5"))
    }
}
