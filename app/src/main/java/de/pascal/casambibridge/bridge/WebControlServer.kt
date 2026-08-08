package de.pascal.casambibridge.bridge

import android.content.Context
import android.content.Intent
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.ACTION_COMMAND
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.EXTRA_BRIGHTNESS
import de.pascal.casambibridge.bridge.CasambiBridgeService.Companion.EXTRA_STATE
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
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
            if (!config.webInterfaceEnabled) { stopLocked(true); return }
            val port = config.webInterfacePort.coerceIn(1024, 65535)
            if (serverSocket != null && runningPort == port) { LogBus.log("Webinterface laeuft bereits auf Port $port"); return }
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
                                while (true) { val line = reader.readLine() ?: break; if (line.isBlank()) break }
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
            "/status" -> "application/json; charset=utf-8" to statusJson()
            "/logs" -> "text/html; charset=utf-8" to logsPage()
            "/logs.txt" -> "text/plain; charset=utf-8" to LogBus.recentLines(180).joinToString("\n")
            "/files" -> "text/html; charset=utf-8" to filesPage()
            "/file" -> fileResponse(params["name"] ?: "")
            "/dashboard-yaml" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                "text/yaml; charset=utf-8" to DashboardExporter.generateYaml(ctx, c)
            }
            "/dashboard-export" -> {
                val ctx = appContext ?: return "text/plain; charset=utf-8" to "No context"
                val c = ConfigStore.load(ctx)
                val msg = try { "Dashboard YAML gespeichert: ${DashboardExporter.exportToSmb(ctx, c)}" } catch (t: Throwable) { "Dashboard Export Fehler: ${t.message}" }
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

    private fun statusJson(): String = JSONObject()
        .put("state", RuntimeStatus.lastState)
        .put("brightness", RuntimeStatus.lastBrightness)
        .put("online", RuntimeStatus.lastOnline)
        .put("raw", RuntimeStatus.lastRawState)
        .put("bridge", RuntimeStatus.bridgeState)
        .put("ble", RuntimeStatus.bleConnected)
        .put("mqtt", RuntimeStatus.mqttConnected)
        .put("cloud", RuntimeStatus.cloudConnected)
        .put("uptime", RuntimeStatus.uptimeText())
        .put("lastUpdate", RuntimeStatus.lastUpdateMillis)
        .put("lastSync", RuntimeStatus.lastSyncMillis)
        .put("version", "0.5.3")
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
            "API Fetch OK: ${result.rawSummary}. Dashboard wurde neu erzeugt."
        } catch (t: Throwable) {
            "API Fetch Fehler: ${t.message}"
        }
    }

    private fun sendCommand(state: String, brightness: Int?) {
        val ctx = appContext ?: return
        ctx.startService(Intent(ctx, CasambiBridgeService::class.java).apply {
            action = ACTION_COMMAND
            putExtra(EXTRA_STATE, state)
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
            page("SMB Browser", "<div class='toolbar'><a class='btn ghost' href='/'>HOME</a><a class='btn ghost' href='/dashboard-yaml'>YAML anzeigen</a><a class='btn ghost' href='/dashboard-export'>YAML neu schreiben</a></div>$rows")
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
        val sceneButtons = if (scenes.isEmpty()) "<span class='muted'>Keine Szenen gespeichert</span>" else scenes.joinToString(" ") { s -> "<a class='btn ghost' href='/scene?id=${s.id}&name=${url(s.name)}'>${esc(s.name)}</a>" }
        return page("Casambi Jungle", """
<div class='hero'>
  <h1>CASAMBI JUNGLE</h1>
  <div class='sub'>${esc(c.casambiNetworkName.ifBlank { "Bridge Control Center" })} - powered by Sambesi - v0.5.3</div>
  <div class='msg'>${esc(message)}</div>
</div>
<div class='grid'>
  <section class='card'><h2>Live Status</h2><div id='statusGrid' class='statusGrid'>Lade Status...</div><script>${statusScript()}</script></section>
  <section class='card'><h2>Licht</h2><div class='controlRow'><a class='btn' href='/command?state=ON'>ON</a><a class='btn danger' href='/command?state=OFF'>OFF</a><a class='btn amber' href='/command?state=ON&brightness=102'>40%</a></div></section>
  <section class='card'><h2>Szenen</h2><div class='controlRow'>$sceneButtons</div></section>
  <section class='card'><h2>Tools</h2><div class='controlRow'><a class='btn ghost' href='/fetch-api'>API Fetch</a><a class='btn ghost' href='/dashboard-export'>YAML neu schreiben</a><a class='btn ghost' href='/dashboard-yaml'>YAML anzeigen</a><a class='btn ghost' href='/files'>SMB Browser</a><a class='btn ghost' href='/logs'>Live Log</a><a class='btn ghost' href='/backup'>Backup SMB</a></div></section>
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
.wrap{max-width:1120px;margin:auto;padding:22px}.hero,.card{border:1px solid var(--line);border-radius:24px;background:linear-gradient(180deg,#082216e8,#03100ae8);box-shadow:0 0 24px #14f19522;padding:16px;margin-bottom:16px}h1{margin:0;color:var(--leaf);font-size:28px;text-shadow:0 0 18px #14f195aa}h2{margin:0 0 12px;color:var(--teal);font-size:18px}.sub,.muted{color:var(--muted)}.msg{margin-top:10px;color:var(--lime)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}.wide{grid-column:1/-1}.btn{display:inline-flex;align-items:center;justify-content:center;margin:5px;padding:12px 15px;border-radius:15px;background:linear-gradient(135deg,var(--leaf),var(--lime));color:#001208;text-decoration:none;font-weight:900;border:0}.ghost{background:linear-gradient(135deg,#0e2b1e,#10351f);color:var(--leaf);border:1px solid var(--line)}.danger{background:linear-gradient(135deg,var(--danger),#ff9bb8)}.amber{background:linear-gradient(135deg,var(--amber),#e0a326)}.mini{padding:7px 10px;font-size:11px}.statusGrid{display:grid;gap:8px}.pill{display:flex;justify-content:space-between;gap:10px;border:1px solid #14f19533;border-radius:12px;padding:8px;background:#020a0788}.ok{color:var(--lime)}.bad,.dangerText{color:var(--danger)}.controlRow{display:flex;flex-wrap:wrap}.toolbar{margin:0 0 14px}.file{display:grid;grid-template-columns:1fr auto auto;gap:10px;align-items:center;border:1px solid #14f19533;border-radius:12px;padding:9px;margin:7px 0;background:#020a0788}pre{white-space:pre-wrap;word-break:break-word;max-height:420px;overflow:auto;background:#020a07;border:1px solid #14f19533;border-radius:16px;padding:13px;color:var(--text)}
</style></head><body><div class='wrap'>$body</div></body></html>
""".trimIndent()

    private fun statusScript(): String = """
async function refreshStatus(){try{const r=await fetch('/status');const s=await r.json();const p=(n,v,ok)=>`<div class='pill'><span>${'$'}{n}</span><b class='${'$'}{ok?'ok':'bad'}'>${'$'}{v}</b></div>`;document.getElementById('statusGrid').innerHTML=p('Bridge',s.bridge,s.bridge==='online')+p('BLE',s.ble?'connected':'disconnected',s.ble)+p('MQTT',s.mqtt?'online':'offline',s.mqtt)+p('Cloud',s.cloud?'synced':'unknown',s.cloud)+p('Licht',s.state+' / '+s.brightness,s.online)+p('Uptime',s.uptime,true)+p('Version',s.version,true);}catch(e){document.getElementById('statusGrid').innerHTML='<span class="bad">Status Fehler</span>';}}
refreshStatus();setInterval(refreshStatus,2000);
""".trimIndent()

    private fun logScript(): String = """
async function refreshLog(){try{const r=await fetch('/logs.txt');const t=await r.text();const el=document.getElementById('logBox');el.textContent=t||'Noch keine Logs';el.scrollTop=el.scrollHeight;}catch(e){document.getElementById('logBox').textContent='Log Fehler';}}
refreshLog();setInterval(refreshLog,2500);
""".trimIndent()

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
