package de.pascal.casambibridge.bridge

import android.content.Context
import android.content.Intent
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.ACTION_COMMAND
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.EXTRA_BRIGHTNESS
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.EXTRA_STATE
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

object WebControlServer {
    private val lock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var runningPort: Int = -1
    @Volatile private var appContext: Context? = null

    fun configure(context: Context, config: BridgeConfig) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!config.webInterfaceEnabled) {
                stopLocked(true)
                return
            }
            val port = config.webInterfacePort.coerceIn(1024, 65535)
            if (serverSocket != null && runningPort == port) {
                LogBus.log("Webinterface laeuft bereits auf Port $port")
                return
            }
            stopLocked(false)
            startLocked(port)
        }
    }

    fun stop() { synchronized(lock) { stopLocked(true) } }

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
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isBlank()) break
                                }
                                val path = request.split(" ").getOrNull(1) ?: "/"
                                val response = handle(path)
                                writeResponse(s.getOutputStream(), response.first, response.second)
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
        return when (route) {
            "/", "/index.html" -> "text/html; charset=utf-8" to dashboard("Bereit")
            "/command" -> {
                val state = params["state"] ?: "ON"
                val brightness = params["brightness"]?.toIntOrNull()
                sendCommand(state, brightness)
                "text/html; charset=utf-8" to dashboard("Befehl gesendet: $state ${brightness ?: ""}")
            }
            "/save" -> {
                val ctx = appContext ?: return "text/plain" to "No context"
                val c = ConfigStore.load(ctx)
                val updated = c.copy(
                    casambiMac = params["casambiMac"] ?: c.casambiMac,
                    casambiKeyId = params["casambiKeyId"]?.toIntOrNull() ?: c.casambiKeyId,
                    casambiKeyHex = params["casambiKeyHex"] ?: c.casambiKeyHex,
                    mqttHost = params["mqttHost"] ?: c.mqttHost,
                    mqttPort = params["mqttPort"]?.toIntOrNull() ?: c.mqttPort,
                    mqttUser = params["mqttUser"] ?: c.mqttUser,
                    mqttPassword = params["mqttPassword"] ?: c.mqttPassword,
                    baseTopic = params["baseTopic"] ?: c.baseTopic,
                    discoveryPrefix = params["discoveryPrefix"] ?: c.discoveryPrefix,
                    smbDebugEnabled = params["smbDebugEnabled"]?.equals("true", true) ?: c.smbDebugEnabled,
                    smbServer = params["smbServer"] ?: c.smbServer,
                    smbShare = params["smbShare"] ?: c.smbShare,
                    smbPath = params["smbPath"] ?: c.smbPath,
                    smbDomain = params["smbDomain"] ?: c.smbDomain,
                    smbUser = params["smbUser"] ?: c.smbUser,
                    smbPassword = params["smbPassword"] ?: c.smbPassword,
                    webInterfaceEnabled = params["webInterfaceEnabled"]?.equals("true", true) ?: c.webInterfaceEnabled,
                    webInterfacePort = params["webInterfacePort"]?.toIntOrNull() ?: c.webInterfacePort,
                    autoApiFetchEnabled = params["autoApiFetchEnabled"]?.equals("true", true) ?: c.autoApiFetchEnabled
                )
                ConfigStore.save(ctx, updated)
                DebugExporter.configure(updated)
                configure(ctx, updated)
                "text/html; charset=utf-8" to dashboard("Konfiguration gespeichert")
            }
            "/status" -> {
                val json = JSONObject()
                    .put("state", RuntimeStatus.lastState)
                    .put("brightness", RuntimeStatus.lastBrightness)
                    .put("online", RuntimeStatus.lastOnline)
                    .put("raw", RuntimeStatus.lastRawState)
                    .toString()
                "application/json; charset=utf-8" to json
            }
            "/backup" -> {
                val ctx = appContext ?: return "text/plain" to "No context"
                val c = ConfigStore.load(ctx)
                val msg = try { "Backup gespeichert: ${ConfigBackup.exportToSmb(c)}" } catch (t: Throwable) { "Backup Fehler: ${t.message}" }
                "text/html; charset=utf-8" to dashboard(msg)
            }
            "/scene" -> {
                val id = params["id"]?.toIntOrNull() ?: -1
                val name = params["name"] ?: "Scene $id"
                if (id >= 0) sendScene(id, name)
                "text/html; charset=utf-8" to dashboard("Scene gesendet: $name")
            }
            "/fetch-api", "/scan" -> {
                val ctx = appContext ?: return "text/plain" to "No context"
                val c = ConfigStore.load(ctx)
                val msg = try {
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
                    ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
                    "API Fetch OK: ${result.rawSummary}. Bridge wird mit aktualisiertem Key neu gestartet."
                } catch (t: Throwable) {
                    "API Fetch Fehler: ${t.message}"
                }
                "text/html; charset=utf-8" to dashboard(msg)
            }
            else -> "text/html; charset=utf-8" to dashboard("Unbekannte Route: $route")
        }
    }

    private fun sendCommand(state: String, brightness: Int?) {
        val ctx = appContext ?: return
        val intent = Intent(ctx, CasambiBridgeService::class.java).apply {
            action = ACTION_COMMAND
            putExtra(EXTRA_STATE, state)
            if (brightness != null) putExtra(EXTRA_BRIGHTNESS, brightness)
        }
        ctx.startService(intent)
    }

    private fun sendScene(sceneId: Int, sceneName: String) {
        val ctx = appContext ?: return
        val intent = Intent(ctx, CasambiBridgeService::class.java).apply {
            action = CasambiBridgeService.ACTION_SCENE
            putExtra(CasambiBridgeService.EXTRA_SCENE_ID, sceneId)
            putExtra(CasambiBridgeService.EXTRA_SCENE_NAME, sceneName)
        }
        ctx.startService(intent)
    }

    private fun dashboard(message: String): String {
        val ctx = appContext
        val c = if (ctx != null) ConfigStore.load(ctx) else BridgeConfig()
        fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """
<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><title>Casambi Bridge Jungle Control</title>
<style>
:root{--bg:#04110c;--panel:#071c14cc;--line:#19ff9a70;--leaf:#14f195;--lime:#b6ff4d;--teal:#00e5ff;--amber:#ffcc66;--violet:#8a5cf6;--danger:#ff4d7d;--text:#eafff4;--muted:#8fbba5}
*{box-sizing:border-box}body{margin:0;min-height:100vh;color:var(--text);font-family:Consolas,Monaco,monospace;background:radial-gradient(circle at 20% 10%,#0b3822 0,#05170f 35%,#020805 100%);overflow-x:hidden}
body:before{content:"";position:fixed;inset:-20%;background:linear-gradient(115deg,transparent 0 12%,#1eff8a13 12% 14%,transparent 14% 28%,#00e5ff10 28% 30%,transparent 30% 100%);animation:canopy 18s linear infinite;pointer-events:none;z-index:-2}
body:after{content:"";position:fixed;inset:0;background:radial-gradient(circle at 80% 20%,#b6ff4d22,transparent 20%),radial-gradient(circle at 20% 90%,#00e5ff18,transparent 24%);filter:blur(1px);animation:pulse 6s ease-in-out infinite;pointer-events:none;z-index:-1}
@keyframes canopy{from{transform:translate3d(-4%,0,0) rotate(0deg)}to{transform:translate3d(4%,2%,0) rotate(1deg)}}@keyframes pulse{0%,100%{opacity:.55}50%{opacity:1}}@keyframes glow{0%,100%{box-shadow:0 0 16px #14f19533, inset 0 0 20px #00e5ff10}50%{box-shadow:0 0 34px #14f19588, inset 0 0 32px #b6ff4d14}}@keyframes scan{from{background-position:0 0}to{background-position:0 80px}}
.wrap{max-width:1120px;margin:auto;padding:24px}.hero{position:relative;border:1px solid var(--line);border-radius:28px;padding:22px;background:linear-gradient(135deg,#092417dd,#06100bdd);animation:glow 5s ease-in-out infinite;overflow:hidden}.hero:before{content:"";position:absolute;inset:0;background:repeating-linear-gradient(0deg,transparent 0 18px,#19ff9a0a 19px,#19ff9a0a 20px);animation:scan 8s linear infinite;pointer-events:none}h1{position:relative;margin:0;color:var(--leaf);font-size:28px;letter-spacing:1px;text-shadow:0 0 18px #14f195aa}.sub{position:relative;color:var(--muted);margin-top:6px}.badge{display:inline-block;border:1px solid var(--line);border-radius:999px;padding:8px 12px;margin:8px 8px 0 0;background:#001f12aa;color:var(--lime)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(235px,1fr));gap:16px;margin-top:18px}.card{border:1px solid #14f19555;border-radius:22px;padding:16px;background:linear-gradient(180deg,#082216d9,#03100adb);box-shadow:0 10px 30px #0008}.card h2{margin:0 0 12px;color:var(--teal);font-size:18px}.btn{display:inline-flex;align-items:center;justify-content:center;margin:6px;padding:13px 17px;background:linear-gradient(135deg,var(--leaf),var(--lime));color:#001208;text-decoration:none;border-radius:16px;font-weight:900;border:0;box-shadow:0 0 18px #14f19555;transition:.18s transform,.18s filter}.btn:hover{transform:translateY(-2px) scale(1.02);filter:saturate(1.3)}.danger{background:linear-gradient(135deg,var(--danger),#ff9bb8)}.ghost{background:linear-gradient(135deg,#0e2b1e,#10351f);color:var(--leaf);border:1px solid var(--line)}.ok{color:var(--lime)}.muted{color:var(--muted)}input{width:100%;background:#020a07;color:var(--text);border:1px solid #14f19555;border-radius:13px;padding:11px;margin:5px 0 11px;outline:none}input:focus{border-color:var(--teal);box-shadow:0 0 18px #00e5ff55}label{display:block;color:var(--lime);font-size:12px}.range{accent-color:var(--leaf)}.controlRow{display:flex;flex-wrap:wrap}.orb{width:12px;height:12px;border-radius:50%;display:inline-block;margin-right:8px;background:radial-gradient(circle,#14f195 0%,#008cff 50%,#8e48ff 100%);box-shadow:0 0 14px #008cff,0 0 22px #8e48ff;animation:pulse 1.4s ease-in-out infinite}.switchline{display:flex;align-items:center;gap:10px;margin:8px 0}.switchline input{width:auto;transform:scale(1.35);accent-color:var(--leaf);margin:0}.signalGrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:8px}.footer{margin-top:18px;color:var(--muted);font-size:12px}
</style></head>
<body><div class='wrap'><section class='hero'><h1>CASAMBI BRIDGE v0.3.1</h1><div class='sub'>Jungle Control Center • MQTT • BLE • SMB</div><span class='badge'><span class='orb'></span>WEB ONLINE</span><span class='badge'>PORT ${c.webInterfacePort}</span><span class='badge'>TOPIC ${esc(c.baseTopic)}</span></section>
<div class='grid'><section class='card'><h2>Unit 1 Control</h2><div class='muted'>$message</div><p><span class='orb'></span><b>Real State:</b> <span id='stateText'>${RuntimeStatus.lastState} • ${RuntimeStatus.lastBrightness}</span></p><div class='controlRow'><a class='btn' href='/command?state=ON'>ON</a><a class='btn danger' href='/command?state=OFF'>OFF</a><a class='btn' href='/command?state=ON&brightness=102'>40%</a></div><label>Auto Apply Brightness</label><input id='brightness' class='range' type='range' min='0' max='255' value='${RuntimeStatus.lastBrightness}' oninput='sliderChanged(this.value)'><div class='muted'>Slider sendet automatisch, ohne SET-Button.</div></section>
<section class='card'><h2>Signal LEDs</h2><div class='signalGrid'><p><span class='orb'></span>Casambi BLE RX</p><p><span class='orb'></span>Casambi BLE TX</p><p><span class='orb'></span>MQTT Eingang</p><p><span class='orb'></span>MQTT Ausgang</p><p><span class='orb'></span>SMB ${if (c.smbDebugEnabled) "ON" else "OFF"}</p><p><span class='orb'></span>Web ${if (c.webInterfaceEnabled) "ON" else "OFF"}</p></div><p><b>TX Core:</b> counter + 07 + operation</p><p><b>MQTT:</b> ${esc(c.mqttHost)}:${c.mqttPort}</p><a class='btn ghost' href='/backup'>BACKUP SMB</a></section></div><div class='grid'><section class='card'><h2>Scenes</h2>${sceneButtons(c)}</section><section class='card'><h2>API & Key</h2><p><span class='orb'></span>HEX-Key wird nach Fetch automatisch aus KeyStore gespeichert.</p><a class='btn ghost' href='/fetch-api'>FETCH API</a></section></div>
<section class='card' style='margin-top:16px'><h2>Configuration Matrix</h2><form action='/save'><div class='switchline'><span class='orb'></span><input type='hidden' name='webInterfaceEnabled' value='false'><input type='checkbox' name='webInterfaceEnabled' value='true' ${if (c.webInterfaceEnabled) "checked" else ""}><b>Webinterface aktiv</b></div><div class='switchline'><span class='orb'></span><input type='hidden' name='autoApiFetchEnabled' value='false'><input type='checkbox' name='autoApiFetchEnabled' value='true' ${if (c.autoApiFetchEnabled) "checked" else ""}><b>Auto API Fetch</b></div><label>Webinterface Port</label><input name='webInterfacePort' value='${c.webInterfacePort}'><label>Casambi MAC</label><input name='casambiMac' value='${esc(c.casambiMac)}'><label>Key ID</label><input name='casambiKeyId' value='${c.casambiKeyId}'><label>Key HEX</label><input name='casambiKeyHex' value='${esc(c.casambiKeyHex)}'><label>MQTT Host</label><input name='mqttHost' value='${esc(c.mqttHost)}'><label>MQTT Port</label><input name='mqttPort' value='${c.mqttPort}'><label>MQTT User</label><input name='mqttUser' value='${esc(c.mqttUser)}'><label>MQTT Passwort</label><input name='mqttPassword' value='${esc(c.mqttPassword)}'><label>Base Topic</label><input name='baseTopic' value='${esc(c.baseTopic)}'><label>Discovery Prefix</label><input name='discoveryPrefix' value='${esc(c.discoveryPrefix)}'><div class='switchline'><span class='orb'></span><input type='hidden' name='smbDebugEnabled' value='false'><input type='checkbox' name='smbDebugEnabled' value='true' ${if (c.smbDebugEnabled) "checked" else ""}><b>SMB Logging aktiv</b></div><label>SMB Server</label><input name='smbServer' value='${esc(c.smbServer)}'><label>SMB Share</label><input name='smbShare' value='${esc(c.smbShare)}'><label>SMB Path</label><input name='smbPath' value='${esc(c.smbPath)}'><label>SMB Domain</label><input name='smbDomain' value='${esc(c.smbDomain)}'><label>SMB User</label><input name='smbUser' value='${esc(c.smbUser)}'><label>SMB Passwort</label><input name='smbPassword' value='${esc(c.smbPassword)}'><input class='btn' type='submit' value='SAVE CONFIG'></form></section><div class='footer'>Live-Logs sind aus der App entfernt. Debug-Ausgabe laeuft primaer ueber SMB.</div></div>

<script>
let sliderTimer=null;
function sliderChanged(v){
  clearTimeout(sliderTimer);
  sliderTimer=setTimeout(()=>{
    const value=parseInt(v||'0');
    const state=value<=0?'OFF':'ON';
    fetch('/command?state='+state+'&brightness='+value).then(()=>{}).catch(()=>{});
  },420);
}
function pollStatus(){fetch('/status').then(r=>r.json()).then(s=>{document.getElementById('stateText').textContent=s.state+' • '+s.brightness;const br=document.getElementById('brightness');if(document.activeElement!==br)br.value=s.brightness;}).catch(()=>{});}
setInterval(pollStatus,1500);pollStatus();
</script>

</body></html>
"""
    }


    private fun sceneButtons(config: BridgeConfig): String {
        val ctx = appContext ?: return "<p class='muted'>Kein Kontext.</p>"
        val scenes = SceneStore.loadScenes(ctx)
        if (scenes.isEmpty()) return "<p class='muted'>Keine Szenen gespeichert. Bitte FETCH API ausfuehren.</p>"
        return scenes.joinToString("") { scene ->
            "<a class='btn' href='/scene?id=${scene.id}&name=${scene.name}'>${scene.name}</a>"
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split("&").mapNotNull { part ->
        val kv = part.split("=", limit = 2)
        if (kv.isEmpty() || kv[0].isBlank()) null else URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
    }.toMap()

    private fun writeResponse(out: OutputStream, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
