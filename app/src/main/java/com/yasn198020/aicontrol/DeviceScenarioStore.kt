package com.yasn198020.aicontrol

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StoredDeviceScenario(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val title: String,
    val source: String,
    val enabled: Boolean = true
)

data class DeviceScenarioDeviation(
    val scenarioId: String,
    val scenarioTitle: String,
    val deviceId: String,
    val ruleIndex: Int,
    val widgetId: String,
    val expected: String,
    val actual: String,
    val condition: String,
    val since: Long
) {
    val message: String
        get() = "По сценарию «" + scenarioTitle + "» при условии " + condition +
            " элемент " + widgetId + " должен иметь значение " + expected + ", но сейчас " + actual + "."
}

class DeviceScenarioStore(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY = "marfa_device_scenarios_v1"
    }

    @Synchronized
    fun all(): List<StoredDeviceScenario> {
        val raw = prefs.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val deviceId = o.optString("deviceId").trim()
                    val source = o.optString("source")
                    if (deviceId.isBlank() || source.isBlank()) continue
                    add(
                        StoredDeviceScenario(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            deviceId = deviceId,
                            title = o.optString("title").ifBlank { "Сценарий устройства" },
                            source = source,
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun save(item: StoredDeviceScenario) {
        val updated = all().filterNot { it.id == item.id } + item
        write(updated)
    }

    @Synchronized
    fun delete(id: String) {
        write(all().filterNot { it.id == id })
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        all().firstOrNull { it.id == id }?.let { save(it.copy(enabled = enabled)) }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private fun write(items: List<StoredDeviceScenario>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("deviceId", item.deviceId)
                put("title", item.title)
                put("source", item.source)
                put("enabled", item.enabled)
            })
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }
}

class DeviceScenarioManager(
    private val appContext: Context,
    private val store: DeviceScenarioStore
) {
    private val parsed = ConcurrentHashMap<String, DeviceScenarioModel>()
    private val sourceCache = ConcurrentHashMap<String, String>()
    private val activeDeviations = ConcurrentHashMap<String, PendingDeviation>()
    private val notifiedAt = ConcurrentHashMap<String, Long>()

    private data class PendingDeviation(
        val deviation: DeviceScenarioDeviation,
        val firstSeenAt: Long
    )

    companion object {
        private const val DEVIATION_GRACE_MS = 4_000L
        private const val NOTIFY_REPEAT_MS = 10 * 60_000L
    }

    init {
        refreshModels()
    }

    @Synchronized
    fun refreshModels() {
        val items = store.all()
        items.forEach { refreshModel(it) }
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        parsed.keys.removeAll { it !in validIds }
        sourceCache.keys.removeAll { it !in validIds }
    }

    fun saveScenario(
        deviceId: String,
        title: String,
        source: String,
        enabled: Boolean = true
    ): Pair<StoredDeviceScenario?, DeviceScenarioModel> {
        val model = parseSource(source)
        if (!model.valid) return null to model

        val item = StoredDeviceScenario(
            deviceId = deviceId,
            title = title.ifBlank { "Сценарий устройства" },
            source = source.trim(),
            enabled = enabled
        )
        store.save(item)
        parsed[item.id] = model
        sourceCache[item.id] = item.source
        activeDeviations.keys.removeAll { it.startsWith(item.id + "/") }
        return item to model
    }

    fun parseSource(source: String): DeviceScenarioModel =
        IoTScenarioSemanticAnalyzer.analyze(IoTScenarioParser.parse(source))

    fun scenarios(): List<StoredDeviceScenario> = store.all()

    fun remove(id: String) {
        store.delete(id)
        parsed.remove(id)
        sourceCache.remove(id)
        activeDeviations.keys.removeAll { it.startsWith(id + "/") }
        notifiedAt.keys.removeAll { it.startsWith(id + "/") }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
        if (!enabled) {
            activeDeviations.keys.removeAll { it.startsWith(id + "/") }
            notifiedAt.keys.removeAll { it.startsWith(id + "/") }
        }
    }

    fun deviationSnapshot(): List<DeviceScenarioDeviation> =
        activeDeviations.values.map { it.deviation }.sortedByDescending { it.since }

    fun onDevicesUpdated(devices: List<com.yasn198020.aicontrol.core.Device>) {
        refreshModels()

        val variables = devices
            .flatMap { it.widgets }
            .associate { it.id to it.value }

        val widgetMap = devices
            .flatMap { device -> device.widgets.map { widget -> (device.id + "/" + widget.id) to widget } }
            .toMap()

        val now = System.currentTimeMillis()
        val seen = mutableSetOf<String>()

        store.all().filter { it.enabled }.forEach { stored ->
            val model = refreshModel(stored) ?: return@forEach
            if (model.parserErrors.isNotEmpty()) return@forEach

            model.rules.forEach { rule ->
                val context = IoTScenarioEvaluationContext(variables)
                val condition = IoTScenarioEvaluator.evaluate(rule.condition.expression, context)
                if ((condition as? IoTValue.BooleanValue)?.value != true) return@forEach

                rule.actions.forEach { action ->
                    val target = widgetMap.values.firstOrNull { it.id == action.targetId } ?: return@forEach
                    val expected = IoTScenarioEvaluator.evaluate(action.expression, context)
                    val expectedText = expected.asComparableText() ?: return@forEach
                    val actualText = target.value.trim()
                    if (valuesEquivalent(actualText, expectedText)) return@forEach

                    val key = stored.id + "/" + rule.index + "/" + target.id + "/" + expectedText
                    seen += key

                    val old = activeDeviations[key]
                    val deviation = DeviceScenarioDeviation(
                        scenarioId = stored.id,
                        scenarioTitle = stored.title,
                        deviceId = stored.deviceId,
                        ruleIndex = rule.index,
                        widgetId = target.id,
                        expected = expectedText,
                        actual = actualText.ifBlank { "неизвестно" },
                        condition = rule.condition.rendered,
                        since = old?.deviation?.since ?: now
                    )
                    activeDeviations[key] = PendingDeviation(
                        deviation = deviation,
                        firstSeenAt = old?.firstSeenAt ?: now
                    )

                    val pending = activeDeviations[key] ?: return@forEach
                    if (now - pending.firstSeenAt >= DEVIATION_GRACE_MS) {
                        val last = notifiedAt[key] ?: 0L
                        if (now - last >= NOTIFY_REPEAT_MS) {
                            notifiedAt[key] = now
                            val synthetic = Scenario(
                                id = "device-" + key.hashCode().toString(),
                                title = "Марфа: отклонение от сценария",
                                deviceId = stored.deviceId,
                                widgetId = target.id,
                                threshold = expectedText.replace(',', '.').toDoubleOrNull() ?: 0.0,
                                message = deviation.message
                            )
                            runCatching {
                                ScenarioNotifier.notify(appContext, synthetic, actualText.ifBlank { "неизвестно" })
                            }
                            DiagnosticTrace.system("MARFA_SCENARIO deviation: " + deviation.message)
                        }
                    }
                }
            }
        }

        activeDeviations.keys.toList().filter { it !in seen }.forEach {
            activeDeviations.remove(it)
            notifiedAt.remove(it)
        }
    }

    fun planCommand(
        result: LocalCommandResult,
        devices: List<com.yasn198020.aicontrol.core.Device>
    ): ScenarioCommandPlan {
        refreshModels()
        return IoTScenarioCommandPlanner.plan(
            targetDeviceId = result.deviceId,
            targetWidgetId = result.widgetId,
            desiredValue = result.value,
            baseActions = result.actionItems,
            devices = devices,
            models = store.all()
                .filter { it.enabled }
                .mapNotNull { item -> refreshModel(item)?.let { item.deviceId to it } }
        )
    }

    private fun refreshModel(item: StoredDeviceScenario): DeviceScenarioModel? {
        val oldSource = sourceCache[item.id]
        if (oldSource == item.source) return parsed[item.id]
        val model = parseSource(item.source)
        parsed[item.id] = model
        sourceCache[item.id] = item.source
        return model
    }

    private fun valuesEquivalent(actual: String, expected: String): Boolean {
        val a = actual.replace(',', '.').toDoubleOrNull()
        val e = expected.replace(',', '.').toDoubleOrNull()
        return if (a != null && e != null) kotlin.math.abs(a - e) < 0.000001 else actual == expected
    }

    private fun IoTValue.asComparableText(): String? = when (this) {
        is IoTValue.Number -> java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        is IoTValue.Text -> value
        is IoTValue.BooleanValue -> if (value) "1" else "0"
        IoTValue.Unknown -> null
    }
}
