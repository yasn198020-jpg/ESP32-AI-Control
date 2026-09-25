package com.yasn198020.aicontrol.devices

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import org.json.JSONObject

/** Owns MQTT-derived device/widget state without changing the MQTT protocol. */
class DeviceManager(
    private val onDevicesChanged: (List<Device>) -> Unit,
    private val onPendingValueStored: (key: String, value: String) -> Unit = { _, _ -> }
) {
    private var devices: List<Device> = emptyList()
    private val pendingValues = mutableMapOf<String, String>()

    fun snapshot(): List<Device> = devices

    fun clear() {
        devices = emptyList()
        pendingValues.clear()
        publish()
    }

    fun onStatus(deviceId: String, widgetId: String, value: String) {
        val key = "$deviceId/$widgetId"
        val existingDevice = devices.firstOrNull { it.id == deviceId }
        val existingWidget = existingDevice?.widgets?.any { it.id == widgetId } == true

        if (!existingWidget) {
            pendingValues[key] = value
            onPendingValueStored(key, value)
        }

        if (existingDevice != null && existingWidget) {
            devices = devices.map { device ->
                if (device.id != deviceId) device else device.copy(
                    online = true,
                    widgets = device.widgets.map { widget ->
                        if (widget.id == widgetId) widget.copy(value = value) else widget
                    }
                )
            }
        } else if (existingDevice != null) {
            devices = devices.map { device ->
                if (device.id == deviceId) device.copy(online = true) else device
            }
        }
        publish()
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
        val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val type = when (widgetType.lowercase()) {
            "toggle" -> WidgetState.Type.TOGGLE
            "button", "vbtn", "btn" -> WidgetState.Type.BUTTON
            "input", "text", "number", "slider" -> WidgetState.Type.INPUT
            "anydata", "anydatavlt", "value" -> WidgetState.Type.VALUE
            else -> WidgetState.Type.STATUS
        }
        val newPage = page.ifBlank { "Основная" }
        val newUnit = json.optString("after").trim()
        val definitionName = json.optString("name", widgetType).trim().ifBlank { widgetType }
        val key = "$deviceId/$widgetId"
        val pendingValue = pendingValues[key]
        val existing = devices.firstOrNull { it.id == deviceId }
        val existingWidget = existing?.widgets?.firstOrNull { it.id == widgetId }
        val newWidget = WidgetState(
            widgetId,
            label.ifBlank { widgetId },
            type,
            pendingValue ?: existingWidget?.value ?: "",
            newPage,
            topic,
            order,
            newUnit,
            definitionName,
            json.toString()
        )

        if (existing == null) {
            devices = devices + Device(deviceId, deviceId, true, listOf(newWidget))
        } else {
            devices = devices.map { device ->
                if (device.id != deviceId) device else {
                    val exists = device.widgets.any { it.id == widgetId }
                    device.copy(
                        online = true,
                        widgets = if (exists) device.widgets.map { widget ->
                            if (widget.id == widgetId) newWidget else widget
                        } else device.widgets + newWidget
                    )
                }
            }
        }

        if (pendingValue != null) pendingValues.remove(key)
        publish()
    }

    private fun publish() = onDevicesChanged(devices)
}
