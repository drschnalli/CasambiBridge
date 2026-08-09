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
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import java.security.MessageDigest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        if (route == "/scanners") return "text/html; charset=utf-8" to scannersDashboardPage()
        if (route == "/history") return "text/html; charset=utf-8" to scanHistoryPage()
        if (route == "/api/probe-host") return "application/json; charset=utf-8" to probeHostJson(params)
        if (route == "/api/scan-ble-live") return "application/json; charset=utf-8" to scanBleLiveJson(params)
        if (route == "/api/scan-wifi-live") return "application/json; charset=utf-8" to scanWifiLiveJson(params)
        if (route == "/tools") return "text/html; charset=utf-8" to toolsHomePage()
        if (route == "/lights") return "text/html; charset=utf-8" to dashboard("Bereit", "lights")
        if (route == "/settings") return "text/html; charset=utf-8" to settingsWebPage()
        if (route == "/settings-save") return "text/html; charset=utf-8" to saveSettingsWeb(params)
        if (route == "/scan-network") return "text/html; charset=utf-8" to scanNetworkWeb(params)
        if (route == "/scan-wifi") return "text/html; charset=utf-8" to scanWifiWeb(params)
        if (route == "/scan-ble") return "text/html; charset=utf-8" to scanBleWeb(params)
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
            "/scan-tools" -> "text/html; charset=utf-8" to scanToolsPage()
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
            .put("version", "0.10.0")
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

    private fun statusJson(): String {
        val savedLastSync = appContext?.let { ConfigStore.lastSyncMillis(it) } ?: 0L
        if (RuntimeStatus.lastSyncMillis <= 0L && savedLastSync > 0L) RuntimeStatus.lastSyncMillis = savedLastSync
        return JSONObject()
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
        .put("version", "0.10.0")
        .put("direct", appContext?.let { ConfigStore.load(it).directModeEnabled } ?: false)
        .put("mdns", appContext?.let { ConfigStore.load(it).networkDiscoveryEnabled } ?: false)
        .toString()
    }

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
            ConfigStore.saveLastSyncMillis(ctx, RuntimeStatus.lastSyncMillis)
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

    private fun scannersDashboardPage(): String {
        return page("Scanners", """
<section class='hero card wide glassHero'><h1>Scanners</h1><p class='muted'>Live Scanner im Browser. Network scannt Host fuer Host, BLE/WiFi aktualisieren ein Terminal zyklisch ueber kleine JSON-Requests.</p></section>
<section class='card wide scannerGrid'>
  <div class='scannerPanel'>
    <h2>Live Network Scanner</h2>
    <label>Range Basis/CIDR</label><input id='netRange' value='' placeholder='leer = aktuelles /24'>
    <label>Port Preset</label><select id='portPreset' onchange='applyPortPreset()'><option value='known'>Known Ports</option><option value='none'>Ohne Ports / Host only</option><option value='custom'>Custom</option><option value='all'>All Ports langsam</option></select>
    <label>Ports</label><input id='netPorts' value='known' placeholder='none, known, all oder 22,80,443'>
    <div class='controlRow'><button class='btn' onclick='startNetworkLive()'>START LIVE</button><button class='btn ghost' onclick='stopNetworkLive()'>STOP</button><button class='btn ghost' onclick='clearTerminal("scanTerminal","netProgress")'>CLEAR</button></div>
    <div class='progress'><span id='netProgress'></span></div>
    <pre id='scanTerminal' class='terminal'>Bereit.</pre><div id='networkCards' class='scanCards'></div>
  </div>
  <div class='scannerPanel'>
    <h2>Live WiFi Scanner</h2>
    <label>Filter SSID/BSSID</label><input id='wifiFilter'>
    <div class='controlRow'><button class='btn' onclick='startWifiLive()'>START WIFI LIVE</button><button class='btn ghost' onclick='stopWifiLive()'>STOP</button><button class='btn ghost' onclick='clearTerminal("wifiTerminal","wifiProgress")'>CLEAR</button></div>
    <div class='progress'><span id='wifiProgress'></span></div>
    <pre id='wifiTerminal' class='terminal smallTerminal'>Bereit. Android 8.1 kann WiFi ScanResults leer liefern.</pre><div id='wifiAnalysis' class='analysisBox'></div><div id='wifiCards' class='scanCards'></div>
  </div>
  <div class='scannerPanel'>
    <h2>Live Bluetooth Scanner</h2>
    <label>Filter Name/MAC</label><input id='bleFilter'><label class='switchInline'><input id='bleCasambiOnly' type='checkbox'> <span>Nur Casambi Watch</span></label>
    <div class='controlRow'><button class='btn' onclick='startBleLive()'>START BLE LIVE</button><button class='btn ghost' onclick='stopBleLive()'>STOP</button><button class='btn ghost' onclick='clearTerminal("bleTerminal","bleProgress")'>CLEAR</button></div>
    <div class='progress'><span id='bleProgress'></span></div>
    <pre id='bleTerminal' class='terminal smallTerminal'>Bereit.</pre><div id='bleCards' class='scanCards'></div>
  </div>
</section>
<script>
let scanStop=false, bleStop=false, wifiStop=false;
const seenBle=new Set(); const seenWifi=new Set();
function term(id,line){const el=document.getElementById(id);el.textContent += '\n' + line;el.scrollTop=el.scrollHeight;}
function clearTerminal(id,progress){document.getElementById(id).textContent='Bereit.';document.getElementById(progress).style.width='0%'; if(id==='bleTerminal'){seenBle.clear();bleSeen.clear();document.getElementById('bleCards').innerHTML='';} if(id==='wifiTerminal'){seenWifi.clear();wifiSeenByBssid.clear();wifiSeenBySsid.clear();wifiChannels.clear();document.getElementById('wifiCards').innerHTML='';document.getElementById('wifiAnalysis').innerHTML='';} if(id==='scanTerminal'){document.getElementById('networkCards').innerHTML='';}}
function saveHistory(type,text){let arr=[];try{arr=JSON.parse(localStorage.scanHistory||'[]')}catch(e){};arr.unshift({type:type,time:new Date().toLocaleString(),count:text.split('\n').filter(Boolean).length,text:text});localStorage.scanHistory=JSON.stringify(arr.slice(0,10));}
function html(v){return String(v||'').replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
function val(line,key){let m=String(line).match(new RegExp(key+'=([^ ]+(?: [^ ]+)?)(?=  |$)'));return m?m[1]:'-';}
function signalParts(line){let m=String(line).match(/signal=(-?\d+) dBm ([A-Za-z ]+) ([█░]+)/);return {dbm:m?m[1]:'-',q:m?m[2].trim():'-',bar:m?m[3]:''};}
function secClass(sec){sec=String(sec||'').toLowerCase();if(sec.indexOf('wpa3')>=0)return 'secWpa3';if(sec.indexOf('enterprise')>=0)return 'secEnterprise';if(sec.indexOf('open')>=0)return 'secOpen';if(sec.indexOf('wep')>=0)return 'secWeak';return 'secWpa2';}
function portBadges(ports){return String(ports||'-').split(',').map(function(p){p=p.trim();if(!p)return '';let cls='portBadge';if(p.indexOf('MQTT')>=0)cls+=' mqtt';else if(p.indexOf('SMB')>=0)cls+=' smb';else if(p.indexOf('Home Assistant')>=0)cls+=' ha';else if(p.indexOf('HTTP')>=0)cls+=' http';return '<span class="'+cls+'">'+html(p)+'</span>';}).join(' ');}
function candidateBadges(ports){ports=String(ports||'');let badges='';if(ports.indexOf('1883')>=0)badges+='<span class="badge mqtt">📡 MQTT</span>';if(ports.indexOf('445')>=0)badges+='<span class="badge smb">🗂 SMB</span>';if(ports.indexOf('8123')>=0)badges+='<span class="badge ha">🏠 Home Assistant</span>';if(ports.indexOf('80')>=0||ports.indexOf('443')>=0||ports.indexOf('8080')>=0)badges+='<span class="badge http">🌐 HTTP</span>';return badges;}
const wifiSeenByBssid=new Map(); const wifiSeenBySsid=new Map(); const wifiChannels=new Map(); const bleSeen=new Map();
function updateWifiAnalysis(line){const ssid=val(line,'ssid'), bssid=val(line,'bssid'), sec=val(line,'sec'), band=val(line,'band'), ch=val(line,'ch'), freq=val(line,'freq');const sig=signalParts(line);wifiSeenByBssid.set(bssid,{ssid:ssid,bssid:bssid,sec:sec,band:band,ch:ch,freq:freq,signal:sig});if(!wifiSeenBySsid.has(ssid))wifiSeenBySsid.set(ssid,[]);wifiSeenBySsid.get(ssid).push({bssid:bssid,sec:sec,band:band,ch:ch,freq:freq,signal:sig});wifiChannels.set(ch,(wifiChannels.get(ch)||0)+1);renderWifiAnalysis();}
function renderWifiAnalysis(){const box=document.getElementById('wifiAnalysis');if(!box)return;const ch=[...wifiChannels.entries()].sort(function(a,b){return Number(a[0])-Number(b[0]);}).map(function(e){let w=Math.min(100,e[1]*18);return '<div class="chan"><b>Ch '+html(e[0])+'</b><span><i style="width:'+w+'%"></i></span><em>'+e[1]+' APs</em></div>';}).join('');const ap=[...wifiSeenByBssid.values()].slice(0,24).map(function(a){return '<div class="apLine"><b>'+html(a.bssid)+'</b><span>'+html(a.ssid)+' · '+html(a.sec)+' · '+html(a.band)+' · Ch '+html(a.ch)+'</span></div>';}).join('');box.innerHTML='<h3>WiFi Pro Analyse</h3><div class="analysisGrid"><div><h4>Channel Usage</h4>'+ch+'</div><div><h4>Access Points / BSSID</h4>'+ap+'</div></div>';}
function addWifiCard(line){const ssid=val(line,'ssid'), bssid=val(line,'bssid'), sec=val(line,'sec'), band=val(line,'band'), ch=val(line,'ch'), freq=val(line,'freq'), features=(String(line).split('features=')[1]||'-');const sig=signalParts(line);const card='<div class="scanCard wifiCard"><div class="cardTop"><b>📶 '+html(ssid)+'</b><span class="badge '+secClass(sec)+'">🔐 '+html(sec)+'</span></div><div class="meta">'+html(band)+' · Channel '+html(ch)+' · '+html(freq)+' · BSSID '+html(bssid)+'</div><div class="signal"><span>'+html(sig.dbm)+' dBm · '+html(sig.q)+'</span><i>'+html(sig.bar)+'</i></div><div class="features">'+html(features)+'</div></div>';document.getElementById('wifiCards').insertAdjacentHTML('beforeend',card);updateWifiAnalysis(line);}
function addBleCard(line){const name=val(line,'name'), mac=val(line,'mac');const sig=signalParts(line);const cand=String(line).indexOf('Casambi candidate')>=0;const old=bleSeen.get(mac)||{count:0,best:-999,last:'never'};const dbm=parseInt(sig.dbm||'-999');old.count++;old.best=Math.max(old.best,isNaN(dbm)?-999:dbm);old.last=new Date().toLocaleTimeString();bleSeen.set(mac,old);const best=old.best===-999?'-':old.best+' dBm';const card='<div class="scanCard bleCard '+(cand?'casambiCard':'')+'"><div class="cardTop"><b>🔵 '+html(name)+'</b>'+(cand?'<span class="badge secWpa3">🌴 Casambi</span>':'')+'</div><div class="meta">MAC '+html(mac)+' · seen '+old.count+'x · last '+html(old.last)+' · best '+html(best)+'</div><div class="signal"><span>'+html(sig.dbm)+' dBm · '+html(sig.q)+'</span><i>'+html(sig.bar)+'</i></div></div>';document.getElementById('bleCards').insertAdjacentHTML('beforeend',card);}
function addNetCard(line){const host=val(line,'host'), name=val(line,'name'), ping=val(line,'ping'), mac=val(line,'mac');const ports=(String(line).split('ports=')[1]||'-');const card='<div class="scanCard netCard"><div class="cardTop"><b>🟢 '+html(host)+'</b><span class="badge secWpa2">'+html(ping)+'</span></div><div class="meta">Name: '+html(name)+' · MAC: '+html(mac)+'</div><div class="candidateRow">'+candidateBadges(ports)+'</div><div class="features">Ports: '+portBadges(ports)+'</div></div>';document.getElementById('networkCards').insertAdjacentHTML('beforeend',card);}
function applyPortPreset(){const v=document.getElementById('portPreset').value;document.getElementById('netPorts').value=(v==='custom')?'22,80,443,445,1883,5555,8080,8123':v;}
function stopNetworkLive(){scanStop=true;term('scanTerminal','[STOP] angefordert');}
function subnetBase(value){let raw=(value||'').trim(); if(!raw) return ''; return raw.split('/')[0].split('.').slice(0,3).join('.');}
async function startNetworkLive(){scanStop=false;clearTerminal('scanTerminal','netProgress');const range=document.getElementById('netRange').value;const ports=document.getElementById('netPorts').value||'known';let base=subnetBase(range);term('scanTerminal','[NETWORK] Live scan gestartet ports='+ports);for(let i=1;i<=254;i++){if(scanStop)break;let url='/api/probe-host?i='+i+'&ports='+encodeURIComponent(ports);if(base)url+='&base='+encodeURIComponent(base);try{const r=await fetch(url,{cache:'no-store'});const j=await r.json();document.getElementById('netProgress').style.width=Math.round(i/254*100)+'%';if(j.visible){term('scanTerminal',j.line);addNetCard(j.line);}else if(i%25===0){term('scanTerminal','[progress] '+i+'/254');}}catch(e){term('scanTerminal','[error] '+e);}await new Promise(res=>setTimeout(res,12));}term('scanTerminal',scanStop?'[NETWORK] gestoppt':'[NETWORK] fertig');saveHistory('Network',document.getElementById('scanTerminal').textContent);}
function stopBleLive(){bleStop=true;term('bleTerminal','[BLE] STOP');}
async function startBleLive(){bleStop=false;clearTerminal('bleTerminal','bleProgress');term('bleTerminal','[BLE] Live Scan gestartet');for(let round=1;round<=8;round++){if(bleStop)break;try{const f=document.getElementById('bleFilter').value||'';const r=await fetch('/api/scan-ble-live?filter='+encodeURIComponent(f)+'&round='+round,{cache:'no-store'});const j=await r.json();document.getElementById('bleProgress').style.width=Math.round(round/8*100)+'%';(j.rows||[]).forEach(line=>{if(document.getElementById('bleCasambiOnly').checked && line.indexOf('Casambi candidate')<0)return;if(!seenBle.has(line)){seenBle.add(line);term('bleTerminal',line);addBleCard(line);}});term('bleTerminal','[BLE] round '+round+' results='+(j.rows||[]).length);}catch(e){term('bleTerminal','[BLE error] '+e);}await new Promise(res=>setTimeout(res,220));}term('bleTerminal',bleStop?'[BLE] gestoppt':'[BLE] fertig');saveHistory('Bluetooth',document.getElementById('bleTerminal').textContent);}
function stopWifiLive(){wifiStop=true;term('wifiTerminal','[WIFI] STOP');}
async function startWifiLive(){wifiStop=false;clearTerminal('wifiTerminal','wifiProgress');term('wifiTerminal','[WIFI] Live Scan gestartet');for(let round=1;round<=8;round++){if(wifiStop)break;try{const f=document.getElementById('wifiFilter').value||'';const r=await fetch('/api/scan-wifi-live?filter='+encodeURIComponent(f)+'&round='+round,{cache:'no-store'});const j=await r.json();document.getElementById('wifiProgress').style.width=Math.round(round/8*100)+'%';if((j.rows||[]).length===0){term('wifiTerminal','[WIFI] round '+round+' cached=0 startOk='+j.startOk);}else{(j.rows||[]).forEach(line=>{if(!seenWifi.has(line)){seenWifi.add(line);term('wifiTerminal',line);addWifiCard(line);}});}}catch(e){term('wifiTerminal','[WIFI error] '+e);}await new Promise(res=>setTimeout(res,420));}term('wifiTerminal',wifiStop?'[WIFI] gestoppt':'[WIFI] fertig');saveHistory('WiFi',document.getElementById('wifiTerminal').textContent);}
</script>
""")
    }
    private fun probeHostJson(params: Map<String,String>): String {
        val i = params["i"]?.toIntOrNull()?.coerceIn(1,254) ?: 1
        val base = params["base"]?.trim()?.takeIf { it.count { ch -> ch == '.' } == 2 } ?: currentSubnet24Web().substringBefore('/').substringBeforeLast('.', "192.168.1")
        val ip = "$base.$i"
        val ports = webPortList(params["ports"].orEmpty())
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
        if (addr == null) return JSONObject().put("ip", ip).put("visible", false).toString()
        val pingStart = System.currentTimeMillis()
        val pingOk = runCatching { addr.isReachable(120) }.getOrDefault(false)
        val pingMs = (System.currentTimeMillis() - pingStart).coerceAtLeast(1L)
        val open = if (ports.isEmpty()) emptyList() else ports.filter { webPortOpen(ip, it, 95) }
        val reachable = if (ports.isEmpty()) pingOk else open.isNotEmpty() || pingOk
        val name = runCatching { addr.canonicalHostName }.getOrDefault("-")
        val mac = arpMacForWeb(ip)
        val portText = if (ports.isEmpty()) "not scanned" else if (open.isEmpty()) "-" else open.joinToString(",") { p -> "$p ${portName(p)}" }
        val line = "🟢 host=$ip  name=${if (name == ip) "-" else name}  ping=${if (pingOk) "${pingMs}ms" else "n/a"}  mac=$mac  ports=$portText"
        return JSONObject().put("ip", ip).put("visible", reachable).put("name", if (name == ip) "-" else name).put("mac", mac).put("ports", portText).put("ping", if (pingOk) "${pingMs}ms" else "n/a").put("line", line).toString()
    }
    private fun currentSubnet24Web(): String {
        val ip = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.254") }
                ?.hostAddress
        }.getOrNull() ?: "192.168.1.1"
        return ip.substringBeforeLast('.', "192.168.1") + ".0/24"
    }
    private fun webPortList(modeRaw: String): List<Int> {
        val mode = modeRaw.trim().lowercase().ifBlank { "known" }
        val known = listOf(22, 53, 80, 443, 445, 1883, 5555, 8080, 8123)
        return when (mode) {
            "none", "host", "hosts", "ping", "off" -> emptyList()
            "all" -> (1..65535).toList()
            "known" -> known
            else -> mode.split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct().ifEmpty { known }
        }
    }
    private fun webPortOpen(host: String, port: Int, timeoutMs: Int = 140): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)
    private fun arpMacForWeb(ip: String): String = runCatching {
        java.io.File("/proc/net/arp").readLines().drop(1).firstOrNull { line -> line.trim().split(Regex("\\s+")).firstOrNull() == ip }?.trim()?.split(Regex("\\s+"))?.getOrNull(3) ?: "-"
    }.getOrDefault("-")
    private fun wifiSecurityLabel(caps: String): String {
        val c = caps.uppercase()
        return when {
            c.contains("SAE") -> "WPA3"
            c.contains("OWE") -> "WPA3/OWE"
            c.contains("EAP") && c.contains("WPA2") -> "WPA2-Enterprise"
            c.contains("EAP") -> "Enterprise"
            c.contains("WPA2") || c.contains("RSN") -> "WPA2"
            c.contains("WPA") -> "WPA"
            c.contains("WEP") -> "WEP"
            else -> "OPEN"
        }
    }
    private fun wifiBand(freq: Int): String = when {
        freq in 2400..2499 -> "2.4 GHz"
        freq in 4900..5899 -> "5 GHz"
        freq in 5925..7125 -> "6 GHz"
        else -> "${freq} MHz"
    }
    private fun wifiChannel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> ((freq - 2407) / 5)
        freq in 5000..5895 -> ((freq - 5000) / 5)
        freq in 5955..7115 -> ((freq - 5950) / 5)
        else -> 0
    }
    private fun wifiQuality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        rssi >= -85 -> "Weak"
        else -> "Very weak"
    }
    private fun signalBar(rssi: Int): String = when {
        rssi >= -50 -> "██████████"
        rssi >= -67 -> "████████░░"
        rssi >= -75 -> "██████░░░░"
        rssi >= -85 -> "████░░░░░░"
        else -> "██░░░░░░░░"
    }
    private fun portName(port: Int): String = when (port) {
        22 -> "SSH"
        53 -> "DNS"
        80 -> "HTTP"
        443 -> "HTTPS"
        445 -> "SMB"
        1883 -> "MQTT"
        5555 -> "TCP-LOG"
        8080 -> "HTTP-ALT"
        8123 -> "Home Assistant"
        else -> "TCP"
    }
    @Suppress("DEPRECATION")
    private fun wifiLine(r: android.net.wifi.ScanResult): String {
        val ssid = (r.SSID ?: "").ifBlank { "<hidden>" }
        val sec = wifiSecurityLabel(r.capabilities ?: "")
        val band = wifiBand(r.frequency)
        val ch = wifiChannel(r.frequency)
        val quality = wifiQuality(r.level)
        val bar = signalBar(r.level)
        return "📶 ssid=$ssid  bssid=${r.BSSID}  signal=${r.level} dBm $quality $bar  🔐 sec=$sec  band=$band  ch=$ch  freq=${r.frequency} MHz  features=${r.capabilities}"
    }
    private fun scanWifiLiveJson(params: Map<String,String>): String {
        val ctx = appContext ?: return JSONObject().put("rows", org.json.JSONArray()).put("error", "no context").toString()
        val filter = params["filter"]?.lowercase().orEmpty()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return JSONObject().put("rows", org.json.JSONArray()).put("error", "no wifi manager").toString()
        val startOk = runCatching { wifi.startScan() }.getOrDefault(false)
        Thread.sleep(350)
        val arr = org.json.JSONArray()
        runCatching { wifi.scanResults }.getOrDefault(emptyList()).map { r -> wifiLine(r) }
            .filter { filter.isBlank() || it.lowercase().contains(filter) }
            .sortedByDescending { it.substringAfter("rssi=").substringBefore(" ").trim().toIntOrNull() ?: -999 }
            .forEach { arr.put(it) }
        return JSONObject().put("startOk", startOk).put("rows", arr).toString()
    }
    private fun scanBleLiveJson(params: Map<String,String>): String {
        val ctx = appContext ?: return JSONObject().put("rows", org.json.JSONArray()).put("error", "no context").toString()
        val filter = params["filter"]?.lowercase().orEmpty()
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return JSONObject().put("rows", org.json.JSONArray()).put("error", "no scanner").toString()
        val results = linkedMapOf<String,String>()
        val latch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name ?: result.scanRecord?.deviceName ?: "" }.getOrDefault(result.scanRecord?.deviceName ?: "")
                val address = result.device.address ?: "?"
                val quality = wifiQuality(result.rssi)
                val bar = signalBar(result.rssi)
                val candidate = if (name.contains("casam", true) || name.contains("casambi", true)) "  🌴 Casambi candidate" else ""
                val line = "🔵 name=${name.ifBlank { "-" }}  mac=$address  signal=${result.rssi} dBm $quality $bar$candidate"
                if (filter.isBlank() || line.lowercase().contains(filter)) results[address] = line
            }
            override fun onScanFailed(errorCode: Int) { results["error"] = "BLE Scan Fehler code=$errorCode"; latch.countDown() }
        }
        runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
        latch.await(900, TimeUnit.MILLISECONDS)
        runCatching { scanner.stopScan(cb) }
        val arr = org.json.JSONArray()
        results.values.sortedBy { it.substringAfter("rssi=").substringBefore(" ").trim().toIntOrNull() ?: -999 }.reversed().forEach { arr.put(it) }
        return JSONObject().put("rows", arr).toString()
    }
    private fun scanNetworkWeb(params: Map<String,String>): String {
        val input = params["range"]?.trim().orEmpty().ifBlank { currentSubnet24Web() }
        val base = input.substringBefore('/').substringBeforeLast('.', "192.168.1")
        val ports = webPortList(params["ports"].orEmpty())
        val rows = mutableListOf<String>()
        for (i in 1..254) {
            val ip = "$base.$i"
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
            val open = if (ports.isEmpty()) emptyList() else ports.filter { webPortOpen(ip, it) }
            val reachable = if (ports.isEmpty()) runCatching { addr.isReachable(180) }.getOrDefault(false) else open.isNotEmpty() || runCatching { addr.isReachable(120) }.getOrDefault(false)
            if (reachable) {
                val name = runCatching { addr.canonicalHostName }.getOrDefault("-")
                val mac = arpMacForWeb(ip)
                val portText = if (ports.isEmpty()) "not scanned" else if (open.isEmpty()) "-" else open.joinToString(",")
                rows += "$ip  name=${if (name == ip) "-" else name}  mac=$mac  ports=$portText"
            }
        }
        return page("Network Scan", "<section class='card wide'><h2>Network Scan</h2><pre>${esc(rows.joinToString("\n").ifBlank { "Keine Treffer" })}</pre><a class='btn ghost' href='/tools'>BACK</a></section>")
    }
    @Suppress("DEPRECATION")
    private fun scanWifiWeb(params: Map<String,String>): String {
        val ctx = appContext ?: return page("WiFi Scan", "No context")
        val filter = params["filter"]?.lowercase().orEmpty()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return page("WiFi Scan", "WiFi Manager nicht verfuegbar")
        runCatching { wifi.startScan() }
        Thread.sleep(1800)
        val rows = runCatching { wifi.scanResults }.getOrDefault(emptyList()).map { r -> wifiLine(r) }
            .filter { filter.isBlank() || it.lowercase().contains(filter) }
            .sortedByDescending { it.substringAfter("rssi=").substringBefore(" ").trim().toIntOrNull() ?: -999 }
        return page("WiFi Scan", "<section class='card wide'><h2>WiFi Scan</h2><pre>${esc(rows.joinToString("\n").ifBlank { "Keine Treffer. Android kann Web-/Background-WiFi-Scans blockieren." })}</pre><a class='btn ghost' href='/tools'>BACK</a></section>")
    }
    private fun scanBleWeb(params: Map<String,String>): String {
        val ctx = appContext ?: return page("BLE Scan", "No context")
        val filter = params["filter"]?.lowercase().orEmpty()
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return page("BLE Scan", "Bluetooth Scanner nicht verfuegbar")
        val results = linkedMapOf<String,String>()
        val latch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name ?: result.scanRecord?.deviceName ?: "" }.getOrDefault(result.scanRecord?.deviceName ?: "")
                val address = result.device.address ?: "?"
                val quality = wifiQuality(result.rssi)
                val bar = signalBar(result.rssi)
                val candidate = if (name.contains("casam", true) || name.contains("casambi", true)) "  🌴 Casambi candidate" else ""
                val line = "🔵 name=${name.ifBlank { "-" }}  mac=$address  signal=${result.rssi} dBm $quality $bar$candidate"
                if (filter.isBlank() || line.lowercase().contains(filter)) results[address] = line
            }
            override fun onScanFailed(errorCode: Int) { results["error"] = "BLE Scan Fehler code=$errorCode"; latch.countDown() }
        }
        runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
        latch.await(6, TimeUnit.SECONDS)
        runCatching { scanner.stopScan(cb) }
        val rows = results.values.sortedBy { it.substringAfter("rssi=").substringBefore(" ").trim().toIntOrNull() ?: -999 }.reversed()
        return page("BLE Scan", "<section class='card wide'><h2>Bluetooth Scan</h2><pre>${esc(rows.joinToString("\n").ifBlank { "Keine Treffer" })}</pre><a class='btn ghost' href='/tools'>BACK</a></section>")
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

    private fun dashboard(message: String, section: String = "home"): String {
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
  <div class='sub'>${esc(c.casambiNetworkName.ifBlank { "Bridge Control Center" })} - powered by Sambesi - v0.10.0</div>
  <div class='msg'>${esc(message)}</div>
</div>
<div class='grid'>
  <section class='card'><h2>Live Status</h2><div id='statusGrid' class='statusGrid'>Lade Status...</div><div class='wsHint'>Live Mode: <b>${if (c.webSocketLiveEnabled) "WebSocket" else "Polling"}</b></div><script>window.CASAMBI_WS=${c.webSocketLiveEnabled};</script><script>${statusScript()}</script></section>
  <section id='lightCard' class='card lightCard off'><h2>${esc(unitName)}</h2><div class='powerPanel'><div id='powerOrb' class='powerOrb'>OFF</div><div class='powerMeta'><div id='lightStateText' class='stateTitle'>Licht aus</div><div id='brightnessText' class='stateSub'>Brightness 0%</div><div class='bar'><span id='brightnessBar'></span></div><div class='sliderWrap'><input id='brightnessSlider' class='jungleSlider' type='range' min='0' max='255' value='0'><div class='sliderMeta'><span>0%</span><b id='sliderValue'>0%</b><span>100%</span></div></div></div></div><div class='controlRow'><a id='cmdOn' class='cmdBtn onCmd' href='/command?state=ON'>ON</a><a id='cmdOff' class='cmdBtn offCmd active' href='/command?state=OFF'>OFF</a><a id='cmd40' class='cmdBtn dimCmd' href='/command?state=ON&brightness=102'>40%</a></div></section>
  <section class='card sceneCard'><h2>Szenen</h2><div class='activeScene'><span>Aktive Szene</span><b id='activeSceneName'>keine</b></div><div class='controlRow'>$sceneButtons</div></section>
  <section class='card wide'><h2>Live Log Preview</h2><pre id='logBox'>Lade Log...</pre><script>${logScript()}</script></section>
</div>
""")
    }

    private fun toolsHomePage(): String = page("Tools", """
<section class='hero card wide glassHero'><h1>Tools</h1><p class='muted'>Scanner und Diagnose direkt im Webinterface. Network Live Scanner hat eine eigene dynamische Seite.</p><a class='btn' href='/scanners'>OPEN LIVE SCANNERS</a></section>
<section class='card wide'><h2>Network Scanner</h2><form class='toolForm' action='/scan-network'><label>Range /24 Basis oder CIDR</label><input name='range' placeholder='leer = aktuelles /24'><label>Ports: none, known, all oder custom comma list</label><input name='ports' value='known'><button class='btn'>SCAN NETWORK</button></form></section>
<section class='card wide'><h2>WiFi Scanner</h2><form class='toolForm' action='/scan-wifi'><label>Filter SSID/BSSID</label><input name='filter'><button class='btn'>SCAN WIFI</button></form></section>
<section class='card wide'><h2>Bluetooth Scanner</h2><form class='toolForm' action='/scan-ble'><label>Filter Name/MAC</label><input name='filter'><button class='btn'>SCAN BLE</button></form></section>
""")
    private fun scanToolsPage(): String = toolsHomePage()
    private fun scanHistoryPage(): String = page("Scan History Pro", """
<section class='hero card wide glassHero'><h1>Scan History Pro</h1><p class='muted'>Letzte 10 Web-Scans mit Kartenansicht, Schnellfiltern, Delete, Copy und TXT Export.</p></section>
<section class='card wide historyTools'>
  <input id='historyFilter' placeholder='Filter: wifi, bluetooth, 192.168, Schnuffel, WPA2...' oninput='loadHistory()'>
  <button class='btn ghost' onclick='setQuickFilter("all")'>ALL</button>
  <button class='btn ghost' onclick='setQuickFilter("wifi")'>WiFi</button>
  <button class='btn ghost' onclick='setQuickFilter("network")'>Network</button>
  <button class='btn ghost' onclick='setQuickFilter("bluetooth")'>Bluetooth</button>
  <button class='btn ghost' onclick='setQuickFilter("casambi")'>Casambi</button>
  <button class='btn ghost' onclick='setQuickFilter("wpa2")'>WPA2</button>
  <button class='btn ghost' onclick='setQuickFilter("enterprise")'>Enterprise</button>
  <button class='btn ghost' onclick='setQuickFilter("open")'>Open</button>
  <button class='btn ghost' onclick='setQuickFilter("excellent")'>Strong</button>
  <button class='btn ghost' onclick='setQuickFilter("weak")'>Weak</button>
  <button class='btn ghost' onclick='setQuickFilter("host=")'>Hosts</button>
  <button class='btn ghost' onclick='expandHistory()'>EXPAND ALL</button>
  <button class='btn ghost' onclick='collapseHistory()'>COLLAPSE ALL</button>
  <button class='btn danger' onclick='clearHistory()'>CLEAR HISTORY</button>
</section>
<section class='card wide'><h2>Letzte Scans</h2><div id='historyBox' class='historyBox'>Lade History...</div></section>
<script>
function escHtml(v){return String(v||'').replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
function classifyLine(line){if(line.indexOf('ssid=')>=0)return 'wifi';if(line.indexOf('host=')>=0)return 'network';if(line.indexOf('mac=')>=0&&line.indexOf('signal=')>=0)return 'bluetooth';return 'log';}
function quickSummary(lines){const wifi=lines.filter(function(x){return x.indexOf('ssid=')>=0}).length;const net=lines.filter(function(x){return x.indexOf('host=')>=0}).length;const ble=lines.filter(function(x){return x.indexOf('mac=')>=0&&x.indexOf('signal=')>=0}).length;const cas=lines.filter(function(x){return x.toLowerCase().indexOf('casambi')>=0}).length;const best=(lines.join(' ').match(/signal=(-?\d+) dBm/g)||[]).map(function(v){return parseInt(v.replace(/[^\-0-9]/g,''));}).filter(function(v){return !isNaN(v);}).sort(function(a,b){return b-a})[0];return {wifi:wifi,net:net,ble:ble,cas:cas,best:best};}
function lineCard(line){const cls=classifyLine(line);let icon=cls==='wifi'?'📶':cls==='network'?'🟢':cls==='bluetooth'?'🔵':'▸';return '<div class="histLine '+cls+'"><b>'+icon+'</b><span>'+escHtml(line)+'</span></div>';}
function getHistory(){try{return JSON.parse(localStorage.scanHistory||'[]')}catch(e){return []}}
function setHistory(arr){localStorage.scanHistory=JSON.stringify(arr.slice(0,10));}
function deleteHistory(index){let arr=getHistory();arr.splice(index,1);setHistory(arr);loadHistory();}
function copyHistory(index){let h=getHistory()[index];if(!h)return;navigator.clipboard&&navigator.clipboard.writeText(h.text||'');}
function exportHistory(index){let h=getHistory()[index];if(!h)return;let blob=new Blob([h.text||''],{type:'text/plain'});let a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='casambi_scan_'+String(h.type||'scan')+'_'+String(h.time||'').replace(/[^0-9A-Za-z_-]/g,'_')+'.txt';a.click();setTimeout(function(){URL.revokeObjectURL(a.href)},1200);}
function clearHistory(){setHistory([]);loadHistory();}
function expandHistory(){document.querySelectorAll('.historyItem').forEach(function(d){d.open=true;});}
function collapseHistory(){document.querySelectorAll('.historyItem').forEach(function(d){d.open=false;});}
function setQuickFilter(v){document.getElementById('historyFilter').value=(v==='all')?'':v;loadHistory();}
function loadHistory(){const box=document.getElementById('historyBox');const f=(document.getElementById('historyFilter')?.value||'').toLowerCase();let arr=getHistory();let view=arr.map(function(h,i){return {h:h,i:i}}).filter(function(o){let h=o.h;return !f || (String(h.type)+' '+String(h.time)+' '+String(h.text)).toLowerCase().indexOf(f)>=0;});if(!view.length){box.textContent='Keine passenden Scan-Eintraege gefunden.';return;}box.innerHTML=view.map(function(o){const h=o.h;const lines=String(h.text||'').split('\n').filter(Boolean);const sum=quickSummary(lines);const body=lines.map(lineCard).join('');let best=sum.best?(' · Best '+sum.best+' dBm'):'';return "<details class='historyItem'><summary><span>"+escHtml(h.time)+"</span><b>"+escHtml(h.type)+"</b><em>"+lines.length+" Zeilen · WiFi "+sum.wifi+" · Hosts "+sum.net+" · BLE "+sum.ble+" · Casambi "+sum.cas+best+"</em><button onclick='event.preventDefault();event.stopPropagation();deleteHistory("+o.i+")'>🗑 Delete</button><button onclick='event.preventDefault();event.stopPropagation();copyHistory("+o.i+")'>Copy</button><button onclick='event.preventDefault();event.stopPropagation();exportHistory("+o.i+")'>TXT</button></summary><div class='historyTree'>"+body+"</div></details>";}).join('');}
loadHistory();
</script>
""")
    private fun checked(value: Boolean): String = if (value) " checked" else ""
    private fun settingsWebPage(): String {
        val ctx = appContext ?: return page("Settings", "No context")
        val c = ConfigStore.load(ctx)
        return page("Settings", """
<section class='hero card wide glassHero'><h1>Settings</h1><p class='muted'>Bridge-Einstellungen direkt im Webinterface bearbeiten. Schalter speichern mit SAVE SETTINGS.</p></section>
<section class='card wide settingsGrid'>
<form class='settingsForm' action='/settings-save'>
  <h2>Bridge Toggles</h2>
  <label class='switchLine'><span>MQTT Mode</span><input type='checkbox' name='mqttEnabled'${checked(c.mqttEnabled)}><b></b></label>
  <label class='switchLine'><span>Direct Mode</span><input type='checkbox' name='directModeEnabled'${checked(c.directModeEnabled)}><b></b></label>
  <label class='switchLine'><span>Web Interface</span><input type='checkbox' name='webInterfaceEnabled'${checked(c.webInterfaceEnabled)}><b></b></label>
  <label class='switchLine'><span>SMB Logging</span><input type='checkbox' name='smbDebugEnabled'${checked(c.smbDebugEnabled)}><b></b></label>
  <label class='switchLine'><span>TCP Logstream</span><input type='checkbox' name='tcpLogEnabled'${checked(c.tcpLogEnabled)}><b></b></label>
  <label class='switchLine'><span>WebSocket Live</span><input type='checkbox' name='webSocketLiveEnabled'${checked(c.webSocketLiveEnabled)}><b></b></label>
  <label class='switchLine'><span>Network Discovery / mDNS</span><input type='checkbox' name='networkDiscoveryEnabled'${checked(c.networkDiscoveryEnabled)}><b></b></label>
  <label class='switchLine'><span>Auto API Fetch</span><input type='checkbox' name='autoApiFetchEnabled'${checked(c.autoApiFetchEnabled)}><b></b></label>
  <label class='switchLine'><span>Autostart Bridge</span><input type='checkbox' name='autoStartEnabled'${checked(c.autoStartEnabled)}><b></b></label>
  <h2>MQTT</h2>
  <label>MQTT Host</label><input name='mqttHost' value='${esc(c.mqttHost)}'>
  <label>MQTT Port</label><input name='mqttPort' value='${c.mqttPort}'>
  <label>MQTT User</label><input name='mqttUser' value='${esc(c.mqttUser)}'>
  <label>MQTT Password</label><input name='mqttPassword' value='${esc(c.mqttPassword)}' type='password'>
  <label>Base Topic</label><input name='baseTopic' value='${esc(c.baseTopic)}'>
  <label>Discovery Prefix</label><input name='discoveryPrefix' value='${esc(c.discoveryPrefix)}'>
  <h2>Web / Logs</h2>
  <label>Web Port</label><input name='webInterfacePort' value='${c.webInterfacePort}'>
  <label>TCP Log Port</label><input name='tcpLogPort' value='${c.tcpLogPort}'>
  <h2>SMB</h2>
  <label>SMB Server</label><input name='smbServer' value='${esc(c.smbServer)}'>
  <label>SMB Share</label><input name='smbShare' value='${esc(c.smbShare)}'>
  <label>SMB Path</label><input name='smbPath' value='${esc(c.smbPath)}'>
  <label>SMB Domain</label><input name='smbDomain' value='${esc(c.smbDomain)}'>
  <label>SMB User</label><input name='smbUser' value='${esc(c.smbUser)}'>
  <label>SMB Password</label><input name='smbPassword' value='${esc(c.smbPassword)}' type='password'>
  <button class='btn saveBtn'>SAVE SETTINGS</button>
</form>
</section>
""")
    }
    private fun saveSettingsWeb(params: Map<String,String>): String {
        val ctx = appContext ?: return page("Settings", "No context")
        val c = ConfigStore.load(ctx)
        fun enabled(name: String) = params.containsKey(name)
        fun str(name: String, old: String) = params[name] ?: old
        fun int(name: String, old: Int) = params[name]?.toIntOrNull() ?: old
        val updated = c.copy(
            mqttEnabled = enabled("mqttEnabled"),
            directModeEnabled = enabled("directModeEnabled"),
            webInterfaceEnabled = enabled("webInterfaceEnabled"),
            smbDebugEnabled = enabled("smbDebugEnabled"),
            tcpLogEnabled = enabled("tcpLogEnabled"),
            webSocketLiveEnabled = enabled("webSocketLiveEnabled"),
            networkDiscoveryEnabled = enabled("networkDiscoveryEnabled"),
            autoApiFetchEnabled = enabled("autoApiFetchEnabled"),
            autoStartEnabled = enabled("autoStartEnabled"),
            mqttHost = str("mqttHost", c.mqttHost).trim(),
            mqttPort = int("mqttPort", c.mqttPort).coerceIn(1, 65535),
            mqttUser = str("mqttUser", c.mqttUser),
            mqttPassword = str("mqttPassword", c.mqttPassword),
            baseTopic = str("baseTopic", c.baseTopic).ifBlank { "casambi_bridge" },
            discoveryPrefix = str("discoveryPrefix", c.discoveryPrefix).ifBlank { "homeassistant" },
            webInterfacePort = int("webInterfacePort", c.webInterfacePort).coerceIn(1024, 65535),
            tcpLogPort = int("tcpLogPort", c.tcpLogPort).coerceIn(1024, 65535),
            smbServer = str("smbServer", c.smbServer).trim(),
            smbShare = str("smbShare", c.smbShare).trim(),
            smbPath = str("smbPath", c.smbPath).trim().ifBlank { "casambi_debug" },
            smbDomain = str("smbDomain", c.smbDomain).trim(),
            smbUser = str("smbUser", c.smbUser),
            smbPassword = str("smbPassword", c.smbPassword)
        )
        ConfigStore.save(ctx, updated)
        DebugExporter.configure(updated)
        TcpLogServer.configure(updated)
        WebControlServer.configure(ctx, updated)
        ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
        LogBus.log("Web Settings gespeichert")
        return page("Settings saved", "<section class='card wide glassHero'><h1>Settings gespeichert</h1><p class='muted'>Bridge wurde mit aktualisierten Web-Einstellungen neu konfiguriert.</p><a class='btn' href='/settings'>BACK SETTINGS</a><a class='btn ghost' href='/'>DASHBOARD</a></section>")
    }
    private fun logsPage(): String = page("Live Log", """
<div class='toolbar'><a class='btn ghost' href='/'>HOME</a><a class='btn ghost' href='/logs.txt'>RAW LOG</a></div>
<section class='card wide'><h2>Live Log</h2><pre id='logBox'>Lade Log...</pre><script>${logScript()}</script></section>
""")

    private fun page(title: String, body: String): String = """
<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><title>${esc(title)}</title>
<style>
:root{--bg:#02100d;--panel:#06251eee;--line:#1ddca866;--leaf:#20e69a;--lime:#91f36d;--teal:#22b8a8;--amber:#ffcc66;--violet:#4d8dff;--danger:#ff4d7d;--text:#eafff4;--muted:#9bd7c0}
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:var(--text);font-family:Consolas,Monaco,monospace;background:radial-gradient(circle at 12% 0%,#0b3d34 0,#031a15 38%,#010705 100%);overflow-x:hidden}
.wrap{max-width:none;margin:0;padding:18px 22px;overflow-x:hidden}.pinBtn{position:fixed;right:18px;top:18px;z-index:5;background:#063d32;color:var(--leaf);border:1px solid var(--line);border-radius:14px;padding:11px 14px;font-family:inherit;font-weight:900}.hamb{position:fixed;left:14px;top:14px;z-index:5;background:#063d32;border:1px solid var(--line);border-radius:14px;color:var(--leaf);padding:12px 16px;cursor:pointer;box-shadow:0 0 18px #20e69a44}.drawer{position:fixed;left:0;top:0;bottom:0;width:252px;z-index:4;background:linear-gradient(180deg,#06251e,#02100d);border-right:1px solid var(--line);padding:70px 16px 16px;transition:left .28s ease;box-shadow:0 0 40px #000}.drawer h2{color:var(--leaf)}.drawer a{display:block;margin:8px 0;padding:12px;border:1px solid var(--line);border-radius:14px;color:var(--leaf);text-decoration:none;background:#031c16;transition:transform .18s ease,background .18s ease}.drawer a:hover{background:#0a3e31;transform:translateX(5px)}.stage{margin-left:252px;transition:margin-left .28s ease,transform .28s ease;min-width:0}.unpinned .drawer{left:-272px}.unpinned .stage{margin-left:58px}#drawerToggle:checked~.drawer{left:0}#drawerToggle:checked~.stage{margin-left:252px}.scannerGrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:18px}.scannerPanel{border:1px solid var(--line);border-radius:20px;background:#031a14;padding:16px}.terminal{min-height:320px;max-height:520px}.smallTerminal{min-height:260px}.progress{height:10px;background:#010b08;border:1px solid var(--line);border-radius:999px;overflow:hidden;margin:12px 0}.progress span{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--leaf),var(--lime),var(--teal));box-shadow:0 0 18px var(--leaf)}.glassHero{background:linear-gradient(135deg,#073229dd,#02100ddd)!important}.settingsGrid{max-width:940px}.settingsForm{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px 18px}.settingsForm h2{grid-column:1/-1;margin-top:18px}.settingsForm input{background:#010b08;color:var(--text);border:1px solid var(--line);border-radius:12px;padding:12px;font-family:inherit}.settingsForm label{color:var(--lime);font-weight:800}.switchLine{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;border:1px solid var(--line);border-radius:16px;padding:10px 12px;background:#031c16}.switchLine input{display:none}.switchLine b{width:54px;height:28px;border-radius:999px;background:#06241d;border:1px solid var(--line);position:relative}.switchLine b:before{content:"";position:absolute;width:22px;height:22px;left:3px;top:2px;border-radius:50%;background:#315b4f;transition:left .18s ease,background .18s ease}.switchLine input:checked+b:before{left:27px;background:var(--leaf);box-shadow:0 0 16px var(--leaf)}.saveBtn{grid-column:1/-1;max-width:260px}.historyItem{border:1px solid var(--line);border-radius:16px;margin:10px 0;padding:10px;background:#031c16}.historyItem summary{cursor:pointer;color:var(--leaf);font-weight:900}.scanCards{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:10px;margin-top:12px}.scanCard{border:1px solid var(--line);border-radius:16px;background:linear-gradient(135deg,#031c16,#010b08);padding:12px;box-shadow:0 0 18px #0008}.cardTop{display:flex;align-items:center;justify-content:space-between;gap:10px}.cardTop b{color:var(--leaf);font-size:15px}.badge{border:1px solid var(--line);border-radius:999px;padding:4px 8px;font-size:12px;font-weight:900}.secWpa3{background:#073b46;color:#6ffcff}.secEnterprise{background:#24144c;color:#b79cff}.secWpa2{background:#063d29;color:#80ff9f}.secOpen,.secWeak{background:#4c180f;color:#ff9f80}.meta{color:var(--muted);margin:8px 0}.signal{display:flex;justify-content:space-between;gap:12px}.signal i{font-style:normal;color:#eafff4;text-shadow:0 0 10px var(--leaf)}.features{margin-top:8px;color:#cdeee0;font-size:12px;word-break:break-word}.historyTools input{min-width:360px;max-width:720px;width:50%;background:#010b08;color:var(--text);border:1px solid var(--line);border-radius:12px;padding:12px;font-family:inherit;margin-right:12px}.historyTools .btn{margin:4px 6px 4px 0}.historyTree{display:grid;gap:8px;margin-top:12px}.histLine{border-left:4px solid var(--line);background:#010b08;border-radius:10px;padding:9px 10px;white-space:pre-wrap}.histLine.wifi{border-left-color:#21d6a5}.histLine.network{border-left-color:#80ff9f}.histLine.bluetooth{border-left-color:#4d8dff}.historyItem summary{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.historyItem summary b{color:var(--lime)}.historyItem summary em{color:var(--muted);font-style:normal}.historyItem summary button{margin-left:auto;background:#06251e;color:var(--leaf);border:1px solid var(--line);border-radius:10px;padding:6px 9px;font-family:inherit;font-weight:900}.historyItem summary button+button{margin-left:6px}.histLine{display:flex;gap:10px;align-items:flex-start}.histLine b{min-width:25px}.analysisBox{border:1px solid var(--line);border-radius:16px;background:#02100d;padding:12px;margin:12px 0}.analysisGrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px}.chan{display:grid;grid-template-columns:70px 1fr 55px;gap:8px;align-items:center;margin:6px 0}.chan span{height:12px;border:1px solid var(--line);border-radius:999px;overflow:hidden;background:#010b08}.chan i{display:block;height:100%;background:linear-gradient(90deg,var(--leaf),var(--lime),var(--teal));box-shadow:0 0 12px var(--leaf)}.apLine{border-left:3px solid var(--teal);padding:7px 9px;margin:5px 0;background:#010b08;border-radius:8px}.apLine b{display:block;color:var(--leaf)}.switchInline{display:flex!important;align-items:center;gap:7px;color:var(--lime);font-weight:800;margin:8px 0}.candidateRow{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0}.portBadge{display:inline-block;margin:2px;padding:4px 7px;border-radius:999px;border:1px solid var(--line);background:#031c16;color:var(--text);font-size:12px}.portBadge.mqtt,.badge.mqtt{background:#24210d;color:#ffdf7e}.portBadge.smb,.badge.smb{background:#132036;color:#8fbaff}.portBadge.ha,.badge.ha{background:#10351f;color:#80ff9f}.portBadge.http,.badge.http{background:#073b46;color:#6ffcff}.casambiCard{border-color:#91f36d;box-shadow:0 0 18px #91f36d55}.hamb{position:fixed;left:14px;top:14px;z-index:4;background:#053b31;border:1px solid var(--line);border-radius:14px;color:var(--leaf);padding:12px 16px;cursor:pointer;box-shadow:0 0 18px #20e69a44}.drawer{position:fixed;left:-280px;top:0;bottom:0;width:260px;z-index:3;background:linear-gradient(180deg,#06251e,#02100d);border-right:1px solid var(--line);padding:70px 16px 16px;transition:left .28s ease;box-shadow:0 0 40px #000}.drawer h2{color:var(--leaf)}.drawer a{display:block;margin:8px 0;padding:12px;border:1px solid var(--line);border-radius:14px;color:var(--leaf);text-decoration:none;background:#031c16}.drawer a:hover{background:#093b2e;transform:translateX(4px)}#drawerToggle:checked~.drawer{left:0}#drawerToggle:checked~.stage{transform:translateX(270px);max-width:calc(100% - 280px)}.stage{transition:transform .28s ease}.toolForm{display:grid;gap:10px;max-width:640px}.toolForm input,.scannerPanel input,.scannerPanel select{background:#010b08;color:var(--text);border:1px solid var(--line);border-radius:12px;padding:10px;font-family:inherit;margin:4px 0 10px}.scannerPanel label{display:block;color:var(--lime);font-weight:700}.toolForm input{background:#010b08;color:var(--text);border:1px solid var(--line);border-radius:12px;padding:12px;font-family:inherit}.toolForm label{color:var(--lime);font-weight:700}.hero,.card{border:1px solid var(--line);border-radius:24px;background:linear-gradient(180deg,#082216e8,#03100ae8);box-shadow:0 0 24px #14f19522;padding:16px;margin-bottom:16px}h1{margin:0;color:var(--leaf);font-size:28px;text-shadow:0 0 18px #14f195aa}h2{margin:0 0 12px;color:#21d6a5;font-size:18px}.sub,.muted{color:var(--muted)}.msg{margin-top:10px;color:var(--lime)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}.wide{grid-column:1/-1}.btn{display:inline-flex;align-items:center;justify-content:center;margin:5px;padding:12px 15px;border-radius:15px;background:linear-gradient(135deg,var(--leaf),var(--lime));color:#001208;text-decoration:none;font-weight:900;border:0}.ghost{background:linear-gradient(135deg,#0e2b1e,#10351f);color:var(--leaf);border:1px solid var(--line)}.danger{background:linear-gradient(135deg,var(--danger),#ff9bb8)}.amber{background:linear-gradient(135deg,var(--amber),#e0a326)}.mini{padding:7px 10px;font-size:11px}.statusGrid{display:grid;gap:8px}.pill{display:flex;justify-content:space-between;gap:10px;border:1px solid #14f19533;border-radius:12px;padding:8px;background:#020a0788}.ok{color:var(--lime)}.bad,.dangerText{color:var(--danger)}.controlRow{display:flex;flex-wrap:wrap}.toolbar{margin:0 0 14px}.file{display:grid;grid-template-columns:1fr auto auto;gap:10px;align-items:center;border:1px solid #14f19533;border-radius:12px;padding:9px;margin:7px 0;background:#020a0788}pre{white-space:pre-wrap;word-break:break-word;max-height:420px;overflow:auto;background:#020a07;border:1px solid #14f19533;border-radius:16px;padding:13px;color:var(--text)}.lightCard.on{border-color:#55ff85;box-shadow:0 0 34px #14f19566}.lightCard.off{border-color:#176343;box-shadow:0 0 18px #000}.powerPanel{display:flex;gap:16px;align-items:center;margin:6px 0 16px}.powerOrb{width:92px;height:92px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:900;letter-spacing:1px;background:#06140d;border:2px solid #24543d;color:var(--muted);box-shadow:inset 0 0 24px #000}.lightCard.on .powerOrb{background:radial-gradient(circle,#b6ff4d 0,#14f195 45%,#004f2f 100%);color:#001208;box-shadow:0 0 32px #14f195aa,inset 0 0 18px #ffffff77}.lightCard.off .powerOrb{background:radial-gradient(circle,#221018 0,#0c090b 65%,#040404 100%);border-color:#ff4d7d55;color:#ff7fa0;box-shadow:0 0 20px #ff4d7d33}.powerMeta{flex:1}.stateTitle{font-size:22px;font-weight:900;color:var(--leaf)}.lightCard.off .stateTitle{color:var(--danger)}.stateSub{margin-top:4px;color:var(--muted)}.bar{height:12px;background:#03110b;border:1px solid #14f19544;border-radius:999px;overflow:hidden;margin-top:12px}.bar span{display:block;height:100%;width:0%;background:linear-gradient(90deg,var(--leaf),var(--lime),var(--teal));box-shadow:0 0 15px #14f195}.cmdBtn,.sceneBtn{display:inline-flex;align-items:center;justify-content:center;gap:8px;margin:5px;padding:13px 17px;border-radius:15px;text-decoration:none;font-weight:900;border:1px solid #14f19555;color:var(--leaf);background:#06180f}.cmdBtn.active,.sceneBtn.active{transform:translateY(-1px);box-shadow:0 0 24px #14f19588}.onCmd.active{background:linear-gradient(135deg,#14f195,#b6ff4d);color:#001208}.offCmd.active{background:linear-gradient(135deg,#ff4d7d,#ff9bb8);color:#190006}.dimCmd.active{background:linear-gradient(135deg,#ffcc66,#ffea92);color:#1e1200}.sceneBtn{min-width:88px;min-height:54px;flex-direction:column}.sceneDot{width:13px;height:13px;border-radius:50%;background:#136943;box-shadow:0 0 10px #14f19544}.sceneBtn.active{background:linear-gradient(135deg,#8a5cf6,#14f195);color:#001208;border-color:#b6ff4d}.sceneBtn.active .sceneDot{background:#fff;box-shadow:0 0 18px #fff}.activeScene{border:1px solid #8a5cf655;border-radius:16px;background:#050c12;padding:12px;margin-bottom:12px}.activeScene span{display:block;color:var(--muted);font-size:12px}.activeScene b{display:block;color:var(--violet);font-size:24px;margin-top:3px;text-shadow:0 0 16px #8a5cf688}.sliderWrap{margin-top:15px}.jungleSlider{width:100%;appearance:none;background:transparent;cursor:pointer}.jungleSlider::-webkit-slider-runnable-track{height:14px;border-radius:999px;background:linear-gradient(90deg,#173423,#14f195,#b6ff4d,#00e5ff);border:1px solid #14f19566;box-shadow:0 0 14px #14f19533}.jungleSlider::-webkit-slider-thumb{appearance:none;width:28px;height:28px;border-radius:50%;background:radial-gradient(circle,#ffffff 0,#b6ff4d 35%,#14f195 70%,#00663c 100%);border:2px solid #eafff4;margin-top:-8px;box-shadow:0 0 22px #14f195,0 0 8px #ffffff}.jungleSlider::-moz-range-track{height:14px;border-radius:999px;background:linear-gradient(90deg,#173423,#14f195,#b6ff4d,#00e5ff);border:1px solid #14f19566}.jungleSlider::-moz-range-thumb{width:28px;height:28px;border-radius:50%;background:#14f195;border:2px solid #eafff4;box-shadow:0 0 18px #14f195}.sliderMeta{display:flex;justify-content:space-between;color:var(--muted);font-size:12px;margin-top:7px}.sliderMeta b{color:var(--lime);font-size:14px;text-shadow:0 0 10px #b6ff4d}.wsHint{color:var(--muted);font-size:12px;margin-top:10px}.wsHint b{color:var(--lime)}
</style></head><body><button id='pinBtn' class='pinBtn'>HIDE SIDEBAR</button><input id='drawerToggle' type='checkbox' hidden><label class='hamb' for='drawerToggle'>☰</label><aside class='drawer'><h2>CASAMBI JUNGLE</h2><a href='/'>Dashboard</a><a href='/lights'>Lichter & Szenen</a><a href='/scanners'>Scanners</a><a href='/history'>Scan History</a><a href='/tools'>Tools</a><a href='/settings'>Settings</a><a href='/files'>SMB Browser</a><a href='/logs'>Live Log</a></aside><main class='stage'><div class='wrap'>$body</div></main><script>const b=document.body; if(localStorage.sidebarPinned==='0') b.classList.add('unpinned'); const pb=document.getElementById('pinBtn');function syncPin(){pb.textContent=b.classList.contains('unpinned')?'SHOW SIDEBAR':'HIDE SIDEBAR'};syncPin();pb.onclick=()=>{b.classList.toggle('unpinned');localStorage.sidebarPinned=b.classList.contains('unpinned')?'0':'1';syncPin()};</script></body></html>
""".trimIndent()

    private fun statusScript(): String = """
let sliderBusy=false;let sliderTimer=null;let pollTimer=null;let ws=null;
function applyLightVisual(s){const on=s.state==='ON'&&s.brightness>0;const pct=s.brightnessPct||0;const card=document.getElementById('lightCard');card.classList.toggle('on',on);card.classList.toggle('off',!on);document.getElementById('powerOrb').textContent=on?'ON':'OFF';document.getElementById('lightStateText').textContent=on?'Licht aktiv':'Licht aus';document.getElementById('brightnessText').textContent='Brightness '+pct+'%';document.getElementById('brightnessBar').style.width=pct+'%';document.getElementById('cmdOn').classList.toggle('active',on);document.getElementById('cmdOff').classList.toggle('active',!on);document.getElementById('cmd40').classList.toggle('active',on&&pct>=39&&pct<=41);if(!sliderBusy){const sl=document.getElementById('brightnessSlider');sl.value=s.brightness||0;document.getElementById('sliderValue').textContent=pct+'%';}}
function renderStatus(s){const on=s.state==='ON'&&s.brightness>0;const pct=s.brightnessPct||0;const p=(n,v,ok)=>`<div class='pill'><span>${'$'}{n}</span><b class='${'$'}{ok?'ok':'bad'}'>${'$'}{v}</b></div>`;document.getElementById('statusGrid').innerHTML=p('Bridge',s.bridge,s.bridge==='online')+p('BLE',s.ble?'connected':'disconnected',s.ble)+p('MQTT',s.mqtt?'online':'offline',s.mqtt)+p('Cloud',s.cloud?'synced':'not synced',s.cloud)+p('Last Sync',s.lastSyncText||'not synced',(s.lastSync||0)>0)+p('Licht',s.state+' / '+pct+'%',on)+p('Szene',s.lastSceneName||'keine',!!s.lastSceneName)+p('Last Update',s.lastUpdateText||'never',s.lastUpdateText&&s.lastUpdateText!=='never')+p('Uptime',s.uptime,true)+p('Version',s.version,true);applyLightVisual(s);document.getElementById('activeSceneName').textContent=s.lastSceneName||'keine';document.querySelectorAll('.sceneBtn').forEach(el=>el.classList.toggle('active',String(s.lastSceneId)===String(el.dataset.scene)));}
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
