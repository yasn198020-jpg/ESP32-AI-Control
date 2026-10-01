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

        addTestButton(root, "1. Расскажи анекдот") { runRawTest("Расскажи короткий смешной анекдот.") }
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

    private fun runRawTest(prompt: String) {
        val started = System.currentTimeMillis()
        append("\n\n▶ " + prompt + "\nЗапуск Gemma…")
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val elapsed = System.currentTimeMillis() - started
                if (resultCode == 2) {
                    append("[" + (resultData?.getLong("elapsed") ?: elapsed) + " мс] " + resultData?.getString("stage").orEmpty())
                    return
                }
                val error = resultData?.getString("error")
                val text = resultData?.getString("text").orEmpty()
                append(if (resultCode == 0)
                    "Ответ за " + elapsed + " мс:\n" + text
                else
                    "ОШИБКА за " + elapsed + " мс:\n" + (error ?: "неизвестная ошибка"))
            }
        }
        try {
            startService(Intent(this, GemmaInferenceService::class.java)
                .putExtra(GemmaInferenceService.EXTRA_RAW_PROMPT, prompt)
                .putExtra(GemmaInferenceService.EXTRA_RESULT, receiver))
        } catch (t: Throwable) {
            append("Не удалось запустить Gemma: " + (t.message ?: t.javaClass.simpleName))
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            append("ТАЙМАУТ: Gemma не вернула ответ за 45 секунд. Если выше нет этапа «Сервис получил запрос», вероятно, процесс :gemma завершился до отправки результата.")
        }, 45_000L)
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
        scope.cancel()
        super.onDestroy()
    }
}
