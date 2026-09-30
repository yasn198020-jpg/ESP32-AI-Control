package com.yasn198020.aicontrol
import android.content.Context
import android.net.Uri
import com.yasn198020.aicontrol.core.Device

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

        // User-trained phrases remain an exact-priority path in the callers.
        // Returning NOT_FOUND here intentionally lets the existing trained-action
        // block execute without letting Gemma reinterpret that phrase.
        if (trainedMatcher.matchAll(text).isNotEmpty()) {
            return LocalCommandResult(
                LocalCommandAction.NOT_FOUND,
                reply = ""
            )
        }

        if (gemma.isModelInstalled()) {
            gemma.interpret(text, devices).getOrNull()?.let { return it }
        }

        return LocalCommandManager().interpret(text, devices)
    }

    private fun looksLikeSmartRule(text: String): Boolean {
        val n = text.lowercase().replace('ё', 'е')
        val trigger = listOf("если ", "когда ", "при температур", "как только", "при условии")
            .any { n.contains(it) }
        return trigger && (
            n.contains("температур") ||
            n.contains("градус") ||
            n.contains("жарко") ||
            n.contains("холодно")
        )
    }
}
