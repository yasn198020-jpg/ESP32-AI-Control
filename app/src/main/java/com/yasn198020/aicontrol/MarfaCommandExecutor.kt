package com.yasn198020.aicontrol

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Executes a parsed Marfa command through the existing AppRuntime/MqttManager.
 * It is process-wide so foreground UI and voice service cannot create competing
 * schedulers for the same command.
 */
data class PendingMarfaCommand(
    val id: String,
    val commandText: String,
    val reply: String,
    val executeAtMs: Long
)

class MarfaCommandExecutor private constructor() {
    private val pendingCommands = java.util.concurrent.ConcurrentHashMap<String, PendingMarfaCommand>()
    private val scheduledTasks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MarfaCommandExecutor").apply { isDaemon = true }
        }

    fun execute(
        result: LocalCommandResult,
        runtime: AppRuntime,
        onReply: (String) -> Unit
    ) {
        val initialPlan = runtime.deviceScenarioManager.planCommand(result, runtime.deviceRepository.snapshot())
        initialPlan.blockedReason?.let {
            onReply(it)
            return
        }

        val initialActions = initialPlan.actions
        if (initialActions.isEmpty()) {
            onReply("Не удалось определить действие")
            return
        }

        val snapshot = runtime.deviceRepository.snapshot()
        val target = snapshot
            .flatMap { device -> device.widgets.map { widget -> device to widget } }
            .firstOrNull { it.first.id == result.deviceId && it.second.id == result.widgetId }
        if (target?.second?.type != com.yasn198020.aicontrol.core.WidgetState.Type.BUTTON &&
            target?.second?.value == result.value &&
            initialPlan.prerequisites.isEmpty()
        ) {
            onReply("Уже установлено: " + result.reply.lowercase())
            return
        }

        if (result.delayMs > 0L) {
            val taskId = java.util.UUID.randomUUID().toString()
            val executeAtMs = System.currentTimeMillis() + result.delayMs
            pendingCommands[taskId] = PendingMarfaCommand(
                id = taskId,
                commandText = result.reply,
                reply = result.reply,
                executeAtMs = executeAtMs
            )
            onReply("Запланировано: " + result.reply)
            val future = scheduler.schedule(
                {
                    try {
                        val plan = runtime.deviceScenarioManager.planCommand(
                            result,
                            runtime.deviceRepository.snapshot()
                        )
                        if (plan.blockedReason != null) {
                            onReply(plan.blockedReason)
                            return@schedule
                        }
                        val outcome = perform(plan.actions, runtime)
                        onReply(
                            when {
                                outcome.sent == plan.actions.size && outcome.sent == 1 -> result.reply
                                outcome.sent == plan.actions.size && plan.prerequisites.isNotEmpty() ->
                                    "Выполнено: сначала подготовила условия, затем " + result.reply.lowercase()
                                outcome.sent == plan.actions.size ->
                                    "Выполнено: " + result.reply
                                outcome.sent > 0 ->
                                    "Часть команд выполнена: " + outcome.sent
                                else -> "Не удалось выполнить запланированную команду"
                            }
                        )
                    } finally {
                        pendingCommands.remove(taskId)
                        scheduledTasks.remove(taskId)
                    }
                },
                result.delayMs,
                TimeUnit.MILLISECONDS
            )
            scheduledTasks[taskId] = future
            return
        }

        val outcome = perform(initialActions, runtime)
        onReply(
            when {
                outcome.sent == initialActions.size && initialActions.size == 1 -> result.reply
                outcome.sent == initialActions.size && initialPlan.prerequisites.isNotEmpty() ->
                    "Выполнено: сначала подготовила условия, затем " + result.reply.lowercase()
                outcome.sent == initialActions.size -> "Готово: выполнено " + outcome.sent + " действия"
                outcome.sent > 0 -> "Выполнено " + outcome.sent + " из " + initialActions.size
                else -> "Команда распознана, но MQTT публикация не выполнена"
            }
        )
    }

    fun pendingCommands(): List<PendingMarfaCommand> =
        pendingCommands.values.sortedBy { it.executeAtMs }

    fun cancelScheduled(id: String): Boolean {
        val cancelled = scheduledTasks.remove(id)?.cancel(false) ?: false
        pendingCommands.remove(id)
        return cancelled
    }

    fun shutdown() {
        scheduler.shutdownNow()
        scheduledTasks.clear()
        pendingCommands.clear()
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
            val device = runtime.deviceRepository.snapshot()
                .firstOrNull { it.id == action.deviceId }
            val widget = device?.widgets?.firstOrNull { it.id == action.widgetId }

            if (device == null || widget == null) {
                DiagnosticTrace.system("MARFA action skipped: widget not found " + action.deviceId + "/" + action.widgetId)
                failed++
                return@forEach
            }

            DiagnosticTrace.system("MARFA action TX " + action.deviceId + "/" + action.widgetId + "=" + action.value + " type=" + widget.type.name)

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
