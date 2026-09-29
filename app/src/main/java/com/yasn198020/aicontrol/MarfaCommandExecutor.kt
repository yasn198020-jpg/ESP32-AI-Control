package com.yasn198020.aicontrol

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Executes a parsed Marfa command through the existing AppRuntime/MqttManager.
 * It is process-wide so foreground UI and voice service cannot create competing
 * schedulers for the same command.
 */
class MarfaCommandExecutor private constructor() {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MarfaCommandExecutor").apply { isDaemon = true }
        }

    fun execute(
        result: LocalCommandResult,
        runtime: AppRuntime,
        onReply: (String) -> Unit
    ) {
        val plan = runtime.deviceScenarioManager.planCommand(result, runtime.deviceRepository.snapshot())
        plan.blockedReason?.let {
            onReply(it)
            return
        }

        val actions = plan.actions
        if (actions.isEmpty()) {
            onReply("Не удалось определить действие")
            return
        }

        val target = runtime.deviceRepository.snapshot()
            .flatMap { it.widgets.map { widget -> it to widget } }
            .firstOrNull { it.first.id == result.deviceId && it.second.id == result.widgetId }
        if (target?.second?.value == result.value && plan.prerequisites.isEmpty()) {
            onReply("Уже установлено: " + result.reply.lowercase())
            return
        }

        if (result.delayMs > 0L) {
            onReply("Запланировано: " + result.reply)
            scheduler.schedule(
                {
                    val outcome = perform(actions, runtime)
                    onReply(
                        if (outcome.sent > 0 && outcome.failed == 0) {
                            if (plan.prerequisites.isEmpty()) {
                                "Выполнено: " + result.reply
                            } else {
                                "Выполнено: сначала подготовила режим, затем " + result.reply.lowercase()
                            }
                        } else if (outcome.sent > 0) {
                            "Часть команд выполнена: " + outcome.sent
                        } else {
                            "Не удалось выполнить запланированную команду"
                        }
                    )
                },
                result.delayMs,
                TimeUnit.MILLISECONDS
            )
            return
        }

        val outcome = perform(actions, runtime)
        onReply(
            when {
                outcome.sent == actions.size && outcome.sent == 1 -> result.reply
                outcome.sent == actions.size -> "Готово: выполнено " + outcome.sent + " действия"
                outcome.sent > 0 -> "Выполнено " + outcome.sent + " из " + actions.size
                else -> "Команда распознана, но MQTT публикация не выполнена"
            }
        )
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }

    private data class Outcome(val sent: Int, val failed: Int)

    private fun perform(
        actions: List<LocalCommandActionItem>,
        runtime: AppRuntime
    ): Outcome {
        if (!runtime.mqtt.isConnected()) {
            DiagnosticTrace.system("MARFA command blocked: MQTT is not connected")
            return Outcome(0, actions.size)
        }

        var sent = 0
        var failed = 0

        actions.forEach { action ->
            val widget = runtime.deviceRepository.snapshot()
                .firstOrNull { it.id == action.deviceId }
                ?.widgets
                ?.firstOrNull { it.id == action.widgetId }

            if (widget == null) {
                failed++
                return@forEach
            }

            val ok = try {
                when (widget.type) {
                    com.yasn198020.aicontrol.core.WidgetState.Type.TOGGLE,
                    com.yasn198020.aicontrol.core.WidgetState.Type.BUTTON ->
                        runtime.mqtt.publishControl(action.deviceId, action.widgetId, action.value)

                    com.yasn198020.aicontrol.core.WidgetState.Type.INPUT ->
                        widget.topic.isNotBlank() &&
                            runtime.mqtt.publishWidget(widget.topic, action.value)

                    else -> false
                }
            } catch (_: Exception) {
                false
            }

            if (ok) {
                runtime.deviceRepository.setLocalValue(action.deviceId, action.widgetId, action.value)
                sent++
            } else {
                failed++
            }
        }

        return Outcome(sent, failed)
    }

    companion object {
        @Volatile
        private var instance: MarfaCommandExecutor? = null

        fun get(): MarfaCommandExecutor =
            instance ?: synchronized(this) {
                instance ?: MarfaCommandExecutor().also { instance = it }
            }
    }
}
