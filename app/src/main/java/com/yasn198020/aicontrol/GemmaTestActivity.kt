package com.yasn198020.aicontrol

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GemmaTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var output: TextView
    private var running = false
    private var timeoutHandler: Handler? = null
    private var timeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        root.addView(TextView(this).apply {
            text = "Марфа → Gemma: диагностика"
            textSize = 22f
            setPadding(8, 8, 8, 16)
        })

        root.addView(Button(this).apply {
            text = "🚀 Запустить ВСЕ тесты"
            setOnClickListener { runAllTests() }
        })

        root.addView(Button(this).apply {
            text = "Очистить результат"
            setOnClickListener {
                output.text = "Результат очищен."
            }
        })

        root.addView(Button(this).apply {
            text = "Копировать результат"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Gemma diagnostic", output.text)
                )
                append("✓ Результат скопирован")
            }
        })

        output = TextView(this).apply {
            textSize = 15f
            setPadding(12, 16, 12, 24)
            text = "Нажмите «Запустить ВСЕ тесты»."
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

    private fun runAllTests() {
        if (running) {
            append("⚠ Тесты уже выполняются.")
            return
        }

        running = true
        append(
            "\n\n🚀 ПОЛНЫЙ ЗАПУСК ТЕСТОВ\n" +
                "Потоки: 1 / 2 / 4 / 6\n" +
                "Context: 128 / 256 / 512 / 768\n" +
                "Tokens: 8 / 16 / 32 / 96\n" +
                "Prompt batch: 1 / 2 / 4 / 8 / 16 / 32 / 35\n" +
                "Raw language: 2 теста\n" +
                "IoT: Открой дверь помидоров\n" +
                "Запуск…"
        )

        val started = System.currentTimeMillis()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == 2) {
                    append(
                        "[" + (resultData?.getLong("elapsed") ?: 0L) +
                            " мс] " + resultData?.getString("stage").orEmpty()
                    )
                    return
                }

                timeoutRunnable?.let { timeoutHandler?.removeCallbacks(it) }
                timeoutRunnable = null
                timeoutHandler = null

                if (resultCode != 0) {
                    append(
                        "❌ ПОЛНАЯ ДИАГНОСТИКА ОШИБКА за " +
                            (System.currentTimeMillis() - started) +
                            " мс:\n" +
                            (resultData?.getString("error") ?: "неизвестная ошибка")
                    )
                    running = false
                    return
                }

                append(
                    "\n✅ ВСЕ ДИАГНОСТИЧЕСКИЕ ТЕСТЫ ЗАВЕРШЕНЫ за " +
                        (System.currentTimeMillis() - started) +
                        " мс\n" +
                        resultData?.getString("text").orEmpty()
                )

                runIoTTestAfterDiagnostics()
            }
        }

        try {
            startService(
                Intent(this, GemmaInferenceService::class.java)
                    .putExtra(
                        GemmaInferenceService.EXTRA_RAW_PROMPT,
                        "Скажи одним словом: тест."
                    )
                    .putExtra(GemmaInferenceService.EXTRA_MAX_TOKENS, 96)
                    .putExtra(GemmaInferenceService.EXTRA_THREADS, 4)
                    .putExtra(GemmaInferenceService.EXTRA_CONTEXT, 768)
                    .putExtra(GemmaInferenceService.EXTRA_FULL_DIAGNOSTICS, true)
                    .putExtra(GemmaInferenceService.EXTRA_RESULT, receiver)
            )
        } catch (t: Throwable) {
            append("❌ Не удалось запустить диагностику: " + (t.message ?: t.javaClass.simpleName))
            running = false
            return
        }

        val timeout = Runnable {
            append("⏱ ТАЙМАУТ: полный набор тестов не завершился за 10 минут.")
            running = false
        }
        timeoutRunnable = timeout
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutHandler?.postDelayed(timeout, 600_000L)
    }

    private fun runIoTTestAfterDiagnostics() {
        append("\n🚪 IoT ТЕСТ: Открой дверь помидоров\nАнализ реального каталога IoTManager…")
        scope.launch {
            val started = System.currentTimeMillis()
            val result = GemmaLocalEngine.get(applicationContext)
                .interpret(
                    "Открой дверь помидоров",
                    AppRuntime.get(applicationContext).deviceRepository.snapshot()
                )

            val elapsed = System.currentTimeMillis() - started
            val text = result.fold(
                onSuccess = {
                    "action=" + it.action +
                        "\nwidgetId=" + it.widgetId +
                        "\nvalue=" + it.value +
                        "\ndelayMs=" + it.delayMs +
                        "\nreply=" + it.reply
                },
                onFailure = {
                    "ОШИБКА: " + (it.message ?: it.javaClass.simpleName)
                }
            )

            append("IoT результат за " + elapsed + " мс:\n" + text)
            running = false
        }
    }

    private fun append(value: String) {
        output.append("\n" + value)
        output.post {
            (output.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        timeoutRunnable?.let { timeoutHandler?.removeCallbacks(it) }
        timeoutRunnable = null
        timeoutHandler = null
        scope.cancel()
        super.onDestroy()
    }
}
