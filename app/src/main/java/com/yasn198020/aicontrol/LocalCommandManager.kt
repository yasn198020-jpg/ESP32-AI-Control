package com.yasn198020.aicontrol

import java.math.BigDecimal
import com.yasn198020.aicontrol.core.Device

enum class LocalCommandAction { CONTROL, READ_VALUE, SMART_RULE, CLARIFY, NOT_FOUND }

data class LocalCommandActionItem(
    val deviceId: String,
    val widgetId: String,
    val value: String = "1"
)

data class LocalCommandResult(
    val action: LocalCommandAction,
    val deviceId: String = "",
    val widgetId: String = "",
    val value: String = "",
    val reply: String,
    val delayMs: Long = 0L,
    val conditionDeviceId: String = "",
    val conditionWidgetId: String = "",
    val conditionOperator: String = ">",
    val conditionThreshold: Double = 0.0,
    val actionDeviceId: String = "",
    val actionWidgetId: String = "",
    val actionValue: String = "1",
    val actionItems: List<LocalCommandActionItem> = emptyList(),
    val needsConfirmation: Boolean = false,
    /** Final Android-side scenario plan. External AI never writes this field. */
    val scenarioPlan: ScenarioCommandPlan? = null
)

/**
 * Compatibility entry point used by the UI and voice service.
 * The old public API is kept, but parsing is delegated to MarfaCommandEngine.
 */
typealias ScenarioPlanResolver = (
    result: LocalCommandResult,
    devices: List<Device>
) -> ScenarioCommandPlan

class LocalCommandManager(
    private val scenarioPlanResolver: ScenarioPlanResolver? = null
) {
    private val engine = MarfaCommandEngine(scenarioPlanResolver)

    fun interpret(command: String, devices: List<Device>): LocalCommandResult =
        engine.parse(command, devices)
}

fun formatTemperatureForSpeech(raw: String, unit: String = "°C"): String {
    val normalized = raw.trim().replace(',', '.')
    val number = normalized.toBigDecimalOrNull()
        ?: return raw + " " + unit.ifBlank { "°C" }

    val value = number.stripTrailingZeros().toPlainString().replace('.', ',')
    val degreeWord = if (number.abs().remainder(BigDecimal.ONE) == BigDecimal.ZERO) {
        val whole = number.abs().toInt()
        when {
            whole % 100 in 11..14 -> "градусов"
            whole % 10 == 1 -> "градус"
            whole % 10 in 2..4 -> "градуса"
            else -> "градусов"
        }
    } else {
        "градуса"
    }
    return value + " " + degreeWord
}
