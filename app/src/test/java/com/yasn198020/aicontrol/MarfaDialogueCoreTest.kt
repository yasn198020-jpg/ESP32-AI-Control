package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarfaDialogueCoreTest {

    private fun sensors(): List<Device> = listOf(
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

    private fun doors(): List<Device> = listOf(
        Device(
            id = "greenhouse",
            name = "Теплица",
            online = true,
            widgets = listOf(
                WidgetState("door-1", "дверь", WidgetState.Type.TOGGLE, "0", page = "Огурцы", order = 1),
                WidgetState("door-2", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры", order = 2)
            )
        )
    )

    private fun core(): MarfaDialogueCore {
        val manager = LocalCommandManager()
        return MarfaDialogueCore { command, devices ->
            manager.interpret(command, devices)
        }
    }

    @Test
    fun allSensorsStayInsideOneDialogue() = kotlinx.coroutines.runBlocking {
        val dialogue = core()

        val first = dialogue.process("какая температура", sensors())
        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, first.kind)
        assertTrue(first.reply.contains("Огурцы"))
        assertTrue(first.reply.contains("Улица"))
        assertTrue(first.reply.contains("Помидоры"))

        val all = dialogue.process("все", sensors())
        assertEquals(MarfaDialogueCore.OutcomeKind.ANSWER, all.kind)
        assertEquals(LocalCommandAction.READ_VALUE, all.result?.action)
        assertTrue(all.reply.contains("Огурцы"))
        assertTrue(all.reply.contains("24"))
        assertTrue(all.reply.contains("Улица"))
        assertTrue(all.reply.contains("12"))
        assertTrue(all.reply.contains("Помидоры"))
        assertTrue(all.reply.contains("27"))
    }

    @Test
    fun allControlsThenOneConfirmationAreCentralized() = kotlinx.coroutines.runBlocking {
        val dialogue = core()

        val clarify = dialogue.process("открой дверь", doors())
        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, clarify.kind)
        assertEquals(LocalCommandAction.CLARIFY, clarify.result?.action)

        val all = dialogue.process("оба", doors())
        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, all.kind)
        assertEquals(LocalCommandAction.CONTROL, all.result?.action)
        assertTrue(all.result?.needsConfirmation == true)
        assertEquals(2, all.result?.actionItems?.size)

        val confirmed = dialogue.process("да", doors())
        assertEquals(MarfaDialogueCore.OutcomeKind.EXECUTE_CONTROL, confirmed.kind)
        assertEquals(2, confirmed.result?.actionItems?.size)
    }

    @Test
    fun refinementWithLeadingNoIsNotAccidentalCancellation() = kotlinx.coroutines.runBlocking {
        val dialogue = core()

        dialogue.process("открой дверь", doors())
        dialogue.process("первую", doors())

        val refined = dialogue.process("нет, вторую", doors())
        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, refined.kind)
        assertEquals(LocalCommandAction.CONTROL, refined.result?.action)
        assertEquals("door-2", refined.result?.widgetId)
    }

    @Test
    fun followUpContextRefinesTheSameControlBeforeConfirmation() = kotlinx.coroutines.runBlocking {
        val dialogue = core()

        val first = dialogue.process("открой дверь", listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("door", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры"),
                    WidgetState("vent", "форточка", WidgetState.Type.TOGGLE, "0", page = "Огурцы")
                )
            )
        ))

        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, first.kind)
        assertEquals(LocalCommandAction.CONTROL, first.result?.action)
        assertTrue(first.result?.needsConfirmation == true)
        assertEquals("door", first.result?.widgetId)

        val refined = dialogue.process("помидоры", listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("door", "дверь", WidgetState.Type.TOGGLE, "0", page = "Помидоры"),
                    WidgetState("vent", "форточка", WidgetState.Type.TOGGLE, "0", page = "Огурцы")
                )
            )
        ))

        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, refined.kind)
        assertEquals(LocalCommandAction.CONTROL, refined.result?.action)
        assertTrue(refined.result?.needsConfirmation == true)
        assertEquals("door", refined.result?.widgetId)
    }

    @Test
    fun inflectedActionAndFollowUpShareOneDialogueContext() = kotlinx.coroutines.runBlocking {
        val dialogue = core()
        val devices = listOf(
            Device(
                id = "greenhouse",
                name = "Теплица",
                online = true,
                widgets = listOf(
                    WidgetState("door-c", "дверь", WidgetState.Type.TOGGLE, "1", page = "Огурцы"),
                    WidgetState("door-t", "дверь", WidgetState.Type.TOGGLE, "1", page = "Помидоры")
                )
            )
        )

        val first = dialogue.process("закрыта дверь", devices)
        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, first.kind)
        assertEquals(LocalCommandAction.CLARIFY, first.result?.action)

        val refined = dialogue.process("помидоры", devices)
        assertEquals(MarfaDialogueCore.OutcomeKind.CONTINUE, refined.kind)
        assertEquals(LocalCommandAction.CONTROL, refined.result?.action)
        assertEquals("door-t", refined.result?.widgetId)
        assertEquals("0", refined.result?.value)
        assertTrue(refined.result?.needsConfirmation == true)
    }

    @Test
    fun languageRulesHaveOneSource() {
        assertTrue(MarfaDialogueLanguage.isSelectAll("все"))
        assertTrue(MarfaDialogueLanguage.isSelectAll("оба"))
        assertTrue(MarfaDialogueLanguage.isConfirmation("да"))
        assertTrue(MarfaDialogueLanguage.isRejection("нет"))
        assertTrue(MarfaDialogueLanguage.isSmartRuleConfirmation("сохрани"))
        assertTrue(MarfaDialogueLanguage.isSmartRuleRejection("не сохраняй"))
    }
}
