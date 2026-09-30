package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import java.util.Locale

/**
 * Central deterministic reasoning layer for Marfa.
 *
 * It resolves the user's target from observable evidence and refuses to guess.
 * Element ID is the authoritative identity. Device ID is transport metadata.
 */
class MarfaAnalyticalEngine {

    enum class Kind { TEMPERATURE, HUMIDITY, PRESSURE, VALUE }

    enum class EntityKind {
        DOOR, VENT, WINDOW, GATE, PUMP, FAN, HEATER, VALVE, LIGHT, IRRIGATION, GENERIC
    }

    data class SensorCandidate(
        val device: Device,
        val widget: WidgetState,
        val score: Int,
        val reasons: List<String>
    )

    data class SensorResolution(
        val candidate: SensorCandidate? = null,
        val candidates: List<SensorCandidate> = emptyList(),
        val clarification: String? = null
    )

    data class ControlCandidate(
        val device: Device,
        val widget: WidgetState,
        val score: Int,
        val reasons: List<String>
    )

    data class ControlResolution(
        val candidate: ControlCandidate? = null,
        val candidates: List<ControlCandidate> = emptyList(),
        val clarification: String? = null
    )

    fun resolveControl(
        text: String,
        devices: List<Device>,
        desiredValue: String? = null
    ): ControlResolution {
        val normalized = normalize(text)
        val page = explicitPage(normalized, devices)
        val exactId = explicitElementId(normalized, devices)

        val all = devices.flatMap { device ->
            device.widgets
                .filter(::isControllable)
                .map { widget ->
                    ControlCandidate(
                        device,
                        widget,
                        controlScore(normalized, page, exactId, device, widget, desiredValue),
                        controlReasons(normalized, page, exactId, widget, desiredValue)
                    )
                }
        }

        if (exactId != null) {
            val matches = all.filter { it.widget.id.equals(exactId, ignoreCase = true) }
            return when {
                matches.size == 1 -> ControlResolution(matches.single(), matches)
                matches.size > 1 -> ControlResolution(
                    candidates = matches,
                    clarification = "ID элемента «" + exactId +
                        "» найден несколько раз. Нужен уникальный ID."
                )
                else -> ControlResolution(
                    clarification = "Элемент с ID «" + exactId + "» не найден."
                )
            }
        }

        // Context is resolved against REAL IoTManager page/tab names.
        // No hardcoded context dictionary is used.
        val contextPages = if (page == null) matchingContextPages(normalized, devices) else emptyList()
        val unknownContext = if (page == null && contextPages.isEmpty()) {
            unknownContextWords(normalized, devices)
        } else {
            emptyList()
        }

        val contextScoped = when {
            page != null -> all
            contextPages.isNotEmpty() -> all.filter { candidate ->
                normalize(candidate.widget.page) in contextPages
            }
            unknownContext.isNotEmpty() -> {
                return ControlResolution(
                    clarification = "Я нашла объект «\${entityName(detectEntityKind(normalized))}», " +
                        "но не нашла совпадение контекста «\${unknownContext.joinToString(", ")}». " +
                        "Уточните название вкладки."
                )
            }
            else -> all
        }

        val scoped = if (page == null) contextScoped
        else contextScoped.filter { normalize(it.widget.page) == page }

    private fun matchingContextPages(text: String, devices: List<Device>): List<String> {
        val commandTokens = contextTokens(text)
        if (commandTokens.isEmpty()) return emptyList()

        return devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { normalize(it.page) }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { page ->
                val pageTokens = tokenized(page)
                    .map(::contextTokenKey)
                    .filter { it.isNotBlank() }
                if (pageTokens.isEmpty()) return@mapNotNull null

                val matched = pageTokens.count { pageToken ->
                    commandTokens.any { commandToken -> lexicalMatch(commandToken, pageToken) }
                }
                if (matched == pageTokens.size) page to matched else null
            }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { it.first.length }
            )
            .map { it.first }
            .toList()
    }

