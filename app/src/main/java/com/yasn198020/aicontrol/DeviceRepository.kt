package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.json.JSONObject

/**
 * Single process-wide source of truth for MQTT-derived device/widget state.
 *
 * MQTT, the dashboard and Marfa all see the same device snapshot.
 * Values that arrive before CONFIG are kept until the corresponding CONFIG arrives.
 */
class DeviceRepository {
    private val lock = Any()
    private var devices: List<Device> = emptyList()
    private val pendingValues = mutableMapOf<String, String>()

    fun snapshot(): List<Device> = synchronized(lock) { devices }

    fun clear() {
        synchronized(lock) {
            devices = emptyList()
            pendingValues.clear()
        }
    }

    fun setLocalValue(deviceId: String, widgetId: String, value: String) {
        onStatus(deviceId, widgetId, value)
    }

    fun onStatus(deviceId: String, widgetId: String, value: String) {
        synchronized(lock) {
            val key = "$deviceId/$widgetId"
            val device = devices.firstOrNull { it.id == deviceId }
            val widget = device?.widgets?.firstOrNull { it.id == widgetId }

            if (widget == null) {
                pendingValues[key] = value
            }

            if (device == null) {
                devices = devices + Device(
                    id = deviceId,
                    name = deviceId,
                    online = true,
                    widgets = emptyList()
                )
                return
            }

            if (widget == null) {
                devices = devices.map {
                    if (it.id == deviceId) it.copy(online = true) else it
                }
            } else {
                devices = devices.map {
                    if (it.id != deviceId) it else it.copy(
                        online = true,
                        widgets = it.widgets.map { current ->
                            if (current.id == widgetId) current.copy(value = value) else current
                        }
                    )
                }
            }
        }
    }

    fun onConfig(
        deviceId: String,
        widgetId: String,
        label: String,
        widgetType: String,
        page: String,
        topic: String,
        order: Int,
        raw: String
    ) {
        synchronized(lock) {
            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            val type = when (widgetType.lowercase()) {
                "toggle" -> WidgetState.Type.TOGGLE
                "button", "vbtn", "btn" -> WidgetState.Type.BUTTON
                "input", "text", "number", "slider" -> WidgetState.Type.INPUT
                "anydata", "anydatavlt", "value" -> WidgetState.Type.VALUE
                else -> WidgetState.Type.STATUS
            }

            val key = "$deviceId/$widgetId"
            val pendingValue = pendingValues[key]
            val existingDevice = devices.firstOrNull { it.id == deviceId }
            val existingWidget = existingDevice?.widgets?.firstOrNull { it.id == widgetId }

            val widget = WidgetState(
                id = widgetId,
                title = label.ifBlank { widgetId },
                type = type,
                value = pendingValue ?: existingWidget?.value ?: "",
                page = page.ifBlank { "Основная" },
                topic = topic,
                order = order,
                unit = json.optString("after").trim()
            )

            val updatedDevice = if (existingDevice == null) {
                Device(deviceId, deviceId, true, listOf(widget))
            } else {
                val replaced = existingDevice.widgets.any { it.id == widgetId }
                existingDevice.copy(
                    online = true,
                    widgets = if (replaced) {
                        existingDevice.widgets.map { if (it.id == widgetId) widget else it }
                    } else {
                        existingDevice.widgets + widget
                    }
                )
            }

            devices = if (existingDevice == null) {
                devices + updatedDevice
            } else {
                devices.map { if (it.id == deviceId) updatedDevice else it }
            }

            if (pendingValue != null) {
                pendingValues.remove(key)
            }
        }
    }
}
