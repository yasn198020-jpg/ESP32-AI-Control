package com.yasn198020.aicontrol

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.graphics.Color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MarfaTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var output: TextView
    private lateinit var commandInput: EditText
    private lateinit var followUpInput: EditText
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(18, 18, 18)
        window.navigationBarColor = Color.rgb(18, 18, 18)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(16, 16, 16, 16)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                16 + bars.left,
                16 + bars.top,
                16 + bars.right,
                16 + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        root.addView(TextView(this).apply {
            text = "Марфа → проверка"
            textSize = 22f
            setPadding(8, 8, 8, 16)
        })

        commandInput = EditText(this).apply {
            setSingleLine(true)
            setText("Выключи автомат управления")
            hint = "Команда Марфе"
            setPadding(12, 8, 12, 8)
        }
        root.addView(commandInput)

        root.addView(Button(this).apply {
            text = "➕ Добавить продолжение диалога"
            setOnClickListener {
                followUpInput.visibility =
                    if (followUpInput.visibility == android.view.View.VISIBLE) {
                        android.view.View.GONE
                    } else {
                        android.view.View.VISIBLE
                    }
            }
        })

        followUpInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            hint = "Ответ пользователя после уточнения (например: огурцы)"
            setPadding(12, 8, 12, 8)
            visibility = android.view.View.GONE
        }
        root.addView(followUpInput)

        root.addView(Button(this).apply {
            text = "🔬 Проверить Марфу"
            setOnClickListener {
                runTest(commandInput.text.toString(), followUpInput.text.toString())
            }
        })

        root.addView(Button(this).apply {
            text = "Очистить результат"
            setOnClickListener { output.text = "Результат очищен." }
        })

        root.addView(Button(this).apply {
            text = "Копировать результат"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Marfa diagnostic", output.text)
                )
                append("✓ Результат скопирован")
            }
        })

        output = TextView(this).apply {
            textSize = 15f
            setPadding(12, 16, 12, 24)
            text = "Введите команду и нажмите «Проверить Марфу»."
        }

        root.addView(ScrollView(this).apply {
            addView(output)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
    }

    private fun runTest(command: String, followUp: String) {
        if (running) {
            append("⚠ Проверка уже выполняется.")
            return
        }

        val text = command.trim()
        if (text.isBlank()) {
            append("⚠ Введите команду для проверки.")
            return
        }

        running = true
        output.text = ""
        append("🔬 ЗАПУСК ПРОВЕРКИ MARFA")
        append("Команда: " + text)
        if (followUp.trim().isNotBlank()) {
            append("Продолжение диалога: " + followUp.trim())
        }
        append("MQTT/устройство не будет затронуто.")

        scope.launch {
            val started = System.currentTimeMillis()
            val devices = AppRuntime.get(applicationContext).deviceRepository.snapshot()
            val intelligence = MarfaIntelligence.get(applicationContext)
            val dialogueCore = MarfaDialogueCore { commandText, snapshot ->
                intelligence.interpret(commandText, snapshot)
            }

            try {
                append("=== ОБЩИЙ ДИАЛОГОВЫЙ КОНТУР ===")
                val firstOutcome = dialogueCore.process(text, devices)
                appendDialogueOutcome("1-й ход", firstOutcome)
                append("[" + (System.currentTimeMillis() - started) + " мс]")

                val followUpText = followUp.trim()
                if (followUpText.isNotBlank()) {
                    append("")
                    append("=== ПРОДОЛЖЕНИЕ ТОГО ЖЕ ДИАЛОГА ===")
                    append("Пользователь: " + followUpText)
                    val followStarted = System.currentTimeMillis()
                    val followOutcome = dialogueCore.process(followUpText, devices)
                    appendDialogueOutcome("2-й ход", followOutcome)
                    append("[" + (System.currentTimeMillis() - followStarted) + " мс]")
                }

                append("=== ПРОВЕРКА MARFA ЗАВЕРШЕНА ===")
                append("Оба хода прошли через один MarfaDialogueCore; MQTT и действие устройства не выполнялись.")
            } catch (error: Throwable) {
                append("❌ ОШИБКА ПРОВЕРКИ: " + (error.message ?: error.javaClass.simpleName))
            } finally {
                running = false
            }
        }
    }

    private fun appendDialogueOutcome(label: String, outcome: MarfaDialogueCore.Outcome) {
        val result = outcome.result
        append("--- " + label + " ---")
        append("outcome=" + outcome.kind)
        append("action=" + (result?.action ?: "нет"))
        if (result != null) {
            append("deviceId=" + result.deviceId)
            append("widgetId=" + result.widgetId)
            append("value=" + result.value)
            append("delayMs=" + result.delayMs)
            append("reply=" + result.reply)
            append("needsConfirmation=" + result.needsConfirmation)
            append("actions=" + result.actionItems.joinToString(", ") { it.widgetId + "=" + it.value }.ifBlank { "нет" })
            result.scenarioPlan?.let { plan ->
                append("SCENARIO: " + plan.actions.joinToString(", ") { it.widgetId + "=" + it.value }.ifBlank { "нет" })
                append("ПРЕДУСЛОВИЯ: " + plan.prerequisites.joinToString(", ") { it.widgetId + "=" + it.value }.ifBlank { "нет" })
                append("БЛОК: " + (plan.blockedReason ?: "нет"))
                append("resolvedByScenario=" + plan.resolvedByScenario)
            }
        }
        append("ответ=" + outcome.reply)
    }

    private fun normalizeForCompare(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun append(value: String) {
        output.append("\n" + value)
        output.post {
            (output.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
