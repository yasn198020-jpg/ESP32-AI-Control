package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState

class MarfaCommandEngine {

    fun parse(command: String, devices: List<Device>): LocalCommandResult {
        val text = normalize(command)
        if (text.isBlank()) return result(LocalCommandAction.NOT_FOUND, "Я не услышала команду")

        parseRule(text, devices)?.let { return it }
        parseValueQuestion(text, devices)?.let { return it }

        val action = detectAction(text)
        val candidates = controllable(devices)
        if (action != null && candidates.isNotEmpty()) {
            val ranked = candidates.map { it to entityScore(text, it) }.filter { it.second > 0 }.sortedByDescending { it.second }
            if (ranked.isNotEmpty()) {
                val best = ranked.first()
                val second = ranked.getOrNull(1)
                if (second != null && best.second == second.second) {
                    return LocalCommandResult(LocalCommandAction.CLARIFY, reply = "Уточните, что именно " + action.infinitive + ": " + best.first.widget.title + " или " + second.first.widget.title)
                }
                val delay = parseDelay(text)
                val reply = if (delay > 0L) action.reply + " через " + formatDelay(delay) + ": " + best.first.widget.title else action.reply + ": " + best.first.widget.title
                return LocalCommandResult(LocalCommandAction.CONTROL, best.first.device.id, best.first.widget.id, action.value, reply, delay)
            }
        }
        return result(LocalCommandAction.NOT_FOUND, "Не поняла команду. Например: открой форточку; выключи насос через 20 минут; если температура выше 28 открой форточку")
    }

