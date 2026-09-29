package com.yasn198020.aicontrol

import java.math.BigDecimal
import com.yasn198020.aicontrol.core.Device

enum class LocalCommandAction { CONTROL, READ_VALUE, SMART_RULE, CLARIFY, NOT_FOUND }

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
    val actionValue: String = "1"
)

/**
 * Compatibility facade. The old parser API stays intact, while all parsing
 * is now performed by MarfaCommandEngine.
 */
class LocalCommandManager {
    private val engine = MarfaCommandEngine()

    fun interpret(command: String, devices: List<Device>): LocalCommandResult =
        engine.parse(command, devices)
}

fun formatTemperatureForSpeech(raw: String, unit: String = "°C"): String {
    val normalized = raw.trim().replace(',', '.')
    val number = normalized.toBigDecimalOrNull() ?: return raw + " " + unit.ifBlank { "°C" }
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
