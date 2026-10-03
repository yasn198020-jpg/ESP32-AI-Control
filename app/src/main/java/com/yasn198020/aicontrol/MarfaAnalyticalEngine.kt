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

        // Context comes from actual IoTManager page/tab names.
        // Text pages are resolved deterministically. Pages made only of symbols/emoji
        // are semantic context and are handled by the deterministic semantic resolver instead of
        // being rejected as "unknown" by lexical matching.
        val contextPages = if (page == null) matchingContextPages(normalized, devices) else emptyList()
        val contextTokens = if (page == null) contextTokens(normalized, devices) else emptyList()
        val hasNonLexicalPage = devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { it.page }
            .filter { it.isNotBlank() }
            .any(::isNonLexicalPage)
        val unknownContext = if (page == null && contextPages.isEmpty() && !hasNonLexicalPage) {
            unknownContextWords(normalized, devices)
        } else {
            emptyList()
        }

        // If the command contains context but the available page is emoji/symbol-only,
        // lexical analysis must NOT invent a candidate and must NOT ask the user to clarify.
        // Return an empty resolution so the caller can decide without guessing.
        if (page == null &&
            contextPages.isEmpty() &&
            contextTokens.isNotEmpty() &&
            hasNonLexicalPage &&
            unknownContext.isEmpty()) {
            return ControlResolution()
        }

        val contextScoped = when {
            page != null -> all
            contextPages.isNotEmpty() -> all.filter { candidate ->
                searchableText(candidate.widget.page).trim() in contextPages
            }
            unknownContext.isNotEmpty() -> {
                return ControlResolution(
                    clarification = "Я нашла объект «${entityName(detectEntityKind(normalized))}», " +
                        "но не нашла совпадение контекста «${unknownContext.joinToString(", ")}». " +
                        "Уточните название вкладки."
                )
            }
            else -> all
        }
        // Explicit page/tab is a hard constraint.
        val scoped = if (page == null) contextScoped
        else contextScoped.filter { searchableText(it.widget.page).trim() == page }

        val explicitEntity = detectEntityKind(normalized)
        val entityScoped = if (explicitEntity == EntityKind.GENERIC) {
            scoped
        } else {
            val matching = scoped.filter { candidate ->
                entityMatches(explicitEntity, candidate.widget)
            }
            // An explicitly spoken object is a hard semantic constraint.
            // Never fall back to an unrelated "close/open" control.
            matching
        }

        /*
         * A page can contain many controllable widgets that are not themselves
         * targets of the spoken action: automatic-mode toggles, limit switches,
         * etc.  They must not enter an "open/close" clarification just because
         * they happen to be TOGGLE/BUTTON widgets on the same page.
         *
         * This is intentionally semantic, not tied to any particular device,
         * page name or widget ID.
         */
        val actionScoped = entityScoped
            .filter { it.score > 0 }
            .filter { isActionRelevantControl(it.widget, desiredValue, normalized) }

        val candidates = actionScoped
            .sortedWith(
                compareByDescending<ControlCandidate> { it.score }
                    .thenBy { it.widget.order }
                    .thenBy { normalize(it.widget.page) }
                    .thenBy { it.widget.id }
            )

        if (candidates.isEmpty()) {
            val suffix = if (page == null) "" else
                " на вкладке «" + prettyPage(page, devices) + "»"
            return ControlResolution(
                clarification = "Я не нашла однозначный управляемый элемент" +
                    suffix +
                    ". Назовите объект, ID элемента или вкладку."
            )
        }

        val best = candidates.first()
        val second = candidates.getOrNull(1)

        // A unique candidate inside an explicitly resolved semantic context is
        // already unambiguous. Do not reject it merely because its lexical score
        // is low: "огурцами" may identify the page even when the control title
        // itself is generic ("автомат управления").
        val contextResolved = page != null || contextPages.size == 1
        if (candidates.size == 1 && contextResolved) {
            return ControlResolution(candidate = best, candidates = candidates)
        }

        val tied = candidates.count { it.score == best.score } > 1
        val weak = best.score < 22
        val tooClose = second != null &&
            second.score >= 18 &&
            best.score - second.score < 5

        if (tied || weak || tooClose) {
            return ControlResolution(
                candidates = candidates.take(5),
                clarification = clarificationForControls(candidates)
            )
        }

        return ControlResolution(candidate = best, candidates = candidates)
    }

    fun resolveSensor(text: String, devices: List<Device>): SensorResolution {
        val normalized = normalize(text)
        val page = explicitPage(normalized, devices)
        val kind = detectKind(normalized)

        val all = devices.flatMap { device ->
            device.widgets
                .filter {
                    it.type == WidgetState.Type.VALUE ||
                        it.type == WidgetState.Type.STATUS
                }
                .mapNotNull { widget ->
                    val score = sensorScore(normalized, kind, page, device, widget)
                    if (score <= 0) null else SensorCandidate(
                        device,
                        widget,
                        score,
                        sensorReasons(normalized, kind, page, widget)
                    )
                }
        }

        val contextPages = if (page == null) matchingContextPages(normalized, devices) else emptyList()
        val contextTokens = if (page == null) contextTokens(normalized, devices) else emptyList()
        val unknownContext = if (page == null && contextPages.isEmpty() && contextTokens.isEmpty()) {
            unknownContextWords(normalized, devices)
        } else {
            emptyList()
        }

        // Context may be stored in a sensor title ("Огурцы") on a generic page such as "Температура".
        // Match page + title + device name with the same Russian-inflection semantics
        // used by the control resolver. This also covers arbitrary object names.
        val contextScoped = when {
            page != null -> all
            contextTokens.isNotEmpty() -> {
                val matching = all.filter { candidate ->
                    val corpus = semanticSearchText(
                        candidate.device.name + " " +
                            candidate.widget.page + " " +
                            candidate.widget.title + " " +
                            candidate.widget.definitionName
                    )
                    contextTokens.all { token -> containsSemanticToken(corpus, token.key) }
                }
                if (matching.isNotEmpty()) matching else {
                    return SensorResolution(
                        clarification = "Я нашла датчик, но не нашла совпадение контекста «" +
                            contextTokens.joinToString(", ") { it.original } +
                            "». Уточните название вкладки или объекта."
                    )
                }
            }
            contextPages.isNotEmpty() -> all.filter { normalize(it.widget.page) in contextPages }
            unknownContext.isNotEmpty() -> {
                return SensorResolution(
                    clarification = "Я нашла датчик, но не нашла совпадение контекста «" +
                        unknownContext.joinToString(", ") +
                        "». Уточните название вкладки или объекта."
                )
            }
            else -> all
        }

        val scoped = if (page == null) contextScoped
        else contextScoped.filter { normalize(it.widget.page) == page }

        val candidates = scoped.sortedWith(
            compareByDescending<SensorCandidate> { it.score }
                .thenBy { normalize(it.widget.page) }
                .thenBy { it.widget.order }
                .thenBy { it.widget.id }
        )

        if (candidates.isEmpty()) {
            return SensorResolution(
                clarification = when (kind) {
                    Kind.TEMPERATURE ->
                        "Какую температуру показать? Укажите объект или вкладку."
                    Kind.HUMIDITY ->
                        "Какую влажность показать? Укажите объект или вкладку."
                    Kind.PRESSURE ->
                        "Какое давление показать? Укажите объект или вкладку."
                    else ->
                        "Какой датчик показать? Назовите его или укажите вкладку."
                }
            )
        }

        val best = candidates.first()
        val second = candidates.getOrNull(1)

        // The same rule applies to sensors: once the spoken context resolves
        // to exactly one sensor, a low lexical score is not ambiguity.
        val contextResolved = page != null || contextPages.size == 1
        if (candidates.size == 1 && contextResolved) {
            return SensorResolution(candidate = best, candidates = candidates)
        }

        val tied = candidates.count { it.score == best.score } > 1
        val weak = best.score < 16
        val tooClose = second != null &&
            second.score >= 14 &&
            best.score - second.score < 4

        if (tied || weak || tooClose) {
            return SensorResolution(
                candidates = candidates.take(5),
                clarification = clarificationForSensors(candidates)
            )
        }

        return SensorResolution(candidate = best, candidates = candidates)
    }

    fun detectKind(text: String): Kind = when {
        containsAny(text, "температур", "темп", "градус", "жарко", "холодно") ->
            Kind.TEMPERATURE
        containsAny(text, "влажност", "влажн") ->
            Kind.HUMIDITY
        containsAny(text, "давлен") ->
            Kind.PRESSURE
        else -> Kind.VALUE
    }

    fun explicitPage(text: String, devices: List<Device>): String? {
        val marker = Regex(
            """(?:на\s+страниц(?:е|у)|на\s+вкладк(?:е|у)|во\s+вкладк(?:е|у)|в\s+вкладк(?:е|у)|страниц(?:а|у)|вкладк(?:а|у))\s+"""
        ).find(text) ?: return null

        val tail = " " + text.substring(marker.range.last + 1).trim() + " "
        return devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { normalize(it.page).trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .firstOrNull { page -> tail.contains(" " + page + " ") }
    }

    private fun explicitElementId(text: String, devices: List<Device>): String? {
        val tokens = tokenized(text)
        return devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .firstOrNull { id -> tokens.any { it.equals(id, ignoreCase = true) } }
    }

    private fun entityMatches(kind: EntityKind, widget: WidgetState): Boolean {
        val searchable = normalize(
            listOf(
                widget.title,
                widget.page,
                widget.definitionName,
                widget.configJson
            ).joinToString(" ")
        )

        val aliases = aliases(kind).second
        if (aliases.any { searchable.contains(it) }) return true

        return when (kind) {
            EntityKind.DOOR -> searchable.contains("двер") || searchable.contains("вход")
            EntityKind.VENT -> searchable.contains("форточ") ||
                searchable.contains("фрамуг") || searchable.contains("вент")
            EntityKind.WINDOW -> searchable.contains("окн") || searchable.contains("форточ")
            EntityKind.GATE -> searchable.contains("ворот")
            EntityKind.PUMP -> searchable.contains("насос") || searchable.contains("помп")
            EntityKind.FAN -> searchable.contains("вентил")
            EntityKind.HEATER -> searchable.contains("обогрев") ||
                searchable.contains("отоп") || searchable.contains("нагрев")
            EntityKind.VALVE -> searchable.contains("клапан") || searchable.contains("кран")
            EntityKind.LIGHT -> searchable.contains("свет") || searchable.contains("ламп")
            EntityKind.IRRIGATION -> searchable.contains("полив") || searchable.contains("орош")
            EntityKind.GENERIC -> true
        }
    }

    private fun controlScore(
        text: String,
        page: String?,
        exactId: String?,
        device: Device,
        widget: WidgetState,
        desiredValue: String?
    ): Int {
        if (exactId != null && !widget.id.equals(exactId, true)) return -100000

        val title = semanticSearchText(widget.title)
        val widgetPage = semanticSearchText(widget.page)
        val deviceName = semanticSearchText(device.name)
        var score = 0

        val entity = detectEntityKind(text)
        val aliases = aliases(entity)

        if (aliases.first.any { text.contains(it) }) {
            if (aliases.second.any { title.contains(it) }) score += 34
            if (aliases.second.any { widgetPage.contains(it) }) score += 10
            if (aliases.second.any { deviceName.contains(it) }) score += 8
        }

        tokenized(text).forEach { token ->
            if (token.length >= 3) {
                val key = contextTokenKey(token)
                if (containsSemanticToken(title, key)) score += 6
                if (containsSemanticToken(widgetPage, key)) score += 3
                if (containsSemanticToken(deviceName, key)) score += 2
            }
        }

        val explicitPhysical = containsAny(
            text,
            "реле", "выход", "выходной", "кнопк", "gpio", "канал", "исполнитель"
        )
        val hasOpen = containsAny(
            title, "открыть", "открой", "открыва", "распах", "поднять", "подъем"
        )
        val hasClose = containsAny(
            title, "закрыть", "закрой", "закрыва", "опустить", "опуск"
        )
        val isStateIndicator =
            (title.contains("открыт") && title.contains("закрыт")) ||
                containsAny(
                    title,
                    "состояние", "статус", "индикатор", "положение"
                )

        score += when (desiredValue) {
            "1" ->
                (if (hasOpen) 40 else 0) +
                    (if (hasClose && !hasOpen) -18 else 0) +
                    (if (isStateIndicator && !explicitPhysical) 60 else 0) +
                    (if (explicitPhysical && !isStateIndicator) 45 else 0)
            "0" ->
                (if (hasClose) 40 else 0) +
                    (if (hasOpen && !hasClose) -18 else 0) +
                    (if (isStateIndicator && !explicitPhysical) 60 else 0) +
                    (if (explicitPhysical && !isStateIndicator) 45 else 0)
            else ->
                if (isStateIndicator && !explicitPhysical) 12 else 0
        }

        if (page != null && widgetPage != page) return -100000
        if (page != null) score += 500
        return score
    }

    private fun matchingContextPages(text: String, devices: List<Device>): List<String> {
        val commandTokens = contextTokens(text, devices)
        if (commandTokens.isEmpty()) return emptyList()

        return devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { it.page }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { rawPage ->
                // Use the same internal semantic representation as the local resolver.
                // UI keeps the original emoji, but 🍅 must behave as "помидоры"
                // for page/context matching as well.
                val lexicalPage = searchableText(rawPage).trim()
                if (lexicalPage.isBlank()) return@mapNotNull null

                val pageTokens = tokenized(lexicalPage)
                    .map(::contextTokenKey)
                    .filter { it.isNotBlank() }

                if (pageTokens.isEmpty()) return@mapNotNull null

                /*
                 * Not every noun in a command is page context. In
                 * "выключи автомат управления огурцами", "автомат" and
                 * "управления" describe the control, while "огурцами" selects
                 * the page. Use only command tokens that can actually match a
                 * page token; this keeps generic control titles from blocking
                 * an otherwise unique context match.
                 */
                val relevantCommandTokens = commandTokens.filter { commandToken ->
                    pageTokens.any { pageToken -> lexicalMatch(commandToken, pageToken) }
                }
                if (relevantCommandTokens.isEmpty()) return@mapNotNull null

                val matched = relevantCommandTokens.count { commandToken ->
                    pageTokens.any { pageToken -> lexicalMatch(commandToken, pageToken) }
                }

                // The user's context does not need to repeat every descriptive
                // word of the tab. "огурцами" must match "Огурцы" even though
                // the command contains unrelated control words.
                if (matched == relevantCommandTokens.size) {
                    val pageControlScore = devices.asSequence()
                        .flatMap { device -> device.widgets.asSequence().map { device to it } }
                        .filter { (_, widget) ->
                            searchableText(widget.page).trim() == lexicalPage
                        }
                        .filter { (_, widget) -> isControllable(widget) }
                        .map { (device, widget) ->
                            controlScore(
                                text = text,
                                page = null,
                                exactId = null,
                                device = device,
                                widget = widget,
                                desiredValue = desiredValueFromText(text)
                            )
                        }
                        .maxOrNull() ?: 0

                    // Several IoTManager tabs may share the same semantic marker
                    // (for example both "Теплиц 🥒" and "⚒️ 🥒"). The marker alone
                    // is therefore not enough. Prefer the tab whose controls actually
                    // match the spoken object/action. This keeps emoji pages semantic
                    // without hard-coding any particular crop or tab name.
                    lexicalPage to (matched * 1000 + pageControlScore)
                } else null
            }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { it.first.length }
            )
            .let { ranked ->
                val bestScore = ranked.firstOrNull()?.second ?: return@let emptyList()
                ranked
                    .filter { it.second == bestScore }
                    .map { it.first }
                    .toList()
            }
    }

    private fun unknownContextWords(text: String, devices: List<Device>): List<String> {
        val tokens = contextTokens(text, devices)
        if (tokens.isEmpty()) return emptyList()

        val pageTokens = devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { searchableText(it.page) }
            .filter { it.isNotBlank() }
            .distinct()
            .flatMap { tokenized(it).asSequence() }
            .map(::contextTokenKey)
            .filter { it.isNotBlank() }
            .toSet()

        return tokens
            .filterNot { isSelectionToken(it.original) }
            .filter { token -> pageTokens.none { pageToken ->
                token.key == pageToken ||
                    (token.key.length >= 5 && pageToken.length >= 5 &&
                        (token.key.startsWith(pageToken) || pageToken.startsWith(token.key)))
            } }
            .distinct()
            .map { it.original }
            .toList()
    }

    private fun desiredValueFromText(text: String): String? = when {
        containsAny(
            text,
            "открой", "открыть", "открывай", "подними", "поднять",
            "распахни", "раскрой", "включи", "включить", "включай",
            "запусти", "запустить", "зажги"
        ) -> "1"
        containsAny(
            text,
            "закрой", "закрыть", "закрывай", "опусти", "опустить",
            "запечатай", "выключи", "выключить", "выключай",
            "останови", "остановить", "погаси"
        ) -> "0"
        else -> null
    }

    private fun isSelectionToken(token: String): Boolean {
        val normalized = normalize(token)
        if (normalized.matches(Regex("\\d+"))) return true
        return normalized in setOf(
            "ноль", "один", "одна", "одно", "два", "две", "три", "четыре",
            "пять", "шесть", "семь", "восемь", "девять", "десять",
            "первый", "первая", "первое", "первую", "первого", "первом",
            "второй", "вторая", "второе", "вторую", "второго", "втором",
            "третий", "третья", "третье", "третью", "третьего", "третьем",
            "четвертый", "четвертая", "четвертую", "четвертого", "четвертом",
            "пятый", "пятая", "пятое", "пятую", "пятого", "пятом"
        )
    }

    private data class ContextToken(
        val original: String,
        val key: String
    )

    private fun contextTokens(text: String, devices: List<Device>): List<ContextToken> {
        val actionWords = setOf(
            "открой", "открыть", "открывай", "подними", "поднять", "распахни", "раскрой",
            "закрой", "закрыть", "закрывай", "опусти", "опустить", "запечатай",
            "включи", "включить", "включай", "запусти", "запустить", "зажги",
            "выключи", "выключить", "выключай", "останови", "остановить", "погаси",
            "установи", "установить", "поставь", "поставить", "задай", "задать",
            "назначь", "назначить"
        )

        val grammarWords = setOf(
            "а", "и", "на", "во", "в", "по", "к", "ко", "у", "из", "для", "от", "до",
            "с", "со", "это", "эта", "этот", "этого", "там", "здесь", "нет", "да",
            "пожалуйста", "марфа", "марфу", "марфе", "марфой",
            "сейчас", "сегодня", "завтра", "потом", "позже", "сразу",
            "мне", "меня", "его", "ее", "её", "эту", "сюда", "туда", "тогда",
            "через", "спустя", "час", "часа", "часов", "ч",
            "минут", "минуту", "минуты", "мин", "секунд", "секунду", "секунды",
            "градус", "градуса", "градусов",
            "вкладка", "вкладке", "вкладку", "страница", "странице", "страницу",
            "номер", "значение", "режим",
            "какая", "какое", "какие", "какую", "какой", "сколько",
            "покажи", "показать", "показывай", "скажи", "сказать", "узнай", "узнать",
            "что", "датчик", "датчики",
            "температура", "температуры", "температур", "темп", "темпа",
            "влажность", "влажности", "влажн", "давление", "давления", "давлен",
            "реле", "выход", "выхода", "выходной", "выходного",
            "кнопка", "кнопки", "кнопку", "кнопкой", "gpio", "канал", "канала",
            "исполнитель", "исполнителя"
        )

        val numberWords = setOf(
            "ноль", "один", "одна", "одно", "два", "две", "три", "четыре",
            "пять", "шесть", "семь", "восемь", "девять", "десять",
            "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать",
            "девятнадцать", "двадцать", "тридцать", "сорок", "пятьдесят",
            "шестьдесят", "семьдесят", "восемьдесят", "девяносто", "сто"
        )

        val timeUnits = setOf(
            "секунда", "секунды", "секунду", "секунд",
            "минута", "минуты", "минуту", "минут", "мин",
            "час", "часа", "часов", "ч"
        )

        val entityAliases = aliases(detectEntityKind(normalize(text))).first.toSet()
        val knownIds = devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { normalize(it.id) }
            .filter { it.isNotBlank() }
            .toSet()

        val tokens = tokenized(normalize(text))
        val durationNumberIndexes = buildSet {
            tokens.indices.forEach { index ->
                if (tokens[index] !in numberWords) return@forEach
                var j = index + 1
                var numberCount = 0
                while (j < tokens.size && numberCount < 3 && tokens[j] in numberWords) {
                    numberCount++
                    j++
                }
                if (j < tokens.size && tokens[j] in timeUnits) {
                    add(index)
                    for (k in index + 1 until j) add(k)
                }
            }
        }

        return tokens.withIndex()
            .filterNot { it.value in actionWords || it.value in grammarWords }
            .filterNot { it.value.matches(Regex("\\d+")) }
            .filterNot { it.index in durationNumberIndexes }
            .filterNot { it.value in knownIds }
            .filterNot { token -> entityAliases.any { alias -> token.value.startsWith(alias) } }
            .filter { it.value.length >= 2 }
            .map { token -> ContextToken(token.value, contextTokenKey(token.value)) }
            .filter { it.key.isNotBlank() }
    }

    private fun contextTokenKey(token: String): String {
        val normalized = normalize(token).trim()
        val cardinal = mapOf(
            "ноль" to "0", "один" to "1", "одна" to "1", "одно" to "1",
            "два" to "2", "две" to "2", "три" to "3", "четыре" to "4",
            "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8",
            "девять" to "9", "десять" to "10"
        )
        if (normalized.matches(Regex("\\d+"))) return normalized
        cardinal[normalized]?.let { return it }
        val ordinal = mapOf(
            "первый" to "1", "первая" to "1", "первое" to "1", "первую" to "1", "первого" to "1",
            "второй" to "2", "вторая" to "2", "второе" to "2", "вторую" to "2", "второго" to "2",
            "третий" to "3", "третья" to "3", "третье" to "3", "третью" to "3", "третьего" to "3",
            "четвертый" to "4", "четвертая" to "4", "четвертую" to "4", "четвертого" to "4",
            "пятый" to "5", "пятая" to "5", "пятое" to "5", "пятую" to "5", "пятого" to "5",
            "первом" to "1", "первых" to "1", "втором" to "2", "вторых" to "2",
            "третьем" to "3", "третьих" to "3", "четвертом" to "4", "четвертых" to "4",
            "пятом" to "5", "пятых" to "5"
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
            "иями", "ями", "ами", "ию", "ью", "ою", "ею", "ого", "ему", "ому",
            "ыми", "ими", "ей", "ов", "ев", "ам", "ям", "ах", "ях", "ом", "ем",
            "ым", "им", "ую", "юю", "ая", "яя", "ое", "ее", "ые", "ие",
            "ать", "ить", "еть", "ять", "ой", "ый", "ий", "ь", "й", "ы", "и",
            "а", "я", "у", "ю", "о", "е"
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
        val title = semanticSearchText(widget.title)
        val widgetPage = semanticSearchText(widget.page)
        val deviceName = semanticSearchText(device.name)
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
            if (token.length >= 3) {
                val key = contextTokenKey(token)
                if (containsSemanticToken(title, key)) score += 8
                if (containsSemanticToken(widgetPage, key)) score += 5
                if (containsSemanticToken(deviceName, key)) score += 3
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

    private fun semanticSearchText(value: String): String =
        EmojiSemanticText.normalize(value)
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .trim()

    private fun containsSemanticToken(text: String, key: String): Boolean {
        if (key.isBlank()) return false
        return tokenized(text)
            .map(::contextTokenKey)
            .any { target -> key == target || lexicalMatch(ContextToken(key, key), target) }
    }

    private fun searchableText(value: String): String =
        EmojiSemanticText.normalize(value)
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .trim()

    private fun isNonLexicalPage(page: String): Boolean {
        if (page.isBlank()) return false
        return normalize(page).isBlank() && page.any {
            !it.isLetterOrDigit() && !it.isWhitespace()
        }
    }

    private fun prettyPage(page: String, devices: List<Device>): String =
        devices.asSequence()
            .flatMap { it.widgets.asSequence() }
            .map { it.page }
            .firstOrNull { normalize(it) == page } ?: page

    private fun isActionRelevantControl(
        widget: WidgetState,
        desiredValue: String?,
        commandText: String
    ): Boolean {
        if (desiredValue != "1" && desiredValue != "0") return true

        /*
         * Feedback widgets and automatic-mode selectors should be excluded
         * only when the user is actually asking to open/close a physical object.
         * For "включи/выключи автомат" the automatic-mode widget is the target
         * and must remain eligible.
         */
        val isOpenCloseCommand = containsAny(
            commandText,
            "открой", "открыть", "открывай", "подними", "поднять",
            "распахни", "раскрой", "закрой", "закрыть", "закрывай",
            "опусти", "опустить", "запечатай"
        )
        if (!isOpenCloseCommand) return true

        val title = semanticSearchText(widget.title)

        if (containsAny(title, "концевик", "концевой", "конечный")) return false

        if (containsAny(title, "автомат", "режим")) {
            val hasOpenCloseSemantics = containsAny(
                title,
                "открыт", "открыть", "открой", "закрыт", "закрыть", "закрой",
                "двер", "форточ", "ворот", "окн"
            )
            if (!hasOpenCloseSemantics) return false
        }

        return true
    }

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
