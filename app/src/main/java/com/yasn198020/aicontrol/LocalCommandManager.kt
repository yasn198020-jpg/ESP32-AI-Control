package com.yasn198020.aicontrol
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState

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

        detectSmartRule(text, devices)?.let { return it }

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
        val delayMs = detectDelayMs(text)
        val reply = if (delayMs > 0L) {
            "$actionWord через ${formatDelay(delayMs)}: ${candidate.widget.title}"
        } else {
            "$actionWord: ${candidate.widget.title}"
        }
        return LocalCommandResult(
            LocalCommandAction.CONTROL,
            candidate.device.id,
            candidate.widget.id,
            value,
            reply,
            delayMs
        )
    }

    private fun detectDelayMs(text: String): Long {
        val match = Regex("""(?:через|спустя)\s+(.+?)(?=$|\s+(?:у|в|на|для)\s+)""").find(text) ?: return 0L
        val duration = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (duration.isBlank()) return 0L

        val words = mapOf(
            "один" to 1L, "одна" to 1L, "два" to 2L, "две" to 2L,
            "три" to 3L, "четыре" to 4L, "пять" to 5L, "шесть" to 6L,
            "семь" to 7L, "восемь" to 8L, "девять" to 9L, "десять" to 10L,
            "одиннадцать" to 11L, "двенадцать" to 12L, "тринадцать" to 13L,
            "четырнадцать" to 14L, "пятнадцать" to 15L, "двадцать" to 20L
        )

        var totalMinutes = 0L
        Regex("""(\d+)\s*(?:час(?:а|ов)?|ч)""").find(duration)?.let {
            totalMinutes += (it.groupValues[1].toLongOrNull() ?: 0L) * 60L
        }
        Regex("""(\d+)\s*(?:минут(?:а|ы)?|мин)""").find(duration)?.let {
            totalMinutes += it.groupValues[1].toLongOrNull() ?: 0L
        }
        Regex("""\b([а-я]+)\s+(?:час(?:а|ов)?|ч)\b""").find(duration)?.let {
            totalMinutes += (words[it.groupValues[1]] ?: 0L) * 60L
        }
        Regex("""\b([а-я]+)\s+(?:минут(?:а|ы)?|мин)\b""").find(duration)?.let {
            totalMinutes += words[it.groupValues[1]] ?: 0L
        }

        return totalMinutes.coerceAtMost(7L * 24L * 60L) * 60_000L
    }

    private fun formatDelay(delayMs: Long): String {
        val minutes = delayMs / 60_000L
        val hours = minutes / 60L
        val rest = minutes % 60L
        return when {
            hours > 0L && rest > 0L -> "$hours ч $rest мин"
            hours > 0L -> "$hours ч"
            else -> "$minutes мин"
        }
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

    private fun detectSmartRule(text: String, devices: List<Device>): LocalCommandResult? {
        if (!text.contains("если") || !text.contains("температур")) return null
        val thresholdMatch = Regex("""(?:выше|больше|поднимется\s+выше|станет\s+выше|ниже|меньше)\s+(-?\d+(?:[.,]\d+)?)""").find(text) ?: return null
        val threshold = thresholdMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val op = if (text.contains("ниже") || text.contains("меньше")) "<" else ">"
        val values = devices.flatMap { device -> device.widgets.filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }.map { Candidate(device, it) } }
        val condition = values.map { it to temperatureScore(text, it) }.maxByOrNull { it.second } ?: return null
        if (condition.second <= 1) return null
        // The action may be introduced by "тогда/то", but natural speech often
        // simply continues after the threshold: "если температура выше 28, открой форточку".
        val explicitAction = when {
            text.contains("тогда") -> text.substringAfter("тогда")
            Regex("""\bто\b""").find(text) != null -> Regex("""\bто\b""").find(text)?.let { text.substring(it.range.last + 1) } ?: ""
            else -> text.substring(thresholdMatch.range.last + 1)
        }
        val actionText = explicitAction.trim().trim(',', '.', ':', ';')
        val actionValue = detectValue(actionText) ?: return null
        val actions = devices.flatMap { device -> device.widgets.filter { it.type == WidgetState.Type.TOGGLE || it.type == WidgetState.Type.BUTTON }.map { Candidate(device, it) } }
        val action = actions.map { it to score(actionText, it) }.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        val cw = condition.first.widget
        val aw = action.first.widget
        val title = "Если " + cw.title.ifBlank { cw.id } + " " + op + " " + threshold + " → " + (if (actionValue == "1") "включить " else "выключить ") + aw.title.ifBlank { aw.id }
        return LocalCommandResult(LocalCommandAction.SMART_RULE, reply = "Поняла правило: $title", conditionDeviceId = condition.first.device.id, conditionWidgetId = cw.id, conditionOperator = op, conditionThreshold = threshold, actionDeviceId = action.first.device.id, actionWidgetId = aw.id, actionValue = actionValue)
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
