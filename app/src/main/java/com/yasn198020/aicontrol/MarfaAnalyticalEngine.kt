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

        // Explicit page/tab is a hard constraint.
        val scoped = if (page == null) all
        else all.filter { normalize(it.widget.page) == page }

        val candidates = scoped
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<ControlCandidate> { it.score }
                    .thenBy { normalize(it.widget.page) }
                    .thenBy { it.widget.order }
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

        val scoped = if (page == null) all
        else all.filter { normalize(it.widget.page) == page }

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

    private fun controlScore(
        text: String,
        page: String?,
        exactId: String?,
        device: Device,
        widget: WidgetState,
        desiredValue: String?
    ): Int {
        if (exactId != null && !widget.id.equals(exactId, true)) return -100000

        val title = normalize(widget.title)
        val widgetPage = normalize(widget.page)
        val deviceName = normalize(device.name)
        var score = 0

        val entity = detectEntityKind(text)
        val aliases = aliases(entity)

        if (aliases.first.any { text.contains(it) }) {
            if (aliases.second.any { title.contains(it) }) score += 34
            if (aliases.second.any { widgetPage.contains(it) }) score += 10
            if (aliases.second.any { deviceName.contains(it) }) score += 8
        }

        tokenized(text).forEach { token ->
            if (token.length >= 4) {
                if (title.contains(token)) score += 6
                if (widgetPage.contains(token)) score += 3
                if (deviceName.contains(token)) score += 2
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
