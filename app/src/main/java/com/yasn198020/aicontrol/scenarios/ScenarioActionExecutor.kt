package com.yasn198020.aicontrol.scenarios

import com.yasn198020.aicontrol.mqtt.MqttManager

class ScenarioActionExecutor {
    @Volatile var mqtt: MqttManager? = null

    fun execute(scenario: Scenario) {
        if (scenario.actionType != "MQTT_CONTROL") return
        val manager = mqtt ?: return
        if (!manager.isConnected()) return

        val actions = scenario.actions.ifEmpty {
            if (scenario.actionDeviceId.isNotBlank() && scenario.actionWidgetId.isNotBlank()) {
                listOf(ScenarioAction(scenario.actionDeviceId, scenario.actionWidgetId, scenario.actionValue.ifBlank { "1" }))
            } else emptyList()
        }
        actions.forEach { action ->
            if (action.deviceId.isNotBlank() && action.widgetId.isNotBlank()) {
                manager.publishControl(action.deviceId, action.widgetId, action.value.ifBlank { "1" })
            }
        }
    }
}
