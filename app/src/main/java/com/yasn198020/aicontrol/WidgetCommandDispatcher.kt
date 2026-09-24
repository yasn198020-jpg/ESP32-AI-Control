package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import com.yasn198020.aicontrol.mqtt.MqttManager

/** Handles publishing a value to a runtime widget without changing MQTT semantics. */
class WidgetCommandDispatcher(
    private val mqtt: MqttManager,
    private val getDevices: () -> List<Device>,
    private val setDevices: (List<Device>) -> Unit,
    private val log: (String) -> Unit
) {
    fun send(deviceId: String, widgetId: String, value: String): Boolean {
        val devices = getDevices()
        val widget = devices.firstOrNull { it.id == deviceId }
            ?.widgets?.firstOrNull { it.id == widgetId }

        if (widget == null) {
            log("MQTT TX skipped: widget not found: " + deviceId + "/" + widgetId)
            return false
        }

        // Keep the existing MQTT routing exactly as it was in App.kt.
        val published = when (widget.type) {
            WidgetState.Type.TOGGLE, WidgetState.Type.BUTTON ->
                mqtt.publishControl(deviceId, widgetId, value)
            else -> {
                if (widget.topic.isBlank()) {
                    log("MQTT TX skipped: config has no topic for " + widgetId)
                    false
                } else {
                    mqtt.publishWidget(widget.topic, value)
                }
            }
        }

        if (published) {
            setDevices(devices.map { device ->
                if (device.id != deviceId) {
                    device
                } else {
                    device.copy(
                        widgets = device.widgets.map { w ->
                            if (w.id == widgetId) w.copy(value = value) else w
                        }
                    )
                }
            })
        }

        return published
    }
}
