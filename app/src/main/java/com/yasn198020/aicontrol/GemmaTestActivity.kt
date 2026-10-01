package com.yasn198020.aicontrol

import android.app.Activity
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
    private var testSequence = 0
    private val timeoutHandlers = mutableMapOf<Int, Runnable>()

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

        addTestButton(root, "🔬 Prompt benchmark: batch 1–35") { runPromptBenchmark() }
        addTestButton(root, "🚪 IoT: Открой дверь помидоров") {
            runIoTTest("Открой дверь помидоров")
        }

        root.addView(Button(this).apply {
            text = "Очистить результат"
            setOnClickListener { output.text = "Результат очищен." }
        })

        root.addView(Button(this).apply {
            text = "Копировать результат"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Gemma diagnostic", output.text)
                )
                append("✓ Результат скопирован")
            }
        })

        output = TextView(this).apply {
            textSize = 15f
            setPadding(12, 16, 12, 24)
            text = "Нажмите тест выше. Результат появится здесь."
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

    private fun addTestButton(root: LinearLayout, title: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = title
            setOnClickListener { action() }
        })
    }

    private fun runPromptBenchmark() {
        val prompt = "Скажи одним предложением: привет."
        val started = System.currentTimeMillis()
        val testId = ++testSequence

        append(
            "\n\n🔬 PROMPT BENCHMARK\n" +
                "Потоки: 4, context: 768\n" +
                "Batch: 1 / 2 / 4 / 8 / 16 / 32 / 35\n" +
                "Запуск…"
        )

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val elapsed = System.currentTimeMillis() - started

                if (resultCode == 2) {
                    append(
                        "[" + (resultData?.getLong("elapsed") ?: elapsed) +
                            " мс] " + resultData?.getString("stage").orEmpty()
                    )
                    return
                }

                timeoutHandlers.remove(testId)?.let {
                    Handler(Looper.getMainLooper()).removeCallbacks(it)
                }

                val error = resultData?.getString("error")
                val text = resultData?.getString("text").orEmpty()

                append(
                    if (resultCode == 0)
                        "Benchmark за " + elapsed + " мс:\n" + text
                    else
                        "ОШИБКА benchmark за " + elapsed + " мс:\n" +
                            (error ?: "неизвестная ошибка")
                )
            }
        }

        try {
            startService(
                Intent(this, GemmaInferenceService::class.java)
                    .putExtra(GemmaInferenceService.EXTRA_RAW_PROMPT, prompt)
                    .putExtra(GemmaInferenceService.EXTRA_MAX_TOKENS, 1)
                    .putExtra(GemmaInferenceService.EXTRA_THREADS, 4)
                    .putExtra(GemmaInferenceService.EXTRA_CONTEXT, 768)
                    .putExtra(GemmaInferenceService.EXTRA_PROMPT_BENCHMARK, true)
                    .putExtra(GemmaInferenceService.EXTRA_RESULT, receiver)
            )
        } catch (t: Throwable) {
            append("Не удалось запустить benchmark: " + (t.message ?: t.javaClass.simpleName))
            return
        }

        val timeout = Runnable {
            append("ТАЙМАУТ: benchmark не завершился за 180 секунд.")
        }
        timeoutHandlers[testId] = timeout
        Handler(Looper.getMainLooper()).postDelayed(timeout, 180_000L)
    }

    private fun runIoTTest(command: String) {
        val started = System.currentTimeMillis()
        append("\n\n▶ " + command + "\nАнализ IoTManager…")

        scope.launch {
            val result = GemmaLocalEngine.get(applicationContext)
                .interpret(
                    command,
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

            append("Результат за " + elapsed + " мс:\n" + text)
        }
    }

    private fun append(value: String) {
        output.append("\n" + value)
        output.post { (output.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        timeoutHandlers.values.forEach {
            Handler(Looper.getMainLooper()).removeCallbacks(it)
        }
        timeoutHandlers.clear()
        scope.cancel()
        super.onDestroy()
    }
}
