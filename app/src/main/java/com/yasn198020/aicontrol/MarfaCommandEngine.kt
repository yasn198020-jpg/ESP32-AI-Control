package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The natural-language brain of Marfa.
 *
 * Responsibilities:
 * - understand Russian commands and follow-up phrases;
 * - resolve a spoken entity to a real MQTT widget;
 * - understand relative/absolute time;
 * - build smart-rule requests;
 * - remember the last spoken target for short conversational follow-ups.
 *
 * MQTT is deliberately outside this class.
 */
class MarfaCommandEngine {
    private data class Candidate(val device: Device, val widget: WidgetState, val score: Int)
    private data class ActionSpec(val value: String, val reply: String, val infinitive: String)
    private data class TimeSpec(val delayMs: Long)

    private var lastTarget: LocalCommandActionItem? = null
    private var lastTargetTitle = ""
    private var lastIntent: LocalCommandAction? = null
    private var lastActionValue = ""

    fun parse(command: String, devices: List<Device>): LocalCommandResult {
        val text = normalize(command)
        if (text.isBlank()) return result(LocalCommandAction.NOT_FOUND, "Я не услышала команду")

        parseFollowUp(text, devices)?.let { return remember(it) }
        parseSmartRule(text, devices)?.let { return remember(it) }
        parseValueQuestion(text, devices)?.let { return remember(it) }

        val action = detectAction(text)
        if (action != null) {
            val time = parseTime(text)
            val candidates = rankControllable(text, devices, action.value)
            if (candidates.isEmpty()) {
                return remember(LocalCommandResult(
                    LocalCommandAction.CLARIFY,
                    reply = "Я не нашла управляемый элемент. Назовите устройство или элемент."
                ))
            }

            val chosen = chooseCandidate(candidates)
            if (chosen == null) {
                val names = candidates.take(3)
                    .map { it.widget.title.ifBlank { it.widget.id } }
                    .distinct()
                return remember(LocalCommandResult(
                    LocalCommandAction.CLARIFY,
                    reply = "Уточните, что именно ${action.infinitive}: " + names.joinToString(" или ")
                ))
            }

            val first = LocalCommandActionItem(chosen.device.id, chosen.widget.id, action.value)
            val extra = parseAdditionalActions(text, action, chosen, devices)
            val actions = (listOf(first) + extra)
                .distinctBy { it.deviceId + "/" + it.widgetId + "/" + it.value }
            val delay = time?.delayMs ?: 0L

            return remember(LocalCommandResult(
                action = LocalCommandAction.CONTROL,
                deviceId = first.deviceId,
                widgetId = first.widgetId,
                value = first.value,
                reply = controlReply(action, chosen.widget.title, delay, actions.size),
                delayMs = delay,
                actionItems = actions,
                needsConfirmation = true
            ))
        }

        return remember(result(
            LocalCommandAction.NOT_FOUND,
            "Не поняла. Скажите, например: «открой форточку», «выключи насос через 20 минут», «какая температура?», «закрой её»."
        ))
    }

    private fun parseFollowUp(text: String, devices: List<Device>): LocalCommandResult? {
        val time = parseTime(text)
        val action = detectAction(text)
        val question = containsAny(text, "сколько", "какая", "какое", "покажи", "скажи", "узнай", "что там")

        // "Через 20 минут" after "открой форточку" repeats that action later.
        if (action == null && !question && time != null && lastTarget != null && lastActionValue.isNotBlank()) {
            val t = lastTarget!!
            return LocalCommandResult(
                LocalCommandAction.CONTROL,
                t.deviceId,
                t.widgetId,
                lastActionValue,
                "Запланировала повтор: ${lastTargetTitle.ifBlank { t.widgetId }} через ${formatDelay(time.delayMs)}",
                time.delayMs,
                actionItems = listOf(t),
                needsConfirmation = true
            )
        }

        // "Закрой её", "включи вторую", "открой там".
        if (action != null && hasReference(text)) {
            val target = resolveReference(text, devices) ?: return null
            val item = LocalCommandActionItem(target.device.id, target.widget.id, action.value)
            val delay = time?.delayMs ?: 0L
            return LocalCommandResult(
                LocalCommandAction.CONTROL,
                target.device.id,
                target.widget.id,
                action.value,
                controlReply(action, target.widget.title, delay, 1),
                delay,
                actionItems = listOf(item),
                needsConfirmation = true
            )
        }

        // "А какая там температура?" reuses the last sensor target.
        if (question && containsAny(text, "там", "здесь") && lastIntent == LocalCommandAction.READ_VALUE) {
            val t = lastTarget ?: return null
            val widget = devices.firstOrNull { it.id == t.deviceId }
                ?.widgets?.firstOrNull { it.id == t.widgetId } ?: return null
            return LocalCommandResult(
                LocalCommandAction.READ_VALUE,
                t.deviceId,
                t.widgetId,
                widget.value.trim(),
                valueSpeech(widget)
            )
        }

        return null
    }

    private fun parseSmartRule(text: String, devices: List<Device>): LocalCommandResult? {
        if (!containsAny(text, "если", "когда", "при температуре", "как только", "при условии")) return null
        if (!containsAny(text, "температур", "темп", "градус", "жарко", "холодно")) return null

        val upper = Regex("""(?:стала?\s+|поднялась?\s+|станет?\s+)?(?:выше|больше|превысит|достигнет)\s*(-?\d+(?:[.,]\d+)?)""")
            .find(text)
        val lower = Regex("""(?:стала?\s+|опустилась?\s+|станет?\s+)?(?:ниже|меньше|опустится)\s*(-?\d+(?:[.,]\d+)?)""")
            .find(text)
        val match = upper ?: lower ?: return null
        val threshold = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val operator = if (lower != null && lower.range == match.range) "<" else ">"

        val sensor = chooseSensor(rankSensors(text, devices)) ?: return null
        val tail = text.substring((match.range.last + 1).coerceAtMost(text.length))
            .replaceFirst(Regex("""^\s*(?:градусов?|градуса?|град)?\s*(?:тогда|то|и)?\s*"""), "")
            .trim(' ', ',', '.', ':', ';', '-')

        val action = detectAction(tail) ?: detectAction(text) ?: return null
        val target = chooseCandidate(rankControllable(tail.ifBlank { text }, devices, action.value)) ?: return null

        val sensorTitle = sensor.widget.title.ifBlank { sensor.widget.id }
        val targetTitle = target.widget.title.ifBlank { target.widget.id }
        val title = "Если ${sensorTitle} ${operator} ${threshold} → ${action.infinitive} ${targetTitle}"

        return LocalCommandResult(
            action = LocalCommandAction.SMART_RULE,
            reply = "Поняла: ${title}",
            conditionDeviceId = sensor.device.id,
            conditionWidgetId = sensor.widget.id,
            conditionOperator = operator,
            conditionThreshold = threshold,
            actionDeviceId = target.device.id,
            actionWidgetId = target.widget.id,
            actionValue = action.value,
            actionItems = listOf(LocalCommandActionItem(target.device.id, target.widget.id, action.value))
        )
    }

    private fun parseValueQuestion(text: String, devices: List<Device>): LocalCommandResult? {
        if (!containsAny(text, "сколько", "какая", "какое", "покажи", "скажи", "узнай", "что там")) return null
        val sensor = chooseSensor(rankSensors(text, devices)) ?: return null
        return LocalCommandResult(
            LocalCommandAction.READ_VALUE,
            sensor.device.id,
            sensor.widget.id,
            sensor.widget.value.trim(),
            valueSpeech(sensor.widget)
        )
    }

