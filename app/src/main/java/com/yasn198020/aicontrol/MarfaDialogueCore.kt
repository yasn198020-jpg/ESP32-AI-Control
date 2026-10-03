package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import java.util.Locale

/**
 * Central state machine for Marfa's conversational turn handling.
 *
 * Command resolution remains in MarfaIntelligence/MarfaCommandEngine.
 * This class owns the conversation state around that resolver so UI and
 * background voice service do not implement their own copies.
 */
class MarfaDialogueCore(
    private val interpreter: suspend (String, List<Device>) -> LocalCommandResult
) {
    enum class OutcomeKind {
        CONTINUE,
        EXECUTE_CONTROL,
        SAVE_SMART_RULE,
        ANSWER,
        CANCEL
    }

    data class Outcome(
        val kind: OutcomeKind,
        val result: LocalCommandResult? = null,
        val reply: String = ""
    )

    private sealed class Pending {
        data class Control(
            val result: LocalCommandResult,
            val sourceCommand: String
        ) : Pending()
        data class SmartRule(val result: LocalCommandResult) : Pending()
    }

    private var pending: Pending? = null

    suspend fun process(text: String, devices: List<Device>): Outcome {
        val command = text.trim()
        if (command.isBlank()) {
            return Outcome(
                OutcomeKind.ANSWER,
                LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "Я не услышала команду"),
                "Я не услышала команду"
            )
        }

        when (val current = pending) {
            is Pending.Control -> {
                if (MarfaDialogueLanguage.isConfirmation(command)) {
                    pending = null
                    return Outcome(OutcomeKind.EXECUTE_CONTROL, current.result, current.result.reply)
                }

                if (MarfaDialogueLanguage.isRejection(command)) {
                    pending = null
                    return Outcome(OutcomeKind.CANCEL, current.result, "Хорошо, не выполняю")
                }

                /*
                 * A pending control is still the same conversational task.
                 * A non-terminal answer must therefore refine the original
                 * command, not start a new standalone command.
                 *
                 * Example:
                 *   "открой дверь" -> "Выполнить?"
                 *   "помидоры"    -> resolve "открой дверь помидоры"
                 *
                 * This is generic dialogue state, not a rule for any particular
                 * object, page or word.
                 */
                val refinedCommand = listOf(current.sourceCommand, command)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                val refined = interpreter(refinedCommand, devices)
                return when {
                    refined.action == LocalCommandAction.CONTROL && refined.needsConfirmation -> {
                        pending = Pending.Control(refined, refinedCommand)
                        Outcome(
                            OutcomeKind.CONTINUE,
                            refined,
                            "Поняла уточнение. ${refined.reply}. Выполнить? Скажите да или нет"
                        )
                    }
                    refined.action == LocalCommandAction.CLARIFY -> {
                        Outcome(OutcomeKind.CONTINUE, refined, refined.reply)
                    }
                    refined.action == LocalCommandAction.SMART_RULE -> {
                        pending = Pending.SmartRule(refined)
                        Outcome(
                            OutcomeKind.CONTINUE,
                            refined,
                            "${refined.reply}. Сохранить это правило? Скажите да или нет"
                        )
                    }
                    else -> Outcome(
                        OutcomeKind.CONTINUE,
                        refined,
                        "Выполнить это? Скажите да или нет"
                    )
                }
            }

            is Pending.SmartRule -> {
                if (MarfaDialogueLanguage.isSmartRuleConfirmation(command)) {
                    pending = null
                    return Outcome(OutcomeKind.SAVE_SMART_RULE, current.result, current.result.reply)
                }

                if (MarfaDialogueLanguage.isSmartRuleRejection(command)) {
                    pending = null
                    return Outcome(OutcomeKind.CANCEL, current.result, "Правило не сохранено")
                }

                return Outcome(
                    OutcomeKind.CONTINUE,
                    current.result,
                    "Сохранить предыдущее правило? Скажите да или нет"
                )
            }

            null -> Unit
        }

        val result = interpreter(command, devices)
        return when (result.action) {
            LocalCommandAction.SMART_RULE -> {
                pending = Pending.SmartRule(result)
                Outcome(
                    OutcomeKind.CONTINUE,
                    result,
                    "${result.reply}. Сохранить это правило? Скажите да или нет"
                )
            }

            LocalCommandAction.CONTROL -> {
                if (result.needsConfirmation) {
                    pending = Pending.Control(result, command)
                    Outcome(
                        OutcomeKind.CONTINUE,
                        result,
                        "Поняла. ${result.reply}. Выполнить? Скажите да или нет"
                    )
                } else {
                    Outcome(OutcomeKind.EXECUTE_CONTROL, result, result.reply)
                }
            }

            LocalCommandAction.CLARIFY -> {
                Outcome(OutcomeKind.CONTINUE, result, result.reply)
            }

            LocalCommandAction.READ_VALUE,
            LocalCommandAction.NOT_FOUND -> {
                Outcome(OutcomeKind.ANSWER, result, result.reply)
            }
        }
    }

    /**
     * Used by the background service after it restores a pending smart rule
     * from persistent storage.
     */
    fun restoreSmartRule(result: LocalCommandResult?) {
        pending = if (
            result != null &&
            result.action == LocalCommandAction.SMART_RULE &&
            result.conditionWidgetId.isNotBlank() &&
            result.actionWidgetId.isNotBlank()
        ) {
            Pending.SmartRule(result)
        } else {
            null
        }
    }

    fun reset() {
        pending = null
    }
}

/**
 * One shared vocabulary layer for conversational decisions.
 * Any future variant of "all/yes/no" is added here once and used everywhere.
 */
object MarfaDialogueLanguage {
    private val allAnswers = setOf(
        "оба", "обе", "обоих", "обеих", "все", "всех",
        "все варианты", "оба варианта"
    )

    private val commandConfirmations = setOf(
        "да", "давай", "выполняй", "выполни", "подтверждаю",
        "верно", "точно", "сделай"
    )

    private val commandRejections = setOf(
        "нет", "отмена", "отменить", "не надо",
        "не делай", "не выполняй", "стоп"
    )

    private val smartRuleConfirmations = setOf(
        "да", "сохрани", "сохранить", "подтверждаю",
        "верно", "правильно", "согласен", "согласна"
    )

    private val smartRuleRejections = setOf(
        "нет", "отмена", "отменить",
        "не сохраняй", "не сохранять", "не надо"
    )

    fun isSelectAll(text: String): Boolean =
        normalize(text) in allAnswers

    fun isConfirmation(text: String): Boolean {
        val value = normalize(text)
        return value in commandConfirmations || value.contains("давай")
    }

    fun isRejection(text: String): Boolean =
        normalize(text) in commandRejections

    fun isSmartRuleConfirmation(text: String): Boolean =
        normalize(text) in smartRuleConfirmations

    fun isSmartRuleRejection(text: String): Boolean =
        normalize(text) in smartRuleRejections

    private fun normalize(value: String): String =
        value.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
