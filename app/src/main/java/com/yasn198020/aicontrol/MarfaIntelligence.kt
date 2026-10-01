package com.yasn198020.aicontrol

import android.content.Context
import android.net.Uri
import com.yasn198020.aicontrol.core.Device
import java.util.Locale

class MarfaIntelligence private constructor(context: Context) {
    companion object {
        @Volatile private var instance: MarfaIntelligence? = null
        fun get(context: Context): MarfaIntelligence =
            instance ?: synchronized(this) {
                instance ?: MarfaIntelligence(context.applicationContext).also { instance = it }
            }
    }

    private val gemma = GemmaLocalEngine.get(context)
    private val smartRuleParser = LocalCommandManager()
    private val trainedMatcher = TrainedCommandMatcher(
        TrainedCommandStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    )

    fun gemmaStatus(): String = gemma.statusText()
    suspend fun importGemmaModel(uri: Uri): Result<String> = gemma.importModel(uri)

    suspend fun interpret(command: String, devices: List<Device>): LocalCommandResult {
        val text = command.trim()
        if (text.isBlank()) return LocalCommandResult(
            LocalCommandAction.NOT_FOUND, reply = "Я не услышала команду"
        )

        if (looksLikeSmartRule(text)) return smartRuleParser.interpret(text, devices)

        // Saved user-trained phrases remain outside the semantic LLM path.
        if (trainedMatcher.matchAll(text).isNotEmpty()) {
            return LocalCommandResult(LocalCommandAction.NOT_FOUND, reply = "")
        }

        // FAST PATH: ordinary commands are handled immediately by the existing
        // deterministic engine. Gemma is not invoked for every sentence.
        val fast = smartRuleParser.interpret(text, devices)

        if (!gemma.isModelInstalled()) return fast
        if (!shouldUseGemma(text, fast, devices)) return fast

        return gemma.interpret(text, devices).getOrElse {
            // Gemma is optional. Any model/JNI/runtime error falls back to the
            // already working local command engine.
            fast
        }
    }

    /** Lets the voice UI acknowledge a deep semantic lookup before Gemma starts. */
    fun isLikelyContextual(command: String): Boolean {
        val n = normalize(command)
        if (n.isBlank()) return false
        val padded = " $n "
        return listOf(
            " там ", " здесь ", " где ", " у ", " около ", " возле ",
            " рядом ", " для ", " внутри ", " снаружи ", " в ", " на "
        ).any { padded.contains(it) } || n.split(" ").size >= 5
    }

    private fun shouldUseGemma(
        text: String,
        fast: LocalCommandResult,
        devices: List<Device>
    ): Boolean {
        if (fast.action == LocalCommandAction.CLARIFY ||
            fast.action == LocalCommandAction.NOT_FOUND) return true

        if (fast.action != LocalCommandAction.CONTROL &&
            fast.action != LocalCommandAction.READ_VALUE) return false

        val commandTokens = semanticTokens(text)
        if (commandTokens.isEmpty()) return false

        val explained = mutableSetOf<String>()
        explained.addAll(semanticTokens(fast.widgetId))

        devices.firstOrNull { it.id == fast.deviceId }?.let { device ->
            explained.addAll(semanticTokens(device.name))
            device.widgets.firstOrNull { it.id == fast.widgetId }?.let { widget ->
                explained.addAll(semanticTokens(widget.title))
                explained.addAll(semanticTokens(widget.page))
                explained.addAll(semanticTokens(widget.definitionName))
            }
        }

        // Unknown meaningful words are context clues. This is intentionally
        // universal: no finite list such as "помидор/огурец/..." is required.
        return commandTokens.any { it !in explained }
    }

    private fun semanticTokens(value: String): Set<String> {
        val stop = setOf(
            "открой", "открыть", "открывай", "закрой", "закрыть", "закрывай",
            "включи", "включить", "выключи", "выключить", "запусти", "запустить",
            "останови", "остановить", "покажи", "скажи", "узнай", "какая", "какое",
            "какие", "сколько", "температура", "температур", "градус", "градуса",
            "через", "спустя", "пожалуйста", "марфа", "там", "здесь", "у", "в",
            "на", "для", "где", "около", "возле", "рядом", "и", "а", "то", "же"
        )
        return normalize(value)
            .split(" ")
            .map { stem(it) }
            .filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }
            .toSet()
    }

    private fun stem(word: String): String {
        val endings = listOf(
            "иями", "ами", "ого", "ему", "ому", "ыми", "ими", "ая", "яя",
            "ое", "ее", "ые", "ие", "ать", "ить", "еть", "ять", "ой", "ый",
            "ий", "ов", "ев", "ам", "ям", "ах", "ях", "ы", "и", "а", "я", "о", "е"
        )
        for (ending in endings) {
            if (word.length > ending.length + 2 && word.endsWith(ending)) {
                return word.removeSuffix(ending)
            }
        }
        return word
    }

    private fun looksLikeSmartRule(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT).replace('ё', 'е')
        val trigger = listOf("если ", "когда ", "при температур", "как только", "при условии")
            .any { n.contains(it) }
        return trigger && (
            n.contains("температур") ||
            n.contains("градус") ||
            n.contains("жарко") ||
            n.contains("холодно")
        )
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9:,.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("\\b(?:пожалуйста|прошу|марф|марфа|марфу|марфе|марфой)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