    private fun rankSensors(text: String, devices: List<Device>): List<Candidate> =
        devices.flatMap { d ->
            d.widgets.filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }
                .map { w -> Candidate(d, w, sensorScore(text, d, w)) }
        }.filter { it.score > 0 }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.widget.order }.thenBy { it.widget.title })

    private fun rankControllable(
        text: String,
        devices: List<Device>,
        desiredValue: String? = null
    ): List<Candidate> =
        devices.flatMap { d ->
            d.widgets.filter {
                it.type == WidgetState.Type.TOGGLE ||
                    it.type == WidgetState.Type.BUTTON ||
                    it.type == WidgetState.Type.INPUT
            }.map { w ->
                Candidate(d, w, entityScore(text, d, w) + actionTargetScore(w, desiredValue))
            }
        }.filter { it.score > 0 }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.widget.order }.thenBy { it.widget.title })

    private fun chooseCandidate(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val best = candidates.first()
        val second = candidates.getOrNull(1) ?: return best
        // Generic equal matches are ambiguous; strong exact-title matches are safe.
        return if (best.score == second.score && best.score < 24) null else best
    }

    private fun chooseSensor(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val best = candidates.first()
        val second = candidates.getOrNull(1)
        return if (second != null && best.score == second.score && best.score < 20) null else best
    }

    private fun resolveReference(text: String, devices: List<Device>): Candidate? {
        val all = devices.flatMap { d ->
            d.widgets.filter {
                it.type == WidgetState.Type.TOGGLE ||
                    it.type == WidgetState.Type.BUTTON ||
                    it.type == WidgetState.Type.INPUT
            }.map { w -> Candidate(d, w, 1) }
        }.sortedWith(compareBy<Candidate> { it.widget.order }.thenBy { it.widget.title })

        when {
            containsAny(text, "первую", "первая", "первый", "первое", "номер один") -> return all.getOrNull(0)
            containsAny(text, "вторую", "вторая", "второй", "второе", "номер два") -> return all.getOrNull(1)
            containsAny(text, "третью", "третья", "третий", "третье", "номер три") -> return all.getOrNull(2)
        }

        val last = lastTarget ?: return null
        val device = devices.firstOrNull { it.id == last.deviceId } ?: return null
        val widget = device.widgets.firstOrNull { it.id == last.widgetId } ?: return null
        return Candidate(device, widget, 100)
    }

    private fun hasReference(text: String): Boolean =
        containsAny(
            text, "ее", "его", "их", "эту", "этот", "это", "там", "здесь",
            "первую", "вторую", "третью", "первый", "второй", "третий",
            "номер один", "номер два", "номер три"
        )

    private fun parseAdditionalActions(
        text: String,
        action: ActionSpec,
        chosen: Candidate,
        devices: List<Device>
    ): List<LocalCommandActionItem> {
        val parts = Regex("""\s+и\s+""").split(text, limit = 2)
        if (parts.size != 2) return emptyList()
        val second = parts[1].trim()
        if (!containsAny(second, "форточ", "окн", "двер", "ворот", "насос", "помп",
                "вент", "обогрев", "отоп", "нагрев", "клапан", "кран", "свет", "ламп", "полив")) return emptyList()

        val target = chooseCandidate(rankControllable(second, devices)) ?: return emptyList()
        if (target.device.id == chosen.device.id && target.widget.id == chosen.widget.id) return emptyList()
        return listOf(LocalCommandActionItem(target.device.id, target.widget.id, action.value))
    }

    private fun detectAction(text: String): ActionSpec? {
        val lower = normalize(text)
        return when {
            Regex("""\b(?:открой|открыть|открывай|подними|поднять|распахни|раскрой)\b""").containsMatchIn(lower) ->
                ActionSpec("1", "Открываю", "открыть")
            Regex("""\b(?:закрой|закрыть|закрывай|опусти|опустить|запечатай)\b""").containsMatchIn(lower) ->
                ActionSpec("0", "Закрываю", "закрыть")
            Regex("""\b(?:включи|включить|включай|запусти|запустить|зажги)\b""").containsMatchIn(lower) ->
                ActionSpec("1", "Включаю", "включить")
            Regex("""\b(?:выключи|выключить|выключай|останови|остановить|погаси)\b""").containsMatchIn(lower) ->
                ActionSpec("0", "Выключаю", "выключить")
            else -> Regex("""(?:установи|установить|поставь|поставить|задай|задать|назначь)\s+(-?\d+(?:[.,]\d+)?)""")
                .find(lower)?.groupValues?.getOrNull(1)
                ?.let { ActionSpec(it.replace(',', '.'), "Устанавливаю ${it}", "установить значение") }
        }
    }

    private fun sensorScore(text: String, device: Device, widget: WidgetState): Int {
        val title = searchable(widget.title)
        val page = searchable(widget.page)
        val deviceText = searchable(device.name + " " + device.id)
        var score = 0

        if (containsAny(text, "температур", "темп", "градус", "жарко", "холодно")) {
            if (title.contains("температур") || title.contains("темп")) score += 18
            if (title == "t" || title.startsWith("t ")) score += 4
            if (widget.unit.contains("°") || widget.unit.equals("c", true)) score += 8
        }
        if (containsAny(text, "влажност", "влажн") &&
            (title.contains("влажн") || widget.unit.contains("%"))) score += 18
        if (containsAny(text, "давлен") &&
            (title.contains("давлен") || widget.unit.contains("па", true))) score += 18

        score += contextScore(text, title, page, deviceText)
        text.split(" ").filter { it.length >= 4 && it !in STOP_WORDS }.forEach {
            val s = stem(it)
            if (s.length >= 4 && title.contains(s)) score += 4
            if (s.length >= 4 && page.contains(s)) score += 2
            if (s.length >= 4 && deviceText.contains(s)) score += 2
        }
        return score
    }

    private fun entityScore(text: String, device: Device, widget: WidgetState): Int {
        val title = searchable(widget.title)
        val page = searchable(widget.page)
        val deviceText = searchable(device.name + " " + device.id)
        var score = 0

        val aliases = listOf(
            listOf("форточ", "фрамуг", "окн") to listOf("форточ", "фрамуг", "окн"),
            listOf("двер", "вход") to listOf("двер", "вход"),
            listOf("ворот") to listOf("ворот"),
            listOf("насос", "помп") to listOf("насос", "помп"),
            listOf("вентилят", "вент") to listOf("вентилят", "вент"),
            listOf("обогрев", "отоп", "нагрев") to listOf("обогрев", "отоп", "нагрев"),
            listOf("клапан", "кран") to listOf("клапан", "кран"),
            listOf("свет", "ламп") to listOf("свет", "ламп"),
            listOf("полив", "орош") to listOf("полив", "орош")
        )

        aliases.forEach { (words, parts) ->
            if (words.any { text.contains(it) }) {
                if (parts.any { title.contains(it) }) score += 28
                if (parts.any { page.contains(it) }) score += 10
                if (parts.any { deviceText.contains(it) }) score += 8
            }
        }

        score += contextScore(text, title, page, deviceText)
        text.split(" ").filter { it.length >= 4 && it !in STOP_WORDS }.forEach {
            val s = stem(it)
            if (s.length >= 4 && title.contains(s)) score += 5
            if (s.length >= 4 && page.contains(s)) score += 3
            if (s.length >= 4 && deviceText.contains(s)) score += 3
        }

        if (text.contains(searchable(widget.id))) score += 35
        return score
    }

    private fun actionTargetScore(widget: WidgetState, desiredValue: String?): Int {
        if (desiredValue == null) return 0

        val title = searchable(widget.title)
        val hasOpen = containsAny(title, "открыть", "открой", "открыва", "распах", "поднять", "подъем")
        val hasClose = containsAny(title, "закрыть", "закрой", "закрыва", "опустить", "опуск")
        val isStateIndicator =
            (title.contains("открыт") && title.contains("закрыт")) ||
                containsAny(title, "состояние", "статус", "индикатор", "положение")

        /*
         * A natural-language command names the logical object, not necessarily
         * the physical relay. In IoTManager the relay is often only the action
         * produced by a scenario:
         *
         *     "открой дверь" -> vbtn78=1 -> scenario -> btn43=1
         *
         * Therefore a widget describing the object's state ("открыта/закрыта",
         * "состояние", "положение") must outrank an actuator with an "open/close"
         * title. Direct commands such as "включи реле" still match the actuator
         * because the state indicator normally does not contain "реле".
         */
        return when (desiredValue) {
            "1" -> (if (hasOpen) 40 else 0) + (if (hasClose && !hasOpen) -18 else 0) +
                (if (isStateIndicator) 60 else 0)
            "0" -> (if (hasClose) 40 else 0) + (if (hasOpen && !hasClose) -18 else 0) +
                (if (isStateIndicator) 60 else 0)
            else -> if (isStateIndicator) 10 else 0
        }
    }

    private fun contextScore(text: String, title: String, page: String, deviceText: String): Int {
        var score = 0
        val contexts = listOf(
            listOf("теплиц", "парник") to 12,
            listOf("помидор", "томат") to 11,
            listOf("огурец", "огурц") to 11,
            listOf("сад", "огород") to 8,
            listOf("гараж") to 8,
            listOf("дом") to 6
        )
        contexts.forEach { (words, points) ->
            if (words.any { text.contains(it) }) {
                if (words.any { title.contains(it) }) score += points
                if (words.any { page.contains(it) }) score += points - 2
                if (words.any { deviceText.contains(it) }) score += points - 4
            }
        }
        return score
    }

    private fun parseTime(text: String): TimeSpec? {
        val relative = Regex("""(?:через|спустя)\s+(.+)""").find(text)?.groupValues?.get(1)
        if (relative != null) parseDuration(relative)?.let { return TimeSpec(it) }

        val numeric = Regex("""\b(?:в|к)\s+(\d{1,2})(?::(\d{2}))?(?:\s*(?:час(?:а|ов)?|ч))?\b""").find(text)
        if (numeric != null) {
            val hour = numeric.groupValues[1].toIntOrNull() ?: return null
            val minute = numeric.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            if (hour in 0..23 && minute in 0..59) return TimeSpec(delayUntil(hour, minute))
        }

        val wordTime = Regex("""\b(?:в|к)\s+(один|два|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать|двадцать один|двадцать два|двадцать три)\s*(?:час(?:а|ов)?|ч)?\b""").find(text)
        if (wordTime != null) {
            russianNumber(wordTime.groupValues[1])?.let { if (it in 0..23) return TimeSpec(delayUntil(it.toInt(), 0)) }
        }
        return null
    }

    private fun parseDuration(text: String): Long? {
        if (containsAny(text, "полчаса", "полчасика")) return 30L * 60_000L
        if (containsAny(text, "полтора часа", "полтора час")) return 90L * 60_000L

        var minutes = 0L
        var found = false
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:час(?:а|ов)?|ч)""").findAll(text).forEach {
            it.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { value ->
                minutes += (value * 60.0).toLong()
                found = true
            }
        }
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:минут(?:а|ы)?|мин)""").findAll(text).forEach {
            it.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { value ->
                minutes += value.toLong()
                found = true
            }
        }

        Regex("""\b(один|одна|одно|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|пятнадцать|двадцать|тридцать|сорок|пятьдесят|шестьдесят)\s+(час(?:а|ов)?|ч|минут(?:а|ы)?|мин)\b""")
            .findAll(text).forEach {
                val number = russianNumber(it.groupValues[1]) ?: return@forEach
                if (it.groupValues[2].startsWith("ч")) minutes += number * 60L else minutes += number
                found = true
            }

        Regex("""(\d+)\s+с\s+половиной\s+час""").find(text)?.let {
            minutes += (it.groupValues[1].toLongOrNull() ?: 0L) * 60L + 30L
            found = true
        }

        if (!found || minutes <= 0L) return null
        return (minutes * 60_000L).coerceAtMost(MAX_DELAY_MS)
    }

    private fun delayUntil(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var target = now.with(LocalTime.of(hour, minute))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtMost(MAX_DELAY_MS)
    }

    private fun controlReply(action: ActionSpec, title: String, delay: Long, count: Int): String {
        val cleanTitle = title
            .replace(Regex("""(?i)\b(?:закрыта|закрыто|закрыт|открыта|открыто|открыт)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { title }
        val base = if (count > 1) "${action.reply}: выполняю $count элемента"
        else "${action.reply}: ${cleanTitle}"
        return if (delay > 0L) "${base} через ${formatDelay(delay)}" else base
    }
    private fun valueSpeech(widget: WidgetState): String {
        val title = widget.title.ifBlank { widget.id }
        val raw = widget.value.trim()
        if (raw.isBlank() || raw == "—") return "${title}: значение пока неизвестно"

        val unit = widget.unit.trim()
        if (unit.contains("%")) return "${title}: ${raw} процентов"
        if (unit.contains("°") || unit.equals("C", true)) return "${title}: ${formatTemperatureForSpeech(raw)}"
        return if (unit.isBlank()) "${title}: ${raw}" else "${title}: ${raw} ${unit}"
    }

    private fun formatTemperatureForSpeech(raw: String): String {
        val number = raw.trim().replace(',', '.').toBigDecimalOrNull() ?: return raw
        val clean = number.stripTrailingZeros().toPlainString().replace('.', ',')
        if (number.remainder(BigDecimal.ONE) != BigDecimal.ZERO) return "$clean градуса"
        val n = number.abs().toInt()
        val word = when {
            n % 100 in 11..14 -> "градусов"
            n % 10 == 1 -> "градус"
            n % 10 in 2..4 -> "градуса"
            else -> "градусов"
        }
        return "$clean $word"
    }

    private fun russianNumber(word: String): Long? = mapOf(
        "один" to 1L, "одна" to 1L, "одно" to 1L, "два" to 2L, "две" to 2L,
        "три" to 3L, "четыре" to 4L, "пять" to 5L, "шесть" to 6L, "семь" to 7L,
        "восемь" to 8L, "девять" to 9L, "десять" to 10L, "одиннадцать" to 11L,
        "двенадцать" to 12L, "тринадцать" to 13L, "четырнадцать" to 14L,
        "пятнадцать" to 15L, "шестнадцать" to 16L, "семнадцать" to 17L,
        "восемнадцать" to 18L, "девятнадцать" to 19L, "двадцать" to 20L,
        "двадцать один" to 21L, "двадцать два" to 22L, "двадцать три" to 23L,
        "тридцать" to 30L, "сорок" to 40L, "пятьдесят" to 50L, "шестьдесят" to 60L
    )[word]

    private fun remember(result: LocalCommandResult): LocalCommandResult {
        lastIntent = result.action
        when (result.action) {
            LocalCommandAction.CONTROL -> result.actionItems.firstOrNull()?.let {
                lastTarget = it
                lastTargetTitle = result.reply.substringAfter(": ").substringBefore(" через ").trim()
                lastActionValue = it.value
            }
            LocalCommandAction.READ_VALUE -> {
                lastTarget = LocalCommandActionItem(result.deviceId, result.widgetId, result.value)
                lastTargetTitle = result.reply.substringBefore(":").trim()
                lastActionValue = ""
            }
            LocalCommandAction.SMART_RULE -> {
                lastTarget = LocalCommandActionItem(result.actionDeviceId, result.actionWidgetId, result.actionValue)
                lastTargetTitle = result.reply
                lastActionValue = result.actionValue
            }
            else -> Unit
        }
        return result
    }

    private fun result(action: LocalCommandAction, reply: String) =
        LocalCommandResult(action = action, reply = reply)

    private fun normalize(value: String): String =
        value.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9:,.]+"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(Regex("""\b(?:пожалуйста|прошу|марф|марфа|марфу|марфе|марфой)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun searchable(value: String): String = normalize(value).replace(Regex("""[,.:]"""), " ")

    private fun stem(word: String): String {
        val endings = listOf(
            "иями", "ами", "ого", "ему", "ому", "ыми", "ими", "ая", "яя",
            "ое", "ее", "ые", "ие", "ать", "ить", "еть", "ять", "ой", "ый",
            "ий", "ов", "ев", "ам", "ям", "ах", "ях", "ы", "и", "а", "я", "о", "е"
        )
        for (ending in endings) if (word.length > ending.length + 2 && word.endsWith(ending)) return word.removeSuffix(ending)
        return word
    }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any { text.contains(it) }

    private fun formatDelay(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
        val hours = minutes / 60L
        val rest = minutes % 60L
        return when {
            hours > 0 && rest > 0 -> "$hours ч $rest мин"
            hours > 0 -> "$hours ч"
            else -> "$minutes мин"
        }
    }

    companion object {
        private const val MAX_DELAY_MS = 7L * 24L * 60L * 60_000L
        private val STOP_WORDS = setOf(
            "открой", "открыть", "закрой", "закрыть", "включи", "включить",
            "выключи", "выключить", "запусти", "запустить", "останови", "остановить",
            "если", "когда", "при", "температура", "температур", "через", "спустя",
            "минут", "минуту", "час", "часа", "пожалуйста", "марфа"
        )
    }
}
