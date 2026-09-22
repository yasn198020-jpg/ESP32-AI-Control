package com.yasn198020.aicontrol

enum class LocalCommandAction { CONTROL, CLARIFY, NOT_FOUND }

data class LocalCommandResult(
    val action: LocalCommandAction,
    val deviceId: String = "",
    val widgetId: String = "",
    val value: String = "",
    val reply: String
)

class LocalCommandManager {

    fun interpret(command: String, devices: List<Device>): LocalCommandResult {
        val text = normalize(command)
        if (text.isBlank()) {
            return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "Не удалось распознать команду")
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
            "\${actionWord}: \${candidate.widget.title}"
        )
    }

    private data class Candidate(val device: Device, val widget: WidgetState)

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

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