    private fun unknownContextWords(text: String, devices: List<Device>): List<String> {
        val tokens = contextTokens(text)
        if (tokens.isEmpty()) return emptyList()

        val pageTokens = devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { normalize(it.page) }
            .filter { it.isNotBlank() }
            .distinct()
            .flatMap { tokenized(it).asSequence() }
            .map(::contextTokenKey)
            .filter { it.isNotBlank() }
            .toSet()

        return tokens
            .filter { token -> pageTokens.none { lexicalMatch(token, it) } }
            .distinct()
            .map { it.original }
            .toList()
    }

    private data class ContextToken(
        val original: String,
        val key: String
    )

    private fun contextTokens(text: String): List<ContextToken> {
        val actionWords = setOf(
            "открой", "открыть", "открывай", "подними", "поднять", "распахни", "раскрой",
            "закрой", "закрыть", "закрывай", "опусти", "опустить", "запечатай",
            "включи", "включить", "включай", "запусти", "запустить", "зажги",
            "выключи", "выключить", "выключай", "останови", "остановить", "погаси",
            "установи", "установить", "поставь", "поставить", "задай", "задать",
            "назначь", "назначить"
        )

        val controlWords = setOf(
            "дверь", "двери", "дверью", "форточка", "форточки", "форточку", "форточкой",
            "фрамуга", "фрамуги", "фрамугу", "окно", "окна", "окном", "окну",
            "ворота", "ворот", "насос", "насоса", "насосом",
            "вентилятор", "вентиляторе", "вентилятором", "обогрев", "отопление",
            "отоплением", "нагрев", "клапан", "кран", "свет", "лампа", "лампу",
            "лампой", "полив", "орошение"
        )

        val grammarWords = REFERENCE_STOP_WORDS + setOf(
            "сейчас", "сегодня", "завтра", "потом", "позже", "сразу",
            "мне", "меня", "его", "ее", "её", "это", "этот", "эта", "эту",
            "там", "здесь", "сюда", "туда", "тогда",
            "час", "часа", "часов", "ч", "минут", "минуту", "минуты", "мин",
            "градус", "градуса", "градусов",
            "на", "во", "в", "из", "у", "к", "ко", "с", "со", "по", "для", "от", "до",
            "вкладка", "вкладке", "вкладку", "страница", "странице", "страницу",
            "номер", "значение", "режим"
        )

        val entityAliases = aliases(detectEntityKind(normalize(text))).first.toSet()

        return tokenized(normalize(text))
            .filterNot { it in actionWords || it in controlWords || it in grammarWords }
            .filterNot { it.length < 2 }
            .filterNot { it.matches(Regex("\\d+")) }
            .filterNot { token -> entityAliases.any { alias -> token.startsWith(alias) } }
            .map { token -> ContextToken(token, contextTokenKey(token)) }
            .filter { it.key.isNotBlank() }
    }

    private fun contextTokenKey(token: String): String {
        val normalized = normalize(token).trim()
        if (normalized.matches(Regex("\\d+"))) return normalized

        val cardinal = mapOf(
            "ноль" to "0", "один" to "1", "одна" to "1", "одно" to "1",
            "два" to "2", "две" to "2", "три" to "3", "четыре" to "4",
            "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8",
            "девять" to "9", "десять" to "10"
        )
        cardinal[normalized]?.let { return it }

        val ordinal = mapOf(
            "первый" to "1", "первая" to "1", "первое" to "1", "первую" to "1", "первого" to "1",
            "второй" to "2", "вторая" to "2", "второе" to "2", "вторую" to "2", "второго" to "2",
            "третий" to "3", "третья" to "3", "третье" to "3", "третью" to "3", "третьего" to "3",
            "четвертый" to "4", "четвертая" to "4", "четвертую" to "4", "четвертого" to "4",
            "пятый" to "5", "пятая" to "5", "пятое" to "5", "пятую" to "5", "пятого" to "5"
        )
        ordinal[normalized]?.let { return it }

        return stemRussian(normalized)
    }

