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
        output = TextView(this).apply {
            textSize = 16f
            setPadding(24, 16, 24, 24)
            text = "Тест Gemma\n\nНажмите тест. Здесь измеряется время до полного ответа."
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        root.addView(TextView(this).apply {
            text = "Марфа → Gemma: диагностика"
            textSize = 22f
            setPadding(8, 8, 8, 16)
        })

        addTestButton(root, "Потоки: 1") { runRawTest("Скажи одним предложением: привет.", 8, 1) }
        addTestButton(root, "Потоки: 2") { runRawTest("Скажи одним предложением: привет.", 8, 2) }
        addTestButton(root, "Потоки: 4") { runRawTest("Скажи одним предложением: привет.", 8, 4) }
        addTestButton(root, "Потоки: 6") { runRawTest("Скажи одним предложением: привет.", 8, 6) }
        addTestButton(root, "Тест 16 токенов") { runRawTest("Скажи одним предложением: привет.", 16) }
        addTestButton(root, "Тест 32 токена") { runRawTest("Расскажи короткий смешной анекдот.", 32) }
        addTestButton(root, "Тест 96 токенов") { runRawTest("Расскажи короткий смешной анекдот.", 96) }
        addTestButton(root, "1. Расскажи анекдот") { runRawTest("Расскажи короткий смешной анекдот.", 16) }
        addTestButton(root, "2. Кто такой Пушкин?") { runRawTest("Кто такой Александр Сергеевич Пушкин? Ответь кратко.") }
        addTestButton(root, "3. Открой дверь помидоров") { runIoTTest("Открой дверь помидоров") }
        addTestButton(root, "Запустить все три") { runAll() }

        root.addView(Button(this).apply {
            text = "Копировать весь результат"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gemma diagnostic", output.text))
                append("✓ Результат скопирован в буфер обмена")
            }
        })
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addTestButton(root: LinearLayout, title: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = title; setOnClickListener { action() } })
    }

    private fun runRawTest(prompt: String, maxTokens: Int = 96, threads: Int = 0) {
        val started = System.currentTimeMillis()
        val testId = ++testSequence
        append("\n\n▶ " + prompt + "\nПотоки: " + (if (threads > 0) threads else "auto") + ", токены: " + maxTokens + "\nЗапуск Gemma…")
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val elapsed = System.currentTimeMillis() - started
                if (resultCode == 2) {
                    append("[" + (resultData?.getLong("elapsed") ?: elapsed) + " мс] " + resultData?.getString("stage").orEmpty())
                    return
                }
                timeoutHandlers.remove(testId)?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                val error = resultData?.getString("error")
                val text = resultData?.getString("text").orEmpty()
                append(if (resultCode == 0)
                    "Ответ за " + elapsed + " мс, скорость: " + resultData?.getString("tokens_per_second").orEmpty() + " ток/с:\n" + text
                else
                    "ОШИБКА за " + elapsed + " мс:\n" + (error ?: "неизвестная ошибка"))
            }
        }
        try {
            startService(Intent(this, GemmaInferenceService::class.java)
                .putExtra(GemmaInferenceService.EXTRA_RAW_PROMPT, prompt)
                .putExtra(GemmaInferenceService.EXTRA_MAX_TOKENS, maxTokens)
                .putExtra(GemmaInferenceService.EXTRA_THREADS, threads)
                .putExtra(GemmaInferenceService.EXTRA_RESULT, receiver))
        } catch (t: Throwable) {
            append("Не удалось запустить Gemma: " + (t.message ?: t.javaClass.simpleName))
            return
        }
        val timeout = Runnable { append("ТАЙМАУТ: Gemma не вернула ответ за 45 секунд.") }
        timeoutHandlers[testId] = timeout
        Handler(Looper.getMainLooper()).postDelayed(timeout, 45_000L)
    }

    private fun runIoTTest(command: String) {
        val started = System.currentTimeMillis()
        append("\n\n▶ " + command + "\nАнализ IoTManager…")
        scope.launch {
            val result = GemmaLocalEngine.get(applicationContext)
                .interpret(command, AppRuntime.get(applicationContext).deviceRepository.snapshot())
            val elapsed = System.currentTimeMillis() - started
            val text = result.fold(
                onSuccess = {
                    "action=" + it.action + "\nwidgetId=" + it.widgetId + "\nvalue=" + it.value +
                        "\ndelayMs=" + it.delayMs + "\nreply=" + it.reply
                },
                onFailure = { "ОШИБКА: " + (it.message ?: it.javaClass.simpleName) }
            )
            append("Результат за " + elapsed + " мс:\n" + text)
        }
    }

    private fun runAll() {
        append("=== ЗАПУСК ВСЕХ ТРЁХ ТЕСТОВ ===")
        runRawTest("Расскажи короткий смешной анекдот.")
        Handler(Looper.getMainLooper()).postDelayed({ runRawTest("Кто такой Александр Сергеевич Пушкин? Ответь кратко.") }, 1500L)
        Handler(Looper.getMainLooper()).postDelayed({ runIoTTest("Открой дверь помидоров") }, 3000L)
    }

    private fun append(value: String) { output.append("\n" + value) }

    override fun onDestroy() {
        timeoutHandlers.values.forEach { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        timeoutHandlers.clear()
        scope.cancel()
        super.onDestroy()
    }
}
