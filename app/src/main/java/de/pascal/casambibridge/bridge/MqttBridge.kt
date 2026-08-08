package de.pascal.casambibridge.bridge

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date

class MqttBridge(
    private val config: BridgeConfig,
    private val log: (String) -> Unit,
    private val commandCallback: (CasambiCommand) -> Unit = {}
) {
    private var client: MqttAsyncClient? = null
    @Volatile private var subscribed = false
    private val subscribeLock = Any()

    private fun topic(path: String) = "${config.baseTopic}/$path"

    fun connectSafe() {
        try { connect() } catch (t: Throwable) { log("MQTT Fehler: ${t.message}") }
    }

    private fun connect() {
        if (config.mqttHost.isBlank()) {
            log("MQTT Host leer")
            return
        }
        val uri = "tcp://${config.mqttHost}:${config.mqttPort}"
        val clientId = "casambi-bridge-${UUID.randomUUID().toString().take(8)}"
        val mqttClient = MqttAsyncClient(uri, clientId, MemoryPersistence())
        client = mqttClient
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectionLost(cause: Throwable?) {
                synchronized(subscribeLock) { subscribed = false }
                RuntimeStatus.mqttConnected = false
                log("MQTT Verbindung verloren: ${cause?.message ?: "unknown"}")
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                RuntimeStatus.mqttConnected = true
                subscribeCommandTopics()
            }
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic == null || message == null) return
                handleCommand(topic, String(message.payload, Charsets.UTF_8))
            }
        })
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            maxInflight = 200
            connectionTimeout = 10
            keepAliveInterval = 30
            if (config.mqttUser.isNotBlank()) userName = config.mqttUser
            if (config.mqttPassword.isNotBlank()) password = config.mqttPassword.toCharArray()
            setWill(topic("availability"), "offline".toByteArray(), 0, true)
        }
        mqttClient.connect(options).waitForCompletion(5000)
        RuntimeStatus.mqttConnected = true
        log("MQTT verbunden $uri")
        subscribeCommandTopics()
    }

    private fun subscribeCommandTopics() {
        val mqttClient = client ?: return
        val topics = arrayOf(
            topic("light/1/set"),
            topic("scene/+/set"),
            topic("button/api_fetch/set"),
            topic("button/restart/set"),
            topic("settings/+/set")
        )
        val qos = intArrayOf(1, 1, 1, 1, 1)
        synchronized(subscribeLock) {
            if (!mqttClient.isConnected || subscribed) return
            subscribed = true
        }
        try {
            mqttClient.subscribe(topics, qos).waitForCompletion(3000)
            log("MQTT Command Topics abonniert: ${topics.joinToString()}")
        } catch (e: Throwable) {
            synchronized(subscribeLock) { subscribed = false }
            log("MQTT Subscribe Fehler: ${e.message}")
        }
    }

    private fun handleCommand(topicName: String, payload: String) {
        if (topicName == topic("light/1/set")) {
            val json = runCatching { JSONObject(payload) }.getOrNull()
            val state = json?.optString("state", null) ?: payload.trim().uppercase().takeIf { it == "ON" || it == "OFF" }
            val brightness = json?.takeIf { it.has("brightness") }?.optInt("brightness")
            val command = CasambiCommand(1, state, brightness)
            log("MQTT Command Unit 1 empfangen state=${state ?: "-"} brightness=${brightness ?: -1} effective=${command.effectiveBrightness} payload=$payload")
            commandCallback(command)
            return
        }


        if (topicName == topic("button/api_fetch/set")) {
            val command = CasambiCommand(0, "API_FETCH", 0, 90, "HA API Fetch")
            log("MQTT Button Command empfangen: API Fetch payload=$payload")
            commandCallback(command)
            return
        }
        if (topicName == topic("button/restart/set")) {
            val command = CasambiCommand(0, "RESTART", 0, 91, "HA Restart Bridge")
            log("MQTT Button Command empfangen: Restart Bridge payload=$payload")
            commandCallback(command)
            return
        }

        val settingsPrefix = "${config.baseTopic}/settings/"
        if (topicName.startsWith(settingsPrefix) && topicName.endsWith("/set")) {
            val setting = topicName.removePrefix(settingsPrefix).removeSuffix("/set")
            val on = payload.trim().equals("ON", true) || payload.trim().equals("true", true) || payload.trim() == "1"
            val type = when (setting) {
                "webinterface" -> 92
                "smb_logging" -> 93
                "tcp_logstream" -> 94
                "auto_api_fetch" -> 95
                else -> -1
            }
            if (type < 0) {
                log("MQTT Settings Command ignoriert: unbekannt setting=$setting payload=$payload")
                return
            }
            val command = CasambiCommand(0, if (on) "ON" else "OFF", if (on) 255 else 0, type, "HA Setting $setting")
            log("MQTT Settings Command empfangen setting=$setting state=${command.state} payload=$payload")
            commandCallback(command)
            return
        }

        val prefix = "${config.baseTopic}/scene/"
        if (topicName.startsWith(prefix) && topicName.endsWith("/set")) {
            val idPart = topicName.removePrefix(prefix).removeSuffix("/set")
            val sceneId = idPart.toIntOrNull()
            if (sceneId == null) {
                log("MQTT Scene Command ignoriert: ungueltige scene id topic=$topicName payload=$payload")
                return
            }
            val command = CasambiCommand(sceneId, "ON", 255, 4, "MQTT Scene $sceneId")
            log("MQTT Scene Command empfangen scene=$sceneId payload=$payload effective=${command.effectiveBrightness}")
            commandCallback(command)
        }
    }

    fun publishAvailability(online: Boolean) = publish(topic("availability"), if (online) "online" else "offline", true)

    fun publishTest() = publish(
        topic("test"),
        JSONObject().put("bridge", "casambi").put("version", "0.5.5").toString(),
        false
    )

    fun publishDiscoveryForDemoLight() {
        val payload = JSONObject()
            .put("name", "Casambi Light 1")
            .put("unique_id", "casambi_bridge_light_1")
            .put("schema", "json")
            .put("state_topic", topic("light/1/state"))
            .put("command_topic", topic("light/1/set"))
            .put("availability_topic", topic("availability"))
            .put("brightness", true)
            .put("brightness_scale", 255)
            .put("device", deviceJson())
        publish("${config.discoveryPrefix}/light/casambi_bridge/light_1/config", payload.toString(), true)
    }

    fun publishDiscoveryForScenes(scenes: List<CasambiSceneInfo>) {
        if (scenes.isEmpty()) {
            log("MQTT Scene Discovery: keine Szenen gespeichert")
            return
        }
        scenes.forEach { scene ->
            val slug = scene.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "scene_${scene.id}" }
            val payload = JSONObject()
                .put("name", "Casambi Scene ${scene.name}")
                .put("unique_id", "casambi_bridge_scene_${scene.id}_$slug")
                .put("command_topic", topic("scene/${scene.id}/set"))
                .put("payload_press", "PRESS")
                .put("availability_topic", topic("availability"))
                .put("device", deviceJson())
            publish("${config.discoveryPrefix}/button/casambi_bridge/scene_${scene.id}_$slug/config", payload.toString(), true)
        }
        log("MQTT Scene Discovery veroeffentlicht: ${scenes.size} Szenen")
    }

    fun publishDiscoveryForStatusEntities() {
        val dev = deviceJson()
        val bridgeState = JSONObject()
            .put("name", "Casambi Bridge Status")
            .put("unique_id", "casambi_bridge_status")
            .put("state_topic", topic("status/bridge"))
            .put("availability_topic", topic("availability"))
            .put("device", dev)
        publish("${config.discoveryPrefix}/sensor/casambi_bridge/bridge_status/config", bridgeState.toString(), true)

        val ble = JSONObject()
            .put("name", "Casambi BLE Status")
            .put("unique_id", "casambi_bridge_ble_status")
            .put("state_topic", topic("status/ble"))
            .put("availability_topic", topic("availability"))
            .put("device", dev)
        publish("${config.discoveryPrefix}/sensor/casambi_bridge/ble_status/config", ble.toString(), true)

        val unit = JSONObject()
            .put("name", "Casambi Unit 1 Online")
            .put("unique_id", "casambi_bridge_unit_1_online")
            .put("state_topic", topic("light/1/state"))
            .put("value_template", "{% if value_json.online %}ON{% else %}OFF{% endif %}")
            .put("availability_topic", topic("availability"))
            .put("device", dev)
        publish("${config.discoveryPrefix}/binary_sensor/casambi_bridge/unit_1_online/config", unit.toString(), true)

        val apiButton = JSONObject()
            .put("name", "Casambi API Fetch")
            .put("unique_id", "casambi_bridge_api_fetch")
            .put("command_topic", topic("button/api_fetch/set"))
            .put("payload_press", "PRESS")
            .put("availability_topic", topic("availability"))
            .put("device", dev)
        publish("${config.discoveryPrefix}/button/casambi_bridge/api_fetch/config", apiButton.toString(), true)

        val restartButton = JSONObject()
            .put("name", "Casambi Restart Bridge")
            .put("unique_id", "casambi_bridge_restart")
            .put("command_topic", topic("button/restart/set"))
            .put("payload_press", "PRESS")
            .put("availability_topic", topic("availability"))
            .put("device", dev)
        publish("${config.discoveryPrefix}/button/casambi_bridge/restart/config", restartButton.toString(), true)
        log("MQTT Status/Button Discovery veroeffentlicht")
    }

    fun publishDiscoveryForDiagnostics() {
        log("MQTT Diagnostics Discovery in v0.5.5 voruebergehend deaktiviert")
    }
    fun publishDiagnosticStates() {
        // v0.5.5: disabled to avoid MQTT publish storms on Android/Paho.
        return
    }
    fun publishBridgeStatus(bridge: String, ble: String) {
        RuntimeStatus.bridgeState = bridge
        RuntimeStatus.bleConnected = ble.equals("connected", true)
        publish(topic("status/bridge"), bridge, true)
        publish(topic("status/ble"), ble, true)
    }

    fun publishDiscoveryForBridgeSettings() {
        val settings = listOf(
            Triple("webinterface", "Casambi Web Interface", "casambi_bridge_webinterface"),
            Triple("smb_logging", "Casambi SMB Logging", "casambi_bridge_smb_logging"),
            Triple("tcp_logstream", "Casambi TCP Logstream", "casambi_bridge_tcp_logstream"),
            Triple("auto_api_fetch", "Casambi Auto API Fetch", "casambi_bridge_auto_api_fetch")
        )
        settings.forEach { (key, name, uniqueId) ->
            val payload = JSONObject()
                .put("name", name)
                .put("unique_id", uniqueId)
                .put("command_topic", topic("settings/$key/set"))
                .put("state_topic", topic("settings/$key/state"))
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("state_on", "ON")
                .put("state_off", "OFF")
                .put("availability_topic", topic("availability"))
                .put("device", deviceJson())
            publish("${config.discoveryPrefix}/switch/casambi_bridge/$key/config", payload.toString(), true)
        }
        log("MQTT Bridge Settings Discovery veroeffentlicht")
    }

    fun publishBridgeSettingsState(c: BridgeConfig = config) {
        publish(topic("settings/webinterface/state"), if (c.webInterfaceEnabled) "ON" else "OFF", true)
        publish(topic("settings/smb_logging/state"), if (c.smbDebugEnabled) "ON" else "OFF", true)
        publish(topic("settings/tcp_logstream/state"), if (c.tcpLogEnabled) "ON" else "OFF", true)
        publish(topic("settings/auto_api_fetch/state"), if (c.autoApiFetchEnabled) "ON" else "OFF", true)
    }

    fun publishState(state: String, brightness: Int) = publishLightState(1, state, brightness, false, "")

    fun publishLightState(id: Int, state: String, brightness: Int, online: Boolean, raw: String) {
        val payload = JSONObject()
            .put("state", state)
            .put("brightness", brightness.coerceIn(0, 255))
            .put("online", online)
            .put("raw_state", raw)
            .put("unit_id", id)
        publish(topic("light/$id/state"), payload.toString(), true)
        publish(topic("debug/unit/$id"), payload.toString(), false)
    }

    fun publishRawNotify(data: ByteArray) = publish(topic("raw_notify"), HexUtil.bytesToHex(data), false)

    fun disconnect() {
        RuntimeStatus.mqttConnected = false
        try { client?.disconnect()?.waitForCompletion(1500) } catch (_: Throwable) {}
        client = null
        synchronized(subscribeLock) { subscribed = false }
    }

    private fun deviceJson() = JSONObject()
        .put("identifiers", "casambi_bridge_android")
        .put("name", "Android Casambi Bridge")
        .put("manufacturer", "Pascal/Copilot")
        .put("model", "Android BLE Bridge")
        .put("sw_version", "0.5.5")

    private fun publish(topicName: String, payload: String, retained: Boolean) {
        val mqttClient = client ?: return
        if (!mqttClient.isConnected) return
        try {
            val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                qos = 0
                isRetained = retained
            }
            mqttClient.publish(topicName, msg)
        } catch (t: Throwable) {
            RuntimeStatus.mqttConnected = false
            log("MQTT publish ignoriert: ${t.message ?: t.javaClass.simpleName} topic=$topicName")
        }
    }
}
