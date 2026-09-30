package com.yasn198020.aicontrol
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
class MarfaAnalyticalEngine {
    enum class Kind { TEMPERATURE, HUMIDITY, PRESSURE, VALUE }
    data class SensorCandidate(val device: Device, val widget: WidgetState, val score: Int, val reasons: List<String>)
    data class SensorResolution(val candidate: SensorCandidate? = null, val candidates: List<SensorCandidate> = emptyList(), val clarification: String? = null)
    fun resolveSensor(text: String, devices: List<Device>): SensorResolution {
        val normalized = text.trim().lowercase(); val page = explicitPage(normalized, devices); val kind = detectKind(normalized)
        val candidates = devices.flatMap { device -> device.widgets.filter { it.type == WidgetState.Type.VALUE || it.type == WidgetState.Type.STATUS }.mapNotNull { widget ->
            val score = sensorScore(normalized, kind, page, device, widget)
            if (score <= 0) null else SensorCandidate(device, widget, score, sensorReasons(normalized, kind, page, widget))
        } }.sortedWith(compareByDescending<SensorCandidate> { it.score }.thenBy { it.widget.order }.thenBy { it.widget.id })
        if (candidates.isEmpty()) return SensorResolution(clarification = when (kind) {
            Kind.TEMPERATURE -> "Какую температуру показать? Укажите объект или вкладку."
            Kind.HUMIDITY -> "Какую влажность показать? Укажите объект или вкладку."
            Kind.PRESSURE -> "Какое давление показать? Укажите объект или вкладку."
            else -> "Какой датчик показать? Назовите его или укажите вкладку." })
        val best = candidates.first(); val tied = candidates.filter { it.score == best.score }; val near = candidates.getOrNull(1)
        if (tied.size > 1 || best.score < 16 || (near != null && best.score - near.score < 4 && near.score >= 14))
            return SensorResolution(candidates = candidates.take(4), clarification = clarificationForSensors(candidates))
        return SensorResolution(candidate = best, candidates = candidates)
    }
    fun detectKind(text: String): Kind = when {
        containsAny(text, "температур", "темп", "градус", "жарко", "холодно") -> Kind.TEMPERATURE
        containsAny(text, "влажност", "влажн") -> Kind.HUMIDITY
        containsAny(text, "давлен") -> Kind.PRESSURE
        else -> Kind.VALUE }
    fun explicitPage(text: String, devices: List<Device>): String? {
        val marker = Regex("""(?:на\s+страниц(?:е|у)|во\s+вкладк(?:е|у)|в\s+вкладк(?:е|у)|страниц(?:а|у)|вкладк(?:а|у))\s+""").find(text) ?: return null
        val tail = text.substring(marker.range.last + 1)
        return devices.asSequence().flatMap { it.widgets.asSequence() }.map { it.page.trim().lowercase() }.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
            .firstOrNull { page -> Regex("(^|\\s)" + Regex.escape(page) + "($|\\s)").containsMatchIn(tail) }
    }
    private fun sensorScore(text: String, kind: Kind, page: String?, device: Device, widget: WidgetState): Int {
        val title = widget.title.lowercase(); val widgetPage = widget.page.lowercase(); val deviceText = (device.name + " " + device.id).lowercase(); var score = 0
        when (kind) {
            Kind.TEMPERATURE -> { if (title.contains("температур") || title.contains("темп")) score += 22; if (widget.unit.contains("°") || widget.unit.equals("c", true)) score += 10 }
            Kind.HUMIDITY -> { if (title.contains("влажн")) score += 22; if (widget.unit.contains("%")) score += 7 }
            Kind.PRESSURE -> { if (title.contains("давлен")) score += 22; if (widget.unit.lowercase().contains("па") || widget.unit.lowercase().contains("bar")) score += 7 }
            Kind.VALUE -> {} }
        if (page != null) score += if (widgetPage == page) 100 else -100
        tokenize(text).forEach { token -> if (token.length >= 4) { if (title.contains(token)) score += 5; if (widgetPage.contains(token)) score += 3; if (deviceText.contains(token)) score += 2 } }
        if (tokenize(text).any { it == widget.id.lowercase() }) score += 1000; return score
    }
    private fun sensorReasons(text: String, kind: Kind, page: String?, widget: WidgetState): List<String> = buildList {
        if (kind != Kind.VALUE) add(kind.name.lowercase()); if (page != null && widget.page.lowercase() == page) add("вкладка " + widget.page)
        if (tokenize(text).any { it == widget.id.lowercase() }) add("ID " + widget.id); if (widget.unit.isNotBlank()) add("единица " + widget.unit) }
    private fun clarificationForSensors(candidates: List<SensorCandidate>): String {
        val names = candidates.take(4).map { it.widget.title.ifBlank { it.widget.id } + if (it.widget.page.isNotBlank()) " (вкладка " + it.widget.page + ")" else "" }.distinct()
        return if (names.isEmpty()) "Уточните, какой датчик нужен." else "Уточните, какой датчик использовать: " + names.joinToString(" или ") }
    private fun tokenize(text: String): List<String> = text.replace(",", " ").replace("?", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    private fun containsAny(text: String, vararg words: String): Boolean = words.any { text.contains(it) }
}