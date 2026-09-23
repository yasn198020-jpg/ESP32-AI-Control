package com.yasn198020.aicontrol
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState

enum class LocalCommandAction { CONTROL, READ_VALUE, CLARIFY, NOT_FOUND }

data class LocalCommandResult(
    val action: LocalCommandAction,
    val deviceId: String = "",
    val widgetId: String = "",
    val value: String = "",
    val reply: String
)

fun formatTemperatureForSpeech(raw: String, unit: String = "°C"): String {
    val normalized = raw.trim().replace(',', '.')
    val number = normalized.toBigDecimalOrNull() ?: return raw + " " + unit.ifBlank { "°C" }
    val value = number.stripTrailingZeros().toPlainString().replace('.', ',')
    val degreeWord = if (number.abs() % java.math.BigDecimal("1") == java.math.BigDecimal.ZERO) {
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
class LocalCommandManager {

    fun interpret(command: String, devices: List<Device>): LocalCommandResult {
        val text = normalize(command)
        if (text.isBlank()) {
            return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "Не удалось распознать команду")
        }

        if (isTemperatureQuestion(text)) {
            val values = devices.flatMap { device ->
                device.widgets
                    .filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }
                    .map { Candidate(device, it) }
            }
            if (values.isEmpty()) return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "Не нашёл датчик температуры")

            val scored = values.map { it to temperatureScore(text, it) }.sortedByDescending { it.second }
            val best = scored.firstOrNull()
            if (best == null || best.second < 1) {
                return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "Не нашёл температуру для помидоров")
            }
            val widget = best.first.widget
            val raw = widget.value.trim()
            val unit = widget.unit.trim().ifBlank { "°C" }
            val spoken = if (raw.isBlank() || raw == "—") {
                "Температура для помидоров пока неизвестна"
            } else {
                "Температура для помидоров: ${formatTemperatureForSpeech(raw, unit)}"
            }
            return LocalCommandResult(LocalCommandAction.READ_VALUE, best.first.device.id, widget.id, raw, spoken)
        }

        val value = detectValue(text)
            ?: return LocalCommandResult(
                LocalCommandAction.NOT_FOUND,
                reply = "Скажите, например: «открой форточку у помидоров» или «выключи насос»"
            )

        val all = devices.flatMap { device ->
            device.widgets
                .filter { it.type == WidgetState.Type.TOGGLE || it.type == WidgetState.Type.BUTTON }
                .map { Candidate(device, it) }
        }

        if (all.isEmpty()) {
            return LocalCommandResult(
                LocalCommandAction.NOT_FOUND,
                reply = "Нет управляемых виджетов. Сначала подключитесь к MQTT и нажмите HELLO."
            )
        }

        val scored = all.map { it to score(text, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        if (scored.isEmpty()) {
            return LocalCommandResult(
                LocalCommandAction.NOT_FOUND,
                reply = "Не нашёл подходящий виджет для команды"
            )
        }

        val best = scored.first()
        val second = scored.getOrNull(1)

        if (second != null && best.second - second.second < 2) {
            return LocalCommandResult(
                LocalCommandAction.CLARIFY,
                reply = clarification(scored.take(4).map { it.first })
            )
        }

        val candidate = best.first
        val actionWord = if (value == "1") "Открываю" else "Закрываю"
        return LocalCommandResult(
            LocalCommandAction.CONTROL,
            candidate.device.id,
            candidate.widget.id,
            value,
            "$actionWord: ${candidate.widget.title}"
        )
    }

    private data class Candidate(val device: Device, val widget: WidgetState)

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun isTemperatureQuestion(text: String): Boolean {
        val temperature = listOf("температур", "тепло", "градус")
        val question = listOf("какая", "сколько", "покажи", "скажи", "температура")
        return temperature.any { text.contains(it) } && question.any { text.contains(it) }
    }

    private fun temperatureScore(text: String, candidate: Candidate): Int {
        val title = normalize(candidate.widget.title)
        val page = normalize(candidate.widget.page)
        val device = normalize(candidate.device.name + " " + candidate.device.id)
        var score = 1
        val tomato = listOf("помидор", "томат")
        if (tomato.any { text.contains(it) }) {
            if (title.contains("помид") || title.contains("томат")) score += 10
            if (page.contains("помид") || page.contains("томат")) score += 8
            if (device.contains("помид") || device.contains("томат")) score += 8
            if (page.contains("🍅")) score += 8
        }
        if (title.contains("температур") || title.contains("темп")) score += 6
        if (title.contains("°") || candidate.widget.unit.contains("c", true) || candidate.widget.unit.contains("°")) score += 4
        if (page.contains("теплиц")) score += 2
        return score
    }

    private fun detectValue(text: String): String? {
        val open = listOf(
            "открой", "открыть", "открывай", "подними", "поднять",
            "включи", "включить", "включай", "запусти", "запустить"
        )
        val close = listOf(
            "закрой", "закрыть", "закрывай", "опусти", "опустить",
            "выключи", "выключить", "выключай", "останови", "остановить"
        )
        return when {
            open.any { text.contains(it) } -> "1"
            close.any { text.contains(it) } -> "0"
            else -> null
        }
    }

    private fun score(text: String, candidate: Candidate): Int {
        val title = normalize(candidate.widget.title)
        val page = normalize(candidate.widget.page)
        val device = normalize(candidate.device.name + " " + candidate.device.id)
        var score = 0

        val objectGroups = listOf(
            listOf("форточка", "форточки", "форточ", "окно", "окна") to listOf("форточ", "окно"),
            listOf("дверь", "двери", "двер") to listOf("двер"),
            listOf("ворота", "ворот") to listOf("ворот"),
            listOf("заслонка", "заслон", "клапан", "клап") to listOf("заслон", "клап"),
            listOf("насос", "помпу", "помпа") to listOf("насос", "помп"),
            listOf("вентилятор", "вентилят", "вент") to listOf("вентилят"),
            listOf("автомат", "автоматика") to listOf("автомат")
        )

        for ((words, titleParts) in objectGroups) {
            if (words.any { text.contains(it) }) {
                if (titleParts.any { title.contains(it) }) score += 10
                else if (titleParts.any { page.contains(it) }) score += 2
                else score -= 2
            }
        }

        val tomato = listOf("помидор", "помидоры", "томат", "томаты")
        val cucumber = listOf("огурец", "огурцы", "огуреч")
        val greenhouse = listOf("теплица", "теплицу", "теплице", "парник", "грядк")

        if (tomato.any { text.contains(it) }) {
            if (page.contains("помид") || page.contains("томат") || candidate.device.name.lowercase().contains("помид")) score += 8
            if (page.contains("🍅")) score += 8
            if (title.contains("помид") || title.contains("томат")) score += 3
        }
        if (cucumber.any { text.contains(it) }) {
            if (page.contains("огур") || candidate.device.name.lowercase().contains("огур")) score += 8
            if (page.contains("🥒")) score += 8
            if (title.contains("огур")) score += 3
        }
        if (greenhouse.any { text.contains(it) }) {
            if (page.contains("теплиц") || page.contains("парник") || page.contains("гряд")) score += 4
        }

        val stopWords = setOf(
            "открой", "открыть", "открывай", "подними", "поднять",
            "включи", "включить", "включай", "запусти", "запустить",
            "закрой", "закрыть", "закрывай", "опусти", "опустить",
            "выключи", "выключить", "выключай", "останови", "остановить",
            "у", "в", "на", "для", "теплица", "теплицу", "теплице"
        )
        text.split(" ")
            .filter { it.length >= 3 && it !in stopWords }
            .forEach { word ->
                if (title.contains(word)) score += 5
                if (page.contains(word)) score += 4
                if (device.contains(word)) score += 4
            }

        return score
    }

    private fun clarification(candidates: List<Candidate>): String {
        val names = candidates
            .map { it.widget.title.ifBlank { it.widget.id } }
            .distinct()
            .take(3)

        return if (names.size >= 2) {
            "Уточните, что выбрать: " + names.joinToString(" или ")
        } else {
            "Уточните, какой виджет нужно управлять"
        }
    }
}