    private fun lexicalMatch(a: ContextToken, b: String): Boolean =
        a.key == b ||
            (a.key.length >= 5 && b.length >= 5 &&
                (a.key.startsWith(b) || b.startsWith(a.key)))

    private fun stemRussian(word: String): String {
        val endings = listOf(
            "иями", "ями", "ами", "ию", "ью", "ою", "ею",
            "ого", "ему", "ому", "ыми", "ими", "ей", "ов", "ев",
            "ам", "ям", "ах", "ях", "ом", "ем", "ым", "им",
            "ую", "юю", "ая", "яя", "ое", "ее", "ые", "ие",
            "ать", "ить", "еть", "ять", "ой", "ый", "ий",
            "ь", "й", "ы", "и", "а", "я", "у", "ю", "о", "е"
        )
        for (ending in endings) {
            if (word.length > ending.length + 2 && word.endsWith(ending)) {
                return word.removeSuffix(ending)
            }
        }
        return word
    }

    private fun entityName(kind: EntityKind): String = when (kind) {
        EntityKind.DOOR -> "дверь"
        EntityKind.VENT -> "форточку"
        EntityKind.WINDOW -> "окно"
        EntityKind.GATE -> "ворота"
        EntityKind.PUMP -> "насос"
        EntityKind.FAN -> "вентилятор"
        EntityKind.HEATER -> "обогрев"
        EntityKind.VALVE -> "клапан"
        EntityKind.LIGHT -> "свет"
        EntityKind.IRRIGATION -> "полив"
        EntityKind.GENERIC -> "объект"
    }

    private fun controlReasons(
        text: String,
        page: String?,
        exactId: String?,
        widget: WidgetState,
        desiredValue: String?
    ): List<String> = buildList {
        val entity = detectEntityKind(text)
        if (entity != EntityKind.GENERIC) {
            add(entity.name.lowercase(Locale("ru", "RU")))
        }
        if (page != null && normalize(widget.page) == page) {
            add("вкладка " + widget.page)
        }
        if (exactId != null && widget.id.equals(exactId, true)) {
            add("ID " + widget.id)
        }
        if (desiredValue != null) add("значение " + desiredValue)
        if (widget.title.isNotBlank()) add("название " + widget.title)
    }

    private fun sensorScore(
        text: String,
        kind: Kind,
        page: String?,
        device: Device,
        widget: WidgetState
    ): Int {
        val title = normalize(widget.title)
        val widgetPage = normalize(widget.page)
        val deviceName = normalize(device.name)
        var score = 0

        when (kind) {
            Kind.TEMPERATURE -> {
                if (title.contains("температур") || title.contains("темп")) score += 22
                if (widget.unit.contains("°") || widget.unit.equals("c", true)) score += 10
            }
            Kind.HUMIDITY -> {
                if (title.contains("влажн")) score += 22
                if (widget.unit.contains("%")) score += 7
            }
            Kind.PRESSURE -> {
                if (title.contains("давлен")) score += 22
                val unit = widget.unit.lowercase(Locale("ru", "RU"))
                if (unit.contains("па") || unit.contains("bar")) score += 7
            }
            Kind.VALUE -> Unit
        }

        tokenized(text).forEach { token ->
            if (token.length >= 4) {
                if (title.contains(token)) score += 5
                if (widgetPage.contains(token)) score += 3
                if (deviceName.contains(token)) score += 2
            }
        }

        if (tokenized(text).any { it.equals(widget.id, ignoreCase = true) }) {
            score += 1000
        }

        if (page != null && widgetPage != page) return -100000
        if (page != null) score += 500
        return score
    }