    private fun parseRule(text: String, devices: List<Device>): LocalCommandResult? {
        val intro = listOf("если", "когда", "при температуре", "как только")
        if (!intro.any { text.contains(it) }) return null
        if (!listOf("температур", "темп", "градус", "жарко", "холодно").any { text.contains(it) }) return null

        val match = Regex("""(?:выше|больше|превысит|достигнет|станет выше|поднимется выше)\s*(-?\d+(?:[.,]\d+)?)""").find(text)
            ?: Regex("""(?:ниже|меньше|опустится ниже|станет ниже)\s*(-?\d+(?:[.,]\d+)?)""").find(text)
            ?: return null
        val threshold = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val operator = if (listOf("ниже", "меньше", "опустится").any { text.contains(it) }) "<" else ">"

        val sensors = devices.flatMap { d -> d.widgets.filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }.map { Candidate(d, it) } }
        val condition = sensors.map { it to sensorScore(text, it) }.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        val tail = text.substring(match.range.last + 1).replaceFirst(Regex("""^\s*(?:градусов?|градуса)?\s*(?:тогда|то|и)?\s*"""), "").trim(' ', ',', '.', ':', ';', '-')
        val action = detectAction(tail) ?: return null
        val target = controllable(devices).map { it to entityScore(tail, it) }.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        val sensor = condition.first.widget
        val widget = target.first.widget
        val title = "Если " + sensor.title.ifBlank { sensor.id } + " " + operator + " " + threshold + " → " + action.infinitive + " " + widget.title.ifBlank { widget.id }
        return LocalCommandResult(LocalCommandAction.SMART_RULE, reply = "Поняла: " + title, conditionDeviceId = condition.first.device.id, conditionWidgetId = sensor.id, conditionOperator = operator, conditionThreshold = threshold, actionDeviceId = target.first.device.id, actionWidgetId = widget.id, actionValue = action.value)
    }

    private fun parseValueQuestion(text: String, devices: List<Device>): LocalCommandResult? {
        if (!listOf("сколько", "какая", "какое", "покажи", "скажи", "узнай").any { text.contains(it) }) return null
        if (!listOf("температур", "темп", "градус", "влажност", "влажн", "давлен").any { text.contains(it) }) return null
        val sensors = devices.flatMap { d -> d.widgets.filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }.map { Candidate(d, it) } }
        val best = sensors.map { it to sensorScore(text, it) }.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        val w = best.first.widget
        val raw = w.value.trim()
        val spoken = if (raw.isBlank() || raw == "—") w.title + ": значение пока неизвестно" else w.title + ": " + formatTemperatureForSpeech(raw, w.unit)
        return LocalCommandResult(LocalCommandAction.READ_VALUE, best.first.device.id, w.id, raw, spoken)
    }

    private data class Candidate(val device: Device, val widget: WidgetState)
    private data class Action(val value: String, val reply: String, val infinitive: String)

    private fun detectAction(text: String): Action? = when {
        Regex("""\b(?:открой|открыть|открывай|подними|поднять|включи|включить|включай|запусти|запустить)\b""").containsMatchIn(text) -> Action("1", "Открываю", "открыть")
        Regex("""\b(?:закрой|закрыть|закрывай|опусти|опустить|выключи|выключить|выключай|останови|остановить)\b""").containsMatchIn(text) -> Action("0", "Закрываю", "закрыть")
        else -> null
    }

    private fun controllable(devices: List<Device>) = devices.flatMap { d -> d.widgets.filter { it.type == WidgetState.Type.TOGGLE || it.type == WidgetState.Type.BUTTON }.map { Candidate(d, it) } }

    private fun sensorScore(text: String, c: Candidate): Int {
        val title = normalize(c.widget.title); val page = normalize(c.widget.page); val device = normalize(c.device.name + " " + c.device.id); var score = 1
        if (listOf("температур", "темп", "градус").any { text.contains(it) }) { if (title.contains("температур") || title.contains("темп")) score += 12; if (c.widget.unit.contains("c", true) || c.widget.unit.contains("°")) score += 6 }
        if (listOf("влажност", "влажн").any { text.contains(it) } && (title.contains("влажн") || c.widget.unit.contains("%"))) score += 10
        val groups = listOf(listOf("помидор", "томат") to listOf("помид", "томат"), listOf("огурец", "огурцы", "огуреч") to listOf("огур"), listOf("теплица", "теплицу", "теплице", "парник") to listOf("теплиц", "парник"))
        for ((words, parts) in groups) if (words.any { text.contains(it) }) { if (parts.any { title.contains(it) }) score += 10; if (parts.any { page.contains(it) }) score += 8; if (parts.any { device.contains(it) }) score += 6 }
        return score
    }

    private fun entityScore(text: String, c: Candidate): Int {
        val title = normalize(c.widget.title); val page = normalize(c.widget.page); val device = normalize(c.device.name + " " + c.device.id); var score = 0
        val groups = listOf(listOf("форточка", "форточки", "форточ", "окно", "окна") to listOf("форточ", "окно"), listOf("дверь", "двери", "двер") to listOf("двер"), listOf("ворота", "ворот") to listOf("ворот"), listOf("насос", "помпа", "помпу") to listOf("насос", "помп"), listOf("вентилятор", "вентилят", "вент") to listOf("вентилят"), listOf("обогрев", "обогреватель", "отопление", "тепло") to listOf("обогрев", "отоп", "нагрев"))
        for ((words, parts) in groups) if (words.any { text.contains(it) }) { if (parts.any { title.contains(it) }) score += 20; if (parts.any { page.contains(it) }) score += 8; if (parts.any { device.contains(it) }) score += 6 }
        val contexts = listOf("помидор" to "помид", "томат" to "томат", "огурец" to "огур", "теплица" to "теплиц", "парник" to "парник")
        for ((word, part) in contexts) if (text.contains(word)) { if (page.contains(part)) score += 10; if (device.contains(part)) score += 7; if (title.contains(part)) score += 4 }
        text.split(" ").filter { it.length >= 4 && it !in STOP_WORDS }.forEach { word -> val s = stem(word); if (s.length >= 4 && title.contains(s)) score += 4; if (s.length >= 4 && page.contains(s)) score += 3; if (s.length >= 4 && device.contains(s)) score += 3 }
        return score
    }

    private fun parseDelay(text: String): Long {
        val part = Regex("""(?:через|спустя)\s+(.+)""").find(text)?.groupValues?.get(1) ?: return 0L
        var minutes = 0L
        Regex("""(\d+)\s*(?:час(?:а|ов)?|ч)""").find(part)?.let { minutes += (it.groupValues[1].toLongOrNull() ?: 0L) * 60L }
        Regex("""(\d+)\s*(?:минут(?:а|ы)?|мин)""").find(part)?.let { minutes += it.groupValues[1].toLongOrNull() ?: 0L }
        val words = mapOf("один" to 1L, "одна" to 1L, "два" to 2L, "две" to 2L, "три" to 3L, "четыре" to 4L, "пять" to 5L, "десять" to 10L, "пятнадцать" to 15L, "двадцать" to 20L, "тридцать" to 30L)
        Regex("""\b([а-я]+)\s+(?:минут(?:а|ы)?|мин)\b""").find(part)?.let { minutes += words[it.groupValues[1]] ?: 0L }
        Regex("""\b([а-я]+)\s+(?:час(?:а|ов)?|ч)\b""").find(part)?.let { minutes += (words[it.groupValues[1]] ?: 0L) * 60L }
        return minutes.coerceIn(0L, 7L * 24L * 60L) * 60_000L
    }

    private fun formatDelay(ms: Long): String { val minutes = ms / 60_000L; val hours = minutes / 60L; val rest = minutes % 60L; return when { hours > 0 && rest > 0 -> hours.toString() + " ч " + rest + " мин"; hours > 0 -> hours.toString() + " ч"; else -> minutes.toString() + " мин" } }

    private fun stem(word: String): String {
        val endings = listOf("иями", "ами", "ого", "ему", "ому", "ыми", "ими", "ать", "ить", "еть", "ой", "ый", "ий", "ая", "яя", "ое", "ее", "ые", "ие", "ы", "и", "а", "я", "о", "е")
        for (ending in endings) if (word.length > ending.length + 2 && word.endsWith(ending)) return word.removeSuffix(ending)
        return word
    }

    private fun normalize(value: String) = value.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9]+"), " ").trim().replace(Regex("""\s+"""), " ").replace(Regex("""^марф(а|у|е|ой)\s*"""), "").trim()
    private fun result(action: LocalCommandAction, reply: String) = LocalCommandResult(action = action, reply = reply)
    private val STOP_WORDS = setOf("открой", "открыть", "закрой", "закрыть", "включи", "включить", "выключи", "выключить", "если", "когда", "при", "температура", "температур", "через", "минут", "минуту", "час", "часа")
}