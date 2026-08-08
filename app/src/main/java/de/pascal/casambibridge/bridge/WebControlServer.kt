package de.pascal.casambibridge.bridge

import android.content.Context
import android.content.Intent
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.ACTION_COMMAND
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.EXTRA_BRIGHTNESS
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.EXTRA_STATE
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import org.json.JSONObject
import android.util.Base64
import java.security.MessageDigest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

object WebControlServer {
    private val lock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var runningPort: Int = -1
    @Volatile private var appContext: Context? = null

    fun configure(context: Context, config: BridgeConfig) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!config.webInterfaceEnabled && !config.directModeEnabled) { NsdDiscoveryAdvertiser.stop(); stopLocked(true); return }
            val port = config.webInterfacePort.coerceIn(1024, 65535)
            if (serverSocket != null && runningPort == port) { NsdDiscoveryAdvertiser.configure(context, config); LogBus.log("Webinterface laeuft bereits auf Port $port"); return }
            stopLocked(false)
            startLocked(port)
            NsdDiscoveryAdvertiser.configure(context, config)
        }
    }

    fun stop() { synchronized(lock) { NsdDiscoveryAdvertiser.stop(); stopLocked(true) } }

    private fun startLocked(port: Int) {
        thread(name = "casambi-web-control", isDaemon = true) {
            var local: ServerSocket? = null
            try {
                val server = ServerSocket(port)
                local = server
                synchronized(lock) { serverSocket = server; runningPort = port }
                LogBus.log("Webinterface aktiv auf Port $port")
                while (!server.isClosed) {
                    val socket = server.accept()
                    thread(name = "casambi-web-client", isDaemon = true) {
                        socket.use { s ->
                            try {
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val request = reader.readLine() ?: return@thread
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isBlank()) break
                                    val key = line.substringBefore(":", "").trim().lowercase()
                                    val value = line.substringAfter(":", "").trim()
                                    if (key.isNotBlank()) headers[key] = value
                                }
                                val path = request.split(" ").getOrNull(1) ?: "/"
                                if (path.startsWith("/ws")) {
                                    handleWebSocket(s, headers)
                                } else {
                                    val response = handle(path)
                                    writeResponse(s.getOutputStream(), response.first, response.second)
                                }
                            } catch (t: Throwable) {
                                LogBus.log("Webinterface Client Fehler: ${t.message}")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                LogBus.log("Webinterface Fehler auf Port $port: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                synchronized(lock) { if (serverSocket === local) { serverSocket = null; runningPort = -1 } }
            }
        }
    }

    private fun stopLocked(logStop: Boolean) {
        val old = runningPort
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        runningPort = -1
        if (logStop && old > 0) LogBus.log("Webinterface gestoppt auf Port $old")
    }

    private fun handle(path: String): Pair<String, String> {
        val parts = path.split("?", limit = 2)
        val route = parts[0]
        val params = if (parts.size > 1) parseQuery(parts[1]) else emptyMap()
        if (route == "/api/info") return "application/json; charset=utf-8" to apiInfoJson()
        if (route == "/api/status") return "application/json; charset=utf-8" to statusJson()
        if (route == "/api/units") return "application/json; charset=utf-8" to apiUnitsJson()
        if (route == "/api/scenes") return "application/json; charset=utf-8" to apiScenesJson()
        if (route.startsWith("/api/light/")) {
            val id = route.removePrefix("/api/light/").trim('/').toIntOrNull() ?: 1
            val state = params["state"] ?: params["power"] ?: "ON"
            val brightness = params["brightness"]?.toIntOrNull()
            LogBus.log("Direct API RX light unit=$id state=$state brightness=${brightness ?: -1}")
            sendCommandToUnit(id, state, brightness)
            LogBus.log("Direct API TX light command unit=$id state=$state")
            return "application/json; charset=utf-8" to JSONObject().put("ok", true).put("unit_id", id).put("state", state).put("brightness", brightness ?: JSONObject.NULL).toString()
        }
        if (route.startsWith("/api/scene/")) {
            val id = route.removePrefix("/api/scene/").trim('/').toIntOrNull() ?: -1
            val scene = appContext?.let { ctx -> SceneStore.loadScenes(ctx).firstOrNull { it.id == id } }
            LogBus.log("Direct API RX scene id=$id")
            if (id >= 0) sendScene(id, scene?.name ?: "Scene $id")
            if (id >= 0) LogBus.log("Direct API TX scene command id=$id")
            return "application/json; charset=utf-8" to JSONObject().put("ok", id >= 0).put("scene_id", id).put("scene_name", scene?.name ?: "Scene $id").toString()
        }
        if (route == "/api/mode") {
            LogBus.log("Direct API RX mode ${params}")
            val ctx = appContext ?: return "application/json; charset=utf-8" to JSONObject().put("ok", false).put("error", "no context").toString()
            val current = ConfigStore.load(ctx)
            fun optBool(name: String, old: Boolean): Boolean = params[name]?.let { it.equals("ON", true) || it.equals("true", true) || it == "1" } ?: old
            val updated = current.copy(
                mqttEnabled = optBool("mqtt", current.mqttEnabled),
                directModeEnabled = optBool("direct", current.directModeEnabled),
                networkDiscoveryEnabled = optBool("discovery", current.networkDiscoveryEnabled)
            )
            ConfigStore.save(ctx, updated)
            DebugExporter.configure(updated)
            TcpLogServer.configure(updated)
            WebControlServer.configure(ctx, updated)
            ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
            LogBus.log("Direct API TX mode mqtt=${updated.mqttEnabled} direct=${updated.directModeEnabled} discovery=${updated.networkDiscoveryEnabled}")
            return "application/json; charset=utf-8" to JSONObject().put("ok", true).put("mqtt", updated.mqttEnabled).put("direct", updated.directModeEnabled).put("discovery", updated.networkDiscoveryEnabled).toString()
        }
        if (route == "/api/restart") {
            LogBus.log("Direct API RX restart")
            val ctx = appContext ?: return "application/json; charset=utf-8" to JSONObject().put("ok", false).put("error", "no context").toString()
            ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
            LogBus.log("Direct API TX restart")
            return "application/json; charset=utf-8" to JSONObject().put("ok", true).put("action", "restart").toString()
        }
        return when (route) {
            "/", "/index.html" -> "text/html; charset=utf-8" to dashboard("Bereit")
            "/command" -> {
                val state = params["state"] ?: "ON"
                val brightness = params["brightness"]?.toIntOrNull()
                sendCommand(state, brightness)
                "text/html; charset=utf-8" to dashboard("Befehl gesendet: $state ${brightness ?: ""}")
            }
            "/status" -> "application/json; charset=utf-8" to statusJson()
            "/logs" -> "text/html; charset=utf-8" to logsPage()
            "/logs.txt" -> "text/plain; charset=utf-8" to LogBus.recentLines(180).joinToString("\n")
            "/files" -> "text/html; charset=utf-8" to filesPage()
            "/file" -> fileResponse(params["name"] ?: "")
            "/toggle-ws" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                val updated = c.copy(webSocketLiveEnabled = !c.webSocketLiveEnabled)
                ConfigStore.save(ctx, updated)
                val msg = if (updated.webSocketLiveEnabled) "WebSocket Live Updates aktiviert" else "WebSocket Live Updates deaktiviert"
                "text/html; charset=utf-8" to dashboard(msg)
            }
            "/dashboard-yaml" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                "text/yaml; charset=utf-8" to DashboardExporter.generateYaml(ctx, c)
            }
            "/dashboard-direct-yaml" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                "text/yaml; charset=utf-8" to DashboardExporter.generateDirectYaml(ctx, c)
            }
            "/dashboard-export" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                val msg = try { "MQTT Dashboard YAML gespeichert: ${DashboardExporter.exportToSmb(ctx, c)}" } catch (t: Throwable) { "MQTT Dashboard Export Fehler: ${t.message}" }
                "text/html; charset=utf-8" to dashboard(msg)
            }
            "/dashboard-direct-export" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                val msg = try { "Direct Dashboard YAML gespeichert: ${DashboardExporter.exportDirectToSmb(ctx, c)}" } catch (t: Throwable) { "Direct Dashboard Export Fehler: ${t.message}" }
                "text/html; charset=utf-8" to dashboard(msg)
            }
            "/backup" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                val msg = try { "Full Backup gespeichert: ${ConfigBackup.exportFullToSmb(ctx, c)}" } catch (t: Throwable) { "Backup Fehler: ${t.message}" }
                "text/html; charset=utf-8" to dashboard(msg)
            }
            "/scene" -> {
                val id = params["id"]?.toIntOrNull() ?: -1
                val name = params["name"] ?: "Scene $id"
                if (id >= 0) sendScene(id, name)
                "text/html; charset=utf-8" to dashboard("Scene gesendet: $name")
            }
            "/fetch-api", "/scan" -> "text/html; charset=utf-8" to dashboard(fetchApiNow())
            else -> "text/html; charset=utf-8" to dashboard("Unbekannte Route: $route")
        }
    }

    private fun primaryUnitName(): String {
        val ctx = appContext ?: return "Casambi Light 1"
        return SceneStore.loadUnits(ctx).firstOrNull()?.name ?: "Casambi Light 1"
    }
    private fun apiInfoJson(): String {
        val ctx = appContext
        val c = if (ctx != null) ConfigStore.load(ctx) else BridgeConfig()
        val webUrl = localWebUrl(c)
        return JSONObject()
            .put("name", c.casambiNetworkName.ifBlank { "Casambi Jungle Bridge" })
            .put("version", "0.7.3")
            .put("mode", if (c.mqttEnabled && c.directModeEnabled) "hybrid" else if (c.directModeEnabled) "direct" else "mqtt")
            .put("mqtt_enabled", c.mqttEnabled && c.mqttHost.isNotBlank())
            .put("direct_enabled", c.directModeEnabled)
            .put("network_discovery_enabled", c.networkDiscoveryEnabled)
            .put("base_topic", c.baseTopic)
            .put("web_url", webUrl)
            .put("api", "/api/info")
            .put("status", "/api/status")
            .put("ws", "/ws")
            .put("auth", "none")
            .put("units", JSONObject(apiUnitsJson()).optJSONArray("units"))
            .put("scenes", JSONObject(apiScenesJson()).optJSONArray("scenes"))
            .toString()
    }
    private fun apiUnitsJson(): String {
        val ctx = appContext
        val arr = org.json.JSONArray()
        if (ctx != null) SceneStore.loadUnits(ctx).forEach { unit ->
            arr.put(JSONObject().put("id", unit.id).put("name", unit.name))
        }
        return JSONObject().put("units", arr).toString()
    }
    private fun apiScenesJson(): String {
        val ctx = appContext
        val arr = org.json.JSONArray()
        if (ctx != null) SceneStore.loadScenes(ctx).forEach { scene ->
            arr.put(JSONObject().put("id", scene.id).put("name", scene.name))
        }
        return JSONObject().put("scenes", arr).toString()
    }
    private fun localWebUrl(c: BridgeConfig): String {
        val ip = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.254") }
                ?.hostAddress
        }.getOrNull() ?: ""
        return if (ip.isBlank()) "" else "http://$ip:${c.webInterfacePort.coerceIn(1024, 65535)}"
    }

    private fun statusJson(): String = JSONObject()
        .put("state", RuntimeStatus.lastState)
        .put("brightness", RuntimeStatus.lastBrightness)
        .put("online", RuntimeStatus.lastOnline)
        .put("raw", RuntimeStatus.lastRawState)
        .put("unitName", primaryUnitName())
        .put("bridge", RuntimeStatus.bridgeState)
        .put("ble", RuntimeStatus.bleConnected)
        .put("mqtt", RuntimeStatus.mqttConnected)
        .put("cloud", RuntimeStatus.cloudConnected)
        .put("uptime", RuntimeStatus.uptimeText())
        .put("lastUpdate", RuntimeStatus.lastUpdateMillis)
        .put("lastSync", RuntimeStatus.lastSyncMillis)
        .put("lastSceneId", RuntimeStatus.lastSceneId)
        .put("lastSceneName", RuntimeStatus.lastSceneName)
        .put("brightnessPct", ((RuntimeStatus.lastBrightness.coerceIn(0,255) * 100) / 255))
        .put("lastSyncText", if (RuntimeStatus.lastSyncMillis > 0L) ageText(RuntimeStatus.lastSyncMillis) else "not synced")
        .put("lastUpdateText", if (RuntimeStatus.lastUpdateMillis > 0L) ageText(RuntimeStatus.lastUpdateMillis) else "never")
        .put("version", "0.7.3")
        .put("direct", appContext?.let { ConfigStore.load(it).directModeEnabled } ?: false)
        .put("mdns", appContext?.let { ConfigStore.load(it).networkDiscoveryEnabled } ?: false)
        .toString()

    private fun fetchApiNow(): String {
        val ctx = appContext ?: return "No context"
        val c = ConfigStore.load(ctx)
        return try {
            val result = CasambiCloudApi.fetch(c)
            val updated = c.copy(
                casambiNetworkName = result.networkName ?: c.casambiNetworkName,
                casambiProtocolVersion = result.protocolVersion ?: c.casambiProtocolVersion,
                casambiKeyId = result.keyId ?: c.casambiKeyId,
                casambiKeyHex = result.keyHex ?: c.casambiKeyHex
            )
            ConfigStore.save(ctx, updated)
            SceneStore.saveScenes(ctx, result.scenes)
            SceneStore.saveGroups(ctx, result.groups)
            SceneStore.saveUnits(ctx, result.units)
            RuntimeCounts.sceneCount = result.scenes.size
            RuntimeCounts.groupCount = result.groups.size
            RuntimeCounts.unitCount = result.units.size.coerceAtLeast(1)
            RuntimeStatus.markSync()
            runCatching { DashboardExporter.exportToSmb(ctx, updated) }
            ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
            "API Fetch OK: ${result.rawSummary}. MQTT Dashboard wurde neu erzeugt."
        } catch (t: Throwable) {
            "API Fetch Fehler: ${t.message}"
        }
    }

    private fun sendCommand(state: String, brightness: Int?) {
        sendCommandToUnit(1, state, brightness)
    }
    private fun sendCommandToUnit(unitId: Int, state: String, brightness: Int?) {
        val ctx = appContext ?: return
        ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply {
            action = ACTION_COMMAND
            putExtra(EXTRA_STATE, state)
            putExtra("unit_id", unitId)
            if (brightness != null) putExtra(EXTRA_BRIGHTNESS, brightness)
        })
    }

    private fun sendScene(sceneId: Int, sceneName: String) {
        val ctx = appContext ?: return
        ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply {
            action = CasambiBridgeService.ACTION_SCENE
            putExtra(CasambiBridgeService.EXTRA_SCENE_ID, sceneId)
            putExtra(CasambiBridgeService.EXTRA_SCENE_NAME, sceneName)
        })
    }

    private fun filesPage(): String {
        val ctx = appContext ?: return page("SMB Browser", "No context")
        val c = ConfigStore.load(ctx)
        if (c.smbServer.isBlank() || c.smbShare.isBlank()) return page("SMB Browser", "<p class='muted'>SMB ist noch nicht konfiguriert.</p>")
        return try {
            val smbCtx = DebugExporter.smbContext(c)
            val dir = SmbFile(DebugExporter.smbDir(c), smbCtx)
            val files = if (dir.exists()) dir.listFiles().orEmpty().sortedByDescending { it.lastModified() } else emptyList()
            val rows = if (files.isEmpty()) "<p class='muted'>Keine Dateien gefunden.</p>" else files.joinToString("\n") { f ->
                val name = f.name.trimEnd('/')
                val enc = url(name)
                val size = if (f.isDirectory) "DIR" else "${f.length()} B"
                "<div class='file'><span>${esc(name)}</span><small>$size</small><a class='btn mini' href='/file?name=$enc'>VIEW</a></div>"
            }
            page("SMB Browser", "<div class='toolbar'><a class='btn ghost' href='/'>HOME</a><a class='btn ghost' href='/dashboard-yaml'>MQTT YAML anzeigen</a><a class='btn ghost' href='/dashboard-export'>MQTT YAML schreiben</a><a class='btn ghost' href='/dashboard-direct-yaml'>Direct YAML anzeigen</a><a class='btn ghost' href='/dashboard-direct-export'>Direct YAML schreiben</a></div>$rows")
        } catch (t: Throwable) {
            page("SMB Browser", "<p class='dangerText'>SMB Fehler: ${esc(t.message ?: t.javaClass.simpleName)}</p>")
        }
    }

    private fun fileResponse(name: String): Pair<String, String> {
        val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
        val c = ConfigStore.load(ctx)
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isBlank()) return "text/plain; charset=utf-8" to "No file"
        return try {
            val smbCtx = DebugExporter.smbContext(c)
            val file = SmbFile(DebugExporter.smbDir(c) + safe, smbCtx)
            if (!file.exists() || file.isDirectory) return "text/plain; charset=utf-8" to "File not found or directory"
            val text = SmbFileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) }
            val type = when {
                safe.endsWith(".yaml", true) || safe.endsWith(".yml", true) -> "text/yaml; charset=utf-8"
                safe.endsWith(".json", true) -> "application/json; charset=utf-8"
                else -> "text/plain; charset=utf-8"
            }
            type to text
        } catch (t: Throwable) {
            "text/plain; charset=utf-8" to "SMB File Fehler: ${t.message}"
        }
    }

    private fun dashboard(message: String): String {
        val ctx = appContext
        val c = if (ctx != null) ConfigStore.load(ctx) else BridgeConfig()
        val scenes = if (ctx != null) SceneStore.loadScenes(ctx) else emptyList()
        val unitName = if (ctx != null) SceneStore.loadUnits(ctx).firstOrNull()?.name ?: "Casambi Light 1" else "Casambi Light 1"
        val sceneButtons = if (scenes.isEmpty()) "<span class='muted'>Keine Szenen gespeichert</span>" else scenes.joinToString(" ") { s ->
            "<a id='scene-${s.id}' class='sceneBtn ghost' data-scene='${s.id}' href='/scene?id=${s.id}&name=${url(s.name)}'><span class='sceneDot'></span><b>${esc(s.name)}</b></a>"
        }
        return page("Casambi Jungle", """
<div class='hero'>
  <h1>CASAMBI JUNGLE</h1>
  <div class='sub'>${esc(c.casambiNetworkName.ifBlank { "Bridge Control Center" })} - powered by Sambesi - v0.7.3</div>
  <div class='msg'>${esc(message)}</div>
</div>
<div class='grid'>
  <section class='card'><h2>Live Status</h2><div id='statusGrid' class='statusGrid'>Lade Status...</div><div class='wsHint'>Live Mode: <b>${if (c.webSocketLiveEnabled) "WebSocket" else "Polling"}</b></div><script>window.CASAMBI_WS=${c.webSocketLiveEnabled};</script><script>${statusScript()}</script></section>
  <section id='lightCard' class='card lightCard off'><h2>${esc(unitName)}</h2><div class='powerPanel'><div id='powerOrb' class='powerOrb'>OFF</div><div class='powerMeta'><div id='lightStateText' class='stateTitle'>Licht aus</div><div id='brightnessText' class='stateSub'>Brightness 0%</div><div class='bar'><span id='brightnessBar'></span></div><div class='sliderWrap'><input id='brightnessSlider' class='jungleSlider' type='range' min='0' max='255' value='0'><div class='sliderMeta'><span>0%</span><b id='sliderValue'>0%</b><span>100%</span></div></div></div></div><div class='controlRow'><a id='cmdOn' class='cmdBtn onCmd' href='/command?state=ON'>ON</a><a id='cmdOff' class='cmdBtn offCmd active' href='/command?state=OFF'>OFF</a><a id='cmd40' class='cmdBtn dimCmd' href='/command?state=ON&brightness=102'>40%</a></div></section>
  <section class='card sceneCard'><h2>Szenen</h2><div class='activeScene'><span>Aktive Szene</span><b id='activeSceneName'>keine</b></div><div class='controlRow'>$sceneButtons</div></section>
  <section class='card'><h2>Tools</h2><div class='controlRow'><a class='btn ghost' href='/fetch-api'>API Fetch</a><a class='btn ghost' href='/dashboard-export'>MQTT YAML schreiben</a><a class='btn ghost' href='/dashboard-yaml'>MQTT YAML anzeigen</a><a class='btn ghost' href='/dashboard-direct-export'>Direct YAML schreiben</a><a class='btn ghost' href='/dashboard-direct-yaml'>Direct YAML anzeigen</a><a class='btn ghost' href='/files'>SMB Browser</a><a class='btn ghost' href='/logs'>Live Log</a><a class='btn ghost' href='/backup'>Backup SMB</a><a class='btn ghost' href='/toggle-ws'>WebSocket ${if (c.webSocketLiveEnabled) "AUS" else "EIN"}</a></div></section>
  <section class='card wide'><h2>Live Log Preview</h2><pre id='logBox'>Lade Log...</pre><script>${logScript()}</script></section>
</div>
""")
    }

    private fun logsPage(): String = page("Live Log", """
<div class='toolbar'><a class='btn ghost' href='/'>HOME</a><a class='btn ghost' href='/logs.txt'>RAW LOG</a></div>
<section class='card wide'><h2>Live Log</h2><pre id='logBox'>Lade Log...</pre><script>${logScript()}</script></section>
""")

    private fun page(title: String, body: String): String = """
<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><title>${esc(title)}</title>
<style>
:root{--bg:#04110c;--panel:#071c14ee;--line:#19ff9a70;--leaf:#14f195;--lime:#b6ff4d;--teal:#00e5ff;--amber:#ffcc66;--violet:#8a5cf6;--danger:#ff4d7d;--text:#eafff4;--muted:#8fbba5}
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:var(--text);font-family:Consolas,Monaco,monospace;background:radial-gradient(circle at 20% 10%,#0b3822 0,#05170f 35%,#020805 100%)}
.wrap{max-width:1120px;margin:auto;padding:22px}.hero,.card{border:1px solid var(--line);border-radius:24px;background:linear-gradient(180deg,#082216e8,#03100ae8);box-shadow:0 0 24px #14f19522;padding:16px;margin-bottom:16px}h1{margin:0;color:var(--leaf);font-size:28px;text-shadow:0 0 18px #14f195aa}h2{margin:0 0 12px;color:var(--teal);font-size:18px}.sub,.muted{color:var(--muted)}.msg{margin-top:10px;color:var(--lime)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}.wide{grid-column:1/-1}.btn{display:inline-flex;align-items:center;justify-content:center;margin:5px;padding:12px 15px;border-radius:15px;background:linear-gradient(135deg,var(--leaf),var(--lime));color:#001208;text-decoration:none;font-weight:900;border:0}.ghost{background:linear-gradient(135deg,#0e2b1e,#10351f);color:var(--leaf);border:1px solid var(--line)}.danger{background:linear-gradient(135deg,var(--danger),#ff9bb8)}.amber{background:linear-gradient(135deg,var(--amber),#e0a326)}.mini{padding:7px 10px;font-size:11px}.statusGrid{display:grid;gap:8px}.pill{display:flex;justify-content:space-between;gap:10px;border:1px solid #14f19533;border-radius:12px;padding:8px;background:#020a0788}.ok{color:var(--lime)}.bad,.dangerText{color:var(--danger)}.controlRow{display:flex;flex-wrap:wrap}.toolbar{margin:0 0 14px}.file{display:grid;grid-template-columns:1fr auto auto;gap:10px;align-items:center;border:1px solid #14f19533;border-radius:12px;padding:9px;margin:7px 0;background:#020a0788}pre{white-space:pre-wrap;word-break:break-word;max-height:420px;overflow:auto;background:#020a07;border:1px solid #14f19533;border-radius:16px;padding:13px;color:var(--text)}.lightCard.on{border-color:#55ff85;box-shadow:0 0 34px #14f19566}.lightCard.off{border-color:#176343;box-shadow:0 0 18px #000}.powerPanel{display:flex;gap:16px;align-items:center;margin:6px 0 16px}.powerOrb{width:92px;height:92px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:900;letter-spacing:1px;background:#06140d;border:2px solid #24543d;color:var(--muted);box-shadow:inset 0 0 24px #000}.lightCard.on .powerOrb{background:radial-gradient(circle,#b6ff4d 0,#14f195 45%,#004f2f 100%);color:#001208;box-shadow:0 0 32px #14f195aa,inset 0 0 18px #ffffff77}.lightCard.off .powerOrb{background:radial-gradient(circle,#221018 0,#0c090b 65%,#040404 100%);border-color:#ff4d7d55;color:#ff7fa0;box-shadow:0 0 20px #ff4d7d33}.powerMeta{flex:1}.stateTitle{font-size:22px;font-weight:900;color:var(--leaf)}.lightCard.off .stateTitle{color:var(--danger)}.stateSub{margin-top:4px;color:var(--muted)}.bar{height:12px;background:#03110b;border:1px solid #14f19544;border-radius:999px;overflow:hidden;margin-top:12px}.bar span{display:block;height:100%;width:0%;background:linear-gradient(90deg,var(--leaf),var(--lime),var(--teal));box-shadow:0 0 15px #14f195}.cmdBtn,.sceneBtn{display:inline-flex;align-items:center;justify-content:center;gap:8px;margin:5px;padding:13px 17px;border-radius:15px;text-decoration:none;font-weight:900;border:1px solid #14f19555;color:var(--leaf);background:#06180f}.cmdBtn.active,.sceneBtn.active{transform:translateY(-1px);box-shadow:0 0 24px #14f19588}.onCmd.active{background:linear-gradient(135deg,#14f195,#b6ff4d);color:#001208}.offCmd.active{background:linear-gradient(135deg,#ff4d7d,#ff9bb8);color:#190006}.dimCmd.active{background:linear-gradient(135deg,#ffcc66,#ffea92);color:#1e1200}.sceneBtn{min-width:88px;min-height:54px;flex-direction:column}.sceneDot{width:13px;height:13px;border-radius:50%;background:#136943;box-shadow:0 0 10px #14f19544}.sceneBtn.active{background:linear-gradient(135deg,#8a5cf6,#14f195);color:#001208;border-color:#b6ff4d}.sceneBtn.active .sceneDot{background:#fff;box-shadow:0 0 18px #fff}.activeScene{border:1px solid #8a5cf655;border-radius:16px;background:#050c12;padding:12px;margin-bottom:12px}.activeScene span{display:block;color:var(--muted);font-size:12px}.activeScene b{display:block;color:var(--violet);font-size:24px;margin-top:3px;text-shadow:0 0 16px #8a5cf688}.sliderWrap{margin-top:15px}.jungleSlider{width:100%;appearance:none;background:transparent;cursor:pointer}.jungleSlider::-webkit-slider-runnable-track{height:14px;border-radius:999px;background:linear-gradient(90deg,#173423,#14f195,#b6ff4d,#00e5ff);border:1px solid #14f19566;box-shadow:0 0 14px #14f19533}.jungleSlider::-webkit-slider-thumb{appearance:none;width:28px;height:28px;border-radius:50%;background:radial-gradient(circle,#ffffff 0,#b6ff4d 35%,#14f195 70%,#00663c 100%);border:2px solid #eafff4;margin-top:-8px;box-shadow:0 0 22px #14f195,0 0 8px #ffffff}.jungleSlider::-moz-range-track{height:14px;border-radius:999px;background:linear-gradient(90deg,#173423,#14f195,#b6ff4d,#00e5ff);border:1px solid #14f19566}.jungleSlider::-moz-range-thumb{width:28px;height:28px;border-radius:50%;background:#14f195;border:2px solid #eafff4;box-shadow:0 0 18px #14f195}.sliderMeta{display:flex;justify-content:space-between;color:var(--muted);font-size:12px;margin-top:7px}.sliderMeta b{color:var(--lime);font-size:14px;text-shadow:0 0 10px #b6ff4d}.wsHint{color:var(--muted);font-size:12px;margin-top:10px}.wsHint b{color:var(--lime)}
</style></head><body><div class='wrap'>$body</div></body></html>
""".trimIndent()

    private fun statusScript(): String = """
let sliderBusy=false;let sliderTimer=null;let pollTimer=null;let ws=null;
function applyLightVisual(s){const on=s.state==='ON'&&s.brightness>0;const pct=s.brightnessPct||0;const card=document.getElementById('lightCard');card.classList.toggle('on',on);card.classList.toggle('off',!on);document.getElementById('powerOrb').textContent=on?'ON':'OFF';document.getElementById('lightStateText').textContent=on?'Licht aktiv':'Licht aus';document.getElementById('brightnessText').textContent='Brightness '+pct+'%';document.getElementById('brightnessBar').style.width=pct+'%';document.getElementById('cmdOn').classList.toggle('active',on);document.getElementById('cmdOff').classList.toggle('active',!on);document.getElementById('cmd40').classList.toggle('active',on&&pct>=39&&pct<=41);if(!sliderBusy){const sl=document.getElementById('brightnessSlider');sl.value=s.brightness||0;document.getElementById('sliderValue').textContent=pct+'%';}}
function renderStatus(s){const on=s.state==='ON'&&s.brightness>0;const pct=s.brightnessPct||0;const p=(n,v,ok)=>`<div class='pill'><span>${'$'}{n}</span><b class='${'$'}{ok?'ok':'bad'}'>${'$'}{v}</b></div>`;document.getElementById('statusGrid').innerHTML=p('Bridge',s.bridge,s.bridge==='online')+p('BLE',s.ble?'connected':'disconnected',s.ble)+p('MQTT',s.mqtt?'online':'offline',s.mqtt)+p('Cloud',s.cloud?'synced':'not synced',s.cloud)+p('Last Sync',s.lastSyncText||'not synced',s.cloud)+p('Licht',s.state+' / '+pct+'%',on)+p('Szene',s.lastSceneName||'keine',!!s.lastSceneName)+p('Last Update',s.lastUpdateText||'never',s.lastUpdateText&&s.lastUpdateText!=='never')+p('Uptime',s.uptime,true)+p('Version',s.version,true);applyLightVisual(s);document.getElementById('activeSceneName').textContent=s.lastSceneName||'keine';document.querySelectorAll('.sceneBtn').forEach(el=>el.classList.toggle('active',String(s.lastSceneId)===String(el.dataset.scene)));}
function sendSlider(v){const url=v<=0?'/command?state=OFF':'/command?state=ON&brightness='+v;fetch(url).catch(()=>{});}
function initSlider(){const sl=document.getElementById('brightnessSlider');if(!sl||sl.dataset.ready==='1')return;sl.dataset.ready='1';sl.addEventListener('input',()=>{sliderBusy=true;const v=parseInt(sl.value||'0');const pct=Math.round(v*100/255);document.getElementById('sliderValue').textContent=pct+'%';document.getElementById('brightnessText').textContent='Brightness '+pct+'%';document.getElementById('brightnessBar').style.width=pct+'%';clearTimeout(sliderTimer);sliderTimer=setTimeout(()=>{sendSlider(v);sliderBusy=false;},260);});sl.addEventListener('change',()=>{const v=parseInt(sl.value||'0');clearTimeout(sliderTimer);sendSlider(v);setTimeout(()=>{sliderBusy=false;refreshStatus();},450);});}
async function refreshStatus(){try{initSlider();const r=await fetch('/status');renderStatus(await r.json());}catch(e){document.getElementById('statusGrid').innerHTML='<span class="bad">Status Fehler</span>';}}
function startPolling(){refreshStatus();pollTimer=setInterval(refreshStatus,1500);}
function startWebSocket(){if(!window.CASAMBI_WS||!('WebSocket'in window)){startPolling();return;}try{const proto=location.protocol==='https:'?'wss://':'ws://';ws=new WebSocket(proto+location.host+'/ws');ws.onmessage=e=>{try{renderStatus(JSON.parse(e.data));}catch(_){}};ws.onopen=()=>{if(pollTimer)clearInterval(pollTimer);};ws.onerror=()=>{};ws.onclose=()=>{if(!pollTimer)startPolling();};}catch(e){startPolling();}}
initSlider();startWebSocket();setTimeout(()=>{if(!ws||ws.readyState!==1)refreshStatus();},700);
""".trimIndent()

    private fun logScript(): String = """
async function refreshLog(){try{const r=await fetch('/logs.txt');const t=await r.text();const el=document.getElementById('logBox');el.textContent=t||'Noch keine Logs';el.scrollTop=el.scrollHeight;}catch(e){document.getElementById('logBox').textContent='Log Fehler';}}
refreshLog();setInterval(refreshLog,2500);
""".trimIndent()



    private fun handleWebSocket(socket: Socket, headers: Map<String, String>) {
        val ctx = appContext ?: return
        val c = ConfigStore.load(ctx)
        if (!c.webSocketLiveEnabled) {
            val response = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\nWebSocket disabled"
            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            return
        }
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val out = socket.getOutputStream()
        val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
        LogBus.log("WebSocket Client verbunden")
        try {
            while (!socket.isClosed) {
                sendWebSocketText(out, statusJson())
                Thread.sleep(1000)
            }
        } catch (_: Throwable) {
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            LogBus.log("WebSocket Client getrennt")
        }
    }

    private fun sendWebSocketText(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write(0x81)
        when {
            bytes.size < 126 -> out.write(bytes.size)
            bytes.size <= 65535 -> {
                out.write(126)
                out.write((bytes.size shr 8) and 255)
                out.write(bytes.size and 255)
            }
            else -> throw IllegalArgumentException("WebSocket frame too large")
        }
        out.write(bytes)
        out.flush()
    }

    private fun ageText(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1000L)
        return when {
            seconds < 5 -> "gerade eben"
            seconds < 60 -> "vor ${seconds}s"
            seconds < 3600 -> "vor ${seconds / 60}m"
            seconds < 86400 -> "vor ${seconds / 3600}h"
            else -> "vor ${seconds / 86400}d"
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val p = it.split('=', limit = 2)
        if (p.isEmpty()) null else URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }.toMap()

    private fun writeResponse(out: OutputStream, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun url(x: String) = URLEncoder.encode(x, "UTF-8")
}
