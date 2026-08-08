package de.pascal.casambibridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import de.pascal.casambibridge.R

class CasambiBridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var mqtt: MqttBridge? = null
    private var ble: CasambiBleClient? = null
    private var bridgeStarting = false
    private var runtimeActive = false
    private var startGeneration = 0
    private val pendingCommands = mutableListOf<CasambiCommand>()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("casambi_bridge", "Casambi Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBridge()
            ACTION_BLE_TEST -> startBleOnly()
            ACTION_COMMAND -> handleCommand(intent)
            ACTION_SCENE -> handleScene(intent)
            else -> startBridge(forceRestart = true)
        }
        return START_STICKY
    }

    private fun setup(config: BridgeConfig) {
        DebugExporter.configure(config)
        TcpLogServer.configure(config)
        WebControlServer.configure(this, config)
    }

    private fun startBridge(forceRestart: Boolean) {
        if (bridgeStarting) {
            LogBus.log("Bridge Start bereits aktiv, kein zweiter Start")
            return
        }
        if (!forceRestart && runtimeActive && ble != null) {
            LogBus.log("Bridge laeuft bereits, kein Neustart noetig")
            return
        }

        bridgeStarting = true
        runtimeActive = false
        startGeneration++
        val generation = startGeneration

        ble?.close()
        ble = null
        mqtt?.disconnect()
        mqtt = null

        val config = ConfigStore.load(this)
        setup(config)
        startForeground(1, notification("Starte Bridge"))

        if (config.autoApiFetchEnabled) {
            Thread {
                val updated = runAutoApiFetch(config)
                handler.post {
                    if (generation == startGeneration) startBridgeRuntime(updated, generation)
                    else LogBus.log("Veralteter Bridge Start verworfen generation=$generation current=$startGeneration")
                }
            }.start()
        } else {
            startBridgeRuntime(config, generation)
        }
    }

    private fun startBridgeRuntime(config: BridgeConfig, generation: Int) {
        if (generation != startGeneration) {
            LogBus.log("Bridge Runtime Start verworfen generation=$generation current=$startGeneration")
            return
        }
        setup(config)
        RuntimeStatus.markBridgeStarted()
        RuntimeCounts.sceneCount = SceneStore.loadScenes(this).size
        RuntimeCounts.groupCount = SceneStore.loadGroups(this).size
        RuntimeCounts.unitCount = SceneStore.loadUnits(this).size.coerceAtLeast(1)
        ble = createBle(config, true, generation)
        mqtt = MqttBridge(config, LogBus::log) { cmd ->
            LogBus.log("MQTT Command Callback Unit ${cmd.unitId} type=${cmd.targetType} state=${cmd.state ?: "-"} brightness=${cmd.brightness ?: -1} effective=${cmd.effectiveBrightness}")
            handleSpecialOrSubmit(cmd)
        }.also {
            it.connectSafe()
            it.publishAvailability(true)
            it.publishDiscoveryForDemoLight()
            it.publishDiscoveryForScenes(SceneStore.loadScenes(this))
            it.publishDiscoveryForStatusEntities()
            it.publishDiscoveryForBridgeSettings()
            // v0.5.4: diagnostics discovery/state publishing remains disabled at startup to prevent MQTT publish storms.
            it.publishBridgeSettingsState(config)
            it.publishBridgeStatus("online", "connecting")
            it.publishState("OFF", 0)
        }
        runtimeActive = true
        bridgeStarting = false
        ble?.connect()
        flushPendingCommands("runtime-start")
    }

    private fun runAutoApiFetch(config: BridgeConfig): BridgeConfig {
        return try {
            LogBus.log("Auto API Fetch gestartet")
            val result = CasambiCloudApi.fetch(config)
            val updated = config.copy(
                casambiNetworkName = result.networkName ?: config.casambiNetworkName,
                casambiProtocolVersion = result.protocolVersion ?: config.casambiProtocolVersion,
                casambiKeyId = result.keyId ?: config.casambiKeyId,
                casambiKeyHex = result.keyHex ?: config.casambiKeyHex
            )
            ConfigStore.save(this, updated)
            SceneStore.saveScenes(this, result.scenes)
            SceneStore.saveGroups(this, result.groups)
            SceneStore.saveUnits(this, result.units)
            RuntimeCounts.sceneCount = result.scenes.size
            RuntimeCounts.groupCount = result.groups.size
            RuntimeCounts.unitCount = result.units.size.coerceAtLeast(1)
            RuntimeStatus.markSync()
            LogBus.log("Auto API Fetch OK: ${result.rawSummary}")
            if (result.keyHex != null) LogBus.log("Auto API Fetch: KeyStore aktualisiert")
            updated
        } catch (t: Throwable) {
            LogBus.log("Auto API Fetch Fehler: ${t.message}")
            config
        }
    }

    private fun startBleOnly() {
        val config = ConfigStore.load(this)
        setup(config)
        startForeground(1, notification("BLE/Auth Test"))
        ble?.close()
        runtimeActive = false
        bridgeStarting = false
        startGeneration++
        ble = createBle(config, false, startGeneration).also { it.connect() }
    }

    private fun updateBridgeSetting(command: CasambiCommand) {
        val enabled = command.state.equals("ON", true)
        val current = ConfigStore.load(this)
        val updated = when (command.targetType) {
            92 -> current.copy(webInterfaceEnabled = enabled)
            93 -> current.copy(smbDebugEnabled = enabled)
            94 -> current.copy(tcpLogEnabled = enabled)
            95 -> current.copy(autoApiFetchEnabled = enabled)
            else -> current
        }
        ConfigStore.save(this, updated)
        DebugExporter.configure(updated)
        TcpLogServer.configure(updated)
        WebControlServer.configure(this, updated)
        mqtt?.publishBridgeSettingsState(updated)
        val label = when (command.targetType) {
            92 -> "Web Interface"
            93 -> "SMB Logging"
            94 -> "TCP Logstream"
            95 -> "Auto API Fetch"
            else -> "Setting"
        }
        LogBus.log("HA Settings: $label ${if (enabled) "ON" else "OFF"}")
    }

    private fun handleSpecialOrSubmit(command: CasambiCommand) {
        when (command.targetType) {
            90 -> {
                LogBus.log("HA Button: API Fetch gestartet")
                Thread {
                    val current = ConfigStore.load(this)
                    val updated = runAutoApiFetch(current)
                    handler.post {
                        LogBus.log("HA Button: Bridge Restart nach API Fetch")
                        startBridge(forceRestart = true)
                    }
                }.start()
            }
            91 -> {
                LogBus.log("HA Button: Bridge Restart")
                startBridge(forceRestart = true)
            }
            92, 93, 94, 95 -> updateBridgeSetting(command)
            else -> submitOrQueue(command)
        }
    }

    private fun handleCommand(intent: Intent) {
        val state = intent.getStringExtra(EXTRA_STATE)
        val brightness = if (intent.hasExtra(EXTRA_BRIGHTNESS)) intent.getIntExtra(EXTRA_BRIGHTNESS, -1).takeIf { it >= 0 } else null
        val command = CasambiCommand(1, state, brightness)
        RuntimeStatus.clearScene()
        LogBus.log("App Control Command Unit 1 state=${state ?: "-"} brightness=${brightness ?: -1} effective=${command.effectiveBrightness}")
        submitOrQueue(command)
    }

    private fun handleScene(intent: Intent) {
        val sceneId = intent.getIntExtra(EXTRA_SCENE_ID, -1)
        val sceneName = intent.getStringExtra(EXTRA_SCENE_NAME) ?: "Scene $sceneId"
        if (sceneId < 0) {
            LogBus.log("Scene Command ungueltig: sceneId fehlt")
            return
        }
        val command = CasambiCommand(sceneId, "ON", 255, 4, sceneName)
        RuntimeStatus.markScene(sceneId, sceneName)
        LogBus.log("Scene Command scene=$sceneId name=$sceneName effective=${command.effectiveBrightness}")
        submitOrQueue(command)
    }

    private fun submitOrQueue(command: CasambiCommand) {
        val client = ble
        if (bridgeStarting || client == null) {
            pendingCommands.add(command)
            LogBus.log("Command service-queued target=${command.unitId} type=${command.targetType} label=${command.label ?: "-"} state=${command.state ?: "-"} brightness=${command.brightness ?: -1} effective=${command.effectiveBrightness}")
            if (!bridgeStarting && client == null) {
                LogBus.log("Bridge nicht aktiv, starte Bridge fuer gepufferten Command")
                startBridge(forceRestart = false)
            }
            return
        }
        client.submitCommand(command)
    }

    private fun flushPendingCommands(reason: String) {
        if (pendingCommands.isEmpty()) return
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        LogBus.log("Verarbeite ${commands.size} gepufferte Commands reason=$reason")
        commands.forEachIndexed { index, command ->
            handler.postDelayed({ ble?.submitCommand(command) }, 900L + (index * 180L))
        }
    }

    private fun createBle(config: BridgeConfig, withMqtt: Boolean, generation: Int) = CasambiBleClient(this, config, LogBus::log, object : CasambiBleClient.Listener {
        override fun onConnected() {
            if (generation != startGeneration) return
            updateNotification(if (withMqtt) "BLE verbunden" else "BLE/Auth Test verbunden")
            RuntimeStatus.bleConnected = true
            if (withMqtt) {
                mqtt?.publishAvailability(true)
                mqtt?.publishBridgeStatus("online", "connected")
            }
        }
        override fun onDisconnected() {
            if (generation != startGeneration) return
            RuntimeStatus.update("OFF", RuntimeStatus.lastBrightness, false, RuntimeStatus.lastRawState)
            RuntimeStatus.bleConnected = false
            updateNotification("BLE getrennt")
            if (withMqtt) {
                mqtt?.publishBridgeStatus("online", "disconnected")
                handler.postDelayed({ if (generation == startGeneration) ble?.connect() }, 5000)
            }
        }
        override fun onNotify(data: ByteArray) {
            if (generation != startGeneration) return
            if (withMqtt) mqtt?.publishRawNotify(data)
        }
        override fun onUnitState(id: Int, online: Boolean, on: Boolean, brightness: Int, rawStateHex: String) {
            if (generation != startGeneration) return
            val state = if (online && brightness > 0) "ON" else "OFF"
            RuntimeStatus.update(state, brightness, online, rawStateHex)
            LogBus.log("MQTT State Unit $id -> state=$state brightness=$brightness raw=$rawStateHex")
            if (withMqtt) {
                mqtt?.publishLightState(id, state, brightness, online, rawStateHex)
            }
        }
    })

    private fun stopBridge() {
        startGeneration++
        bridgeStarting = false
        runtimeActive = false
        pendingCommands.clear()
        ble?.close()
        ble = null
        mqtt?.publishBridgeStatus("stopped", "disconnected")
        mqtt?.publishAvailability(false)
        mqtt?.disconnect()
        mqtt = null
        RuntimeStatus.update("OFF", RuntimeStatus.lastBrightness, false, RuntimeStatus.lastRawState)
        RuntimeStatus.bridgeState = "stopped"
        RuntimeStatus.bleConnected = false
        RuntimeStatus.mqttConnected = false
        TcpLogServer.stop()
        WebControlServer.stop()
        stopForeground(true)
        stopSelf()
        LogBus.log("Bridge gestoppt")
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "casambi_bridge")
        .setContentTitle("Casambi Bridge")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "de.pascal.casambibridge.START"
        const val ACTION_STOP = "de.pascal.casambibridge.STOP"
        const val ACTION_BLE_TEST = "de.pascal.casambibridge.BLE_TEST"
        const val ACTION_COMMAND = "de.pascal.casambibridge.COMMAND"
        const val ACTION_SCENE = "de.pascal.casambibridge.SCENE"
        const val EXTRA_STATE = "state"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_SCENE_ID = "scene_id"
        const val EXTRA_SCENE_NAME = "scene_name"
    }
}