    private fun sensorReasons(
        text: String,
        kind: Kind,
        page: String?,
        widget: WidgetState
    ): List<String> = buildList {
        if (kind != Kind.VALUE) add(kind.name.lowercase(Locale("ru", "RU")))
        if (page != null && normalize(widget.page) == page) {
            add("вкладка " + widget.page)
        }
        if (tokenized(text).any { it.equals(widget.id, ignoreCase = true) }) {
            add("ID " + widget.id)
        }
        if (widget.unit.isNotBlank()) add("единица " + widget.unit)
    }

    private fun clarificationForControls(candidates: List<ControlCandidate>): String {
        val names = candidates.take(5).map {
            val title = it.widget.title.ifBlank { it.widget.id }
            if (it.widget.page.isBlank()) title
            else title + " (вкладка " + it.widget.page + ")"
        }.distinct()
        return if (names.isEmpty()) {
            "Уточните, чем именно управлять."
        } else {
            "Уточните, что именно выбрать: " + names.joinToString(" или ")
        }
    }

    private fun clarificationForSensors(candidates: List<SensorCandidate>): String {
        val names = candidates.take(5).map {
            val title = it.widget.title.ifBlank { it.widget.id }
            if (it.widget.page.isBlank()) title
            else title + " (вкладка " + it.widget.page + ")"
        }.distinct()
        return if (names.isEmpty()) "Уточните, какой датчик нужен."
        else "Уточните, какой датчик использовать: " + names.joinToString(" или ")
    }

    private fun detectEntityKind(text: String): EntityKind = when {
        containsAny(text, "двер") -> EntityKind.DOOR
        containsAny(text, "форточ", "фрамуг") -> EntityKind.VENT
        containsAny(text, "окн") -> EntityKind.WINDOW
        containsAny(text, "ворот") -> EntityKind.GATE
        containsAny(text, "насос", "помп") -> EntityKind.PUMP
        containsAny(text, "вентилят", "вент") -> EntityKind.FAN
        containsAny(text, "обогрев", "отоп", "нагрев") -> EntityKind.HEATER
        containsAny(text, "клапан", "кран") -> EntityKind.VALVE
        containsAny(text, "свет", "ламп") -> EntityKind.LIGHT
        containsAny(text, "полив", "орош") -> EntityKind.IRRIGATION
        else -> EntityKind.GENERIC
    }

    private fun aliases(kind: EntityKind): Pair<List<String>, List<String>> = when (kind) {
        EntityKind.DOOR -> listOf("двер") to listOf("двер", "вход")
        EntityKind.VENT -> listOf("форточ", "фрамуг") to listOf("форточ", "фрамуг", "окн")
        EntityKind.WINDOW -> listOf("окн") to listOf("окн", "форточ")
        EntityKind.GATE -> listOf("ворот") to listOf("ворот")
        EntityKind.PUMP -> listOf("насос", "помп") to listOf("насос", "помп")
        EntityKind.FAN -> listOf("вентилят", "вент") to listOf("вентилят", "вент")
        EntityKind.HEATER -> listOf("обогрев", "отоп", "нагрев") to listOf("обогрев", "отоп", "нагрев")
        EntityKind.VALVE -> listOf("клапан", "кран") to listOf("клапан", "кран")
        EntityKind.LIGHT -> listOf("свет", "ламп") to listOf("свет", "ламп")
        EntityKind.IRRIGATION -> listOf("полив", "орош") to listOf("полив", "орош")
        EntityKind.GENERIC -> emptyList<String>() to emptyList()
    }

    private fun prettyPage(page: String, devices: List<Device>): String =
        devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { it.page }
            .firstOrNull { normalize(it) == page } ?: page

    private fun isControllable(widget: WidgetState): Boolean =
        widget.type == WidgetState.Type.TOGGLE ||
            widget.type == WidgetState.Type.BUTTON ||
            widget.type == WidgetState.Type.INPUT

    private fun tokenized(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun normalize(value: String): String =
        value.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9:,.]+"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun containsAny(text: String, vararg words: String): Boolean =
        words.any { text.contains(it) }
}
