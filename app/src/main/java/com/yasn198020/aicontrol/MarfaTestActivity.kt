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
    private lateinit var expectedInput: EditText
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
            text = "➕ Добавить ответ"
            setOnClickListener {
                expectedInput.visibility =
                    if (expectedInput.visibility == android.view.View.VISIBLE) {
                        android.view.View.GONE
                    } else {
                        android.view.View.VISIBLE
                    }
            }
        })

        expectedInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            hint = "Ожидаемый ответ Марфы (необязательно)"
            setPadding(12, 8, 12, 8)
            visibility = android.view.View.GONE
        }
        root.addView(expectedInput)

        root.addView(Button(this).apply {
            text = "🔬 Проверить Марфу"
            setOnClickListener {
                runTest(commandInput.text.toString(), expectedInput.text.toString())
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

    private fun runTest(command: String, expected: String) {
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
        if (expected.trim().isNotBlank()) {
            append("Ожидаемый ответ: " + expected.trim())
        }
        append("MQTT/устройство не будет затронуто.")

        scope.launch {
            val started = System.currentTimeMillis()
            val devices = AppRuntime.get(applicationContext).deviceRepository.snapshot()
            val result = MarfaIntelligence.get(applicationContext).diagnoseCommand(text, devices)
            val elapsed = System.currentTimeMillis() - started

            val report = result.fold(
                onSuccess = { it },
                onFailure = {
                    "❌ ОШИБКА ПРОВЕРКИ: " + (it.message ?: it.javaClass.simpleName)
                }
            )

            append("[" + elapsed + " мс]")
            append(report)

            val actual = result.getOrNull()
                ?.substringAfter("reply=", "")
                ?.substringBefore("\n")
                ?.trim()
                .orEmpty()

            val expectedText = expected.trim()
            if (expectedText.isNotBlank()) {
                val normalizedActual = normalizeForCompare(actual)
                val normalizedExpected = normalizeForCompare(expectedText)
                val matched = normalizedActual.contains(normalizedExpected) ||
                    normalizedExpected.contains(normalizedActual)

                if (matched) {
                    append("✅ ОТВЕТ СОВПАЛ")
                } else {
                    append(
                        "❌ ОТВЕТ НЕ СОВПАЛ\n" +
                            "Ожидалось: " + expectedText +
                            "\nПолучено: " + actual.ifBlank { "не удалось выделить reply" }
                    )
                }
            }

            append("=== ПРОВЕРКА MARFA ЗАВЕРШЕНА ===")
            running = false
        }
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
