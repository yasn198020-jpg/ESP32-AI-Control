package com.yasn198020.aicontrol.scenarios

class ScenarioEngine(
private val store: ScenarioStore,
    private val onTrigger: (Scenario, String, Double) -> Unit,
    private val onVerificationResult: (Scenario, Boolean, String) -> Unit
) {
    private val values = mutableMapOf<String, Double>()
    private val verificationTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    private fun key(deviceId: String, widgetId: String) = "$deviceId/$widgetId"

    private fun conditionMatches(condition: ScenarioCondition): Boolean? {
        val value = values[key(condition.deviceId, condition.widgetId)] ?: return null
        return when (condition.operator) {
            ">" -> value > condition.threshold
            ">=" -> value >= condition.threshold
            "<" -> value < condition.threshold
            "<=" -> value <= condition.threshold
            "=" -> kotlin.math.abs(value - condition.threshold) < 0.000001
            else -> false
        }
    }

    private fun expressionMatches(conditions: List<ScenarioCondition>): Boolean? {
        if (conditions.isEmpty()) return false
        var result = conditionMatches(conditions.first()) ?: return null
        for (i in 1 until conditions.size) {
            val current = conditionMatches(conditions[i]) ?: return null
            result = if (conditions[i].connector == "OR") result || current else result && current
        }
        return result
    }

    private fun verificationMatches(scenario: Scenario, value: Double): Boolean {
        return when (scenario.verifyOperator) {
            ">" -> value > scenario.verifyValue
            ">=" -> value >= scenario.verifyValue
            "<" -> value < scenario.verifyValue
            "<=" -> value <= scenario.verifyValue
            else -> kotlin.math.abs(value - scenario.verifyValue) < 0.000001
        }
    }

    private fun startVerification(scenario: Scenario) {
        if (!scenario.verifyEnabled || scenario.verifyDeviceId.isBlank() || scenario.verifyWidgetId.isBlank()) return
        verificationTasks.remove(scenario.id)?.cancel(false)
        verificationTasks[scenario.id] = scheduler.schedule({
            synchronized(this) {
                verificationTasks.remove(scenario.id)
            }
            onVerificationResult(scenario, false, "")
        }, scenario.verifyTimeoutSec.coerceIn(1, 300).toLong(), java.util.concurrent.TimeUnit.SECONDS)
    }

    @Synchronized
    fun onValue(deviceId: String, widgetId: String, rawValue: String) {
        val value = rawValue.trim().replace(',', '.').toDoubleOrNull() ?: return
        values[key(deviceId, widgetId)] = value

        store.load().forEach { scenario ->
            if (!scenario.enabled) return@forEach

            if (scenario.verifyEnabled &&
                scenario.verifyDeviceId == deviceId &&
                scenario.verifyWidgetId == widgetId &&
                verificationMatches(scenario, value)
            ) {
                val task = verificationTasks.remove(scenario.id)
                if (task != null) {
                    task.cancel(false)
                    onVerificationResult(scenario, true, rawValue)
                }
            }

            val conditions = scenario.conditions.ifEmpty {
                listOf(ScenarioCondition(scenario.deviceId, scenario.widgetId, scenario.operator, scenario.threshold))
            }
            if (conditions.none { it.deviceId == deviceId && it.widgetId == widgetId }) return@forEach

            val matched = expressionMatches(conditions) ?: return@forEach
            if (matched && scenario.armed) {
                onTrigger(scenario, rawValue, value)
                store.update(scenario.copy(armed = false))
                startVerification(scenario)
            } else if (!matched && !scenario.armed) {
                store.update(scenario.copy(armed = true))
            }
        }
    }
}
