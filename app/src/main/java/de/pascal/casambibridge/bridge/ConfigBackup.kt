package de.pascal.casambibridge.bridge

import android.content.Context
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import org.json.JSONArray
import org.json.JSONObject

object ConfigBackup {
    private const val FILE = "casambi_bridge_config.json"
    private const val FULL_FILE = "casambi_bridge_full_backup.json"

    fun exportToSmb(c: BridgeConfig): String {
        val ctx = DebugExporter.smbContext(c)
        val dir = DebugExporter.smbDir(c)
        SmbFile(dir, ctx).use { if (!it.exists()) it.mkdirs() }
        val url = dir + FILE
        SmbFileOutputStream(SmbFile(url, ctx), false).use { it.write(toJson(c).toString(2).toByteArray(Charsets.UTF_8)) }
        return url
    }

    fun exportFullToSmb(context: Context, c: BridgeConfig): String {
        val ctx = DebugExporter.smbContext(c)
        val dir = DebugExporter.smbDir(c)
        SmbFile(dir, ctx).use { if (!it.exists()) it.mkdirs() }
        val url = dir + FULL_FILE
        val root = JSONObject()
            .put("version", "0.7.4")
            .put("created", System.currentTimeMillis())
            .put("lastApiSyncMillis", ConfigStore.lastSyncMillis(context))
            .put("config", toJson(c))
            .put("scenes", infosToJson(SceneStore.loadScenes(context).map { it.id to it.name }))
            .put("groups", infosToJson(SceneStore.loadGroups(context).map { it.id to it.name }))
            .put("units", infosToJson(SceneStore.loadUnits(context).map { it.id to it.name }))
        SmbFileOutputStream(SmbFile(url, ctx), false).use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        return url
    }

    fun restoreFromSmb(c: BridgeConfig): BridgeConfig {
        val ctx = DebugExporter.smbContext(c)
        val url = DebugExporter.smbDir(c) + FILE
        val text = SmbFileInputStream(SmbFile(url, ctx)).use { it.readBytes().toString(Charsets.UTF_8) }
        return fromJson(JSONObject(text), c)
    }

    fun restoreFullFromSmb(context: Context, fallback: BridgeConfig): BridgeConfig {
        val ctx = DebugExporter.smbContext(fallback)
        val url = DebugExporter.smbDir(fallback) + FULL_FILE
        val text = SmbFileInputStream(SmbFile(url, ctx)).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        val cfg = fromJson(root.optJSONObject("config") ?: root, fallback)
        if (root.has("lastApiSyncMillis")) ConfigStore.saveLastSyncMillis(context, root.optLong("lastApiSyncMillis", 0L))
        SceneStore.saveScenes(context, jsonToInfos(root.optJSONArray("scenes")))
        SceneStore.saveGroups(context, jsonToInfos(root.optJSONArray("groups")))
        SceneStore.saveUnits(context, jsonToInfos(root.optJSONArray("units")))
        return cfg
    }

    private fun infosToJson(items: List<Pair<Int, String>>): JSONArray {
        val arr = JSONArray()
        items.forEach { (id, name) -> arr.put(JSONObject().put("id", id).put("name", name)) }
        return arr
    }

    private fun jsonToInfos(arr: JSONArray?): List<Pair<Int, String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += o.optInt("id", i + 1) to o.optString("name", "Item ${i + 1}")
        }
        return out
    }

    private fun toJson(c: BridgeConfig) = JSONObject()
        .put("casambiMac", c.casambiMac)
        .put("casambiNetworkName", c.casambiNetworkName)
        .put("casambiPassword", c.casambiPassword)
        .put("casambiProtocolVersion", c.casambiProtocolVersion)
        .put("casambiKeyId", c.casambiKeyId)
        .put("casambiKeyHex", c.casambiKeyHex)
        .put("mqttHost", c.mqttHost)
        .put("mqttPort", c.mqttPort)
        .put("mqttUser", c.mqttUser)
        .put("mqttPassword", c.mqttPassword)
        .put("baseTopic", c.baseTopic)
        .put("discoveryPrefix", c.discoveryPrefix)
        .put("smbDebugEnabled", c.smbDebugEnabled)
        .put("smbServer", c.smbServer)
        .put("smbShare", c.smbShare)
        .put("smbPath", c.smbPath)
        .put("smbDomain", c.smbDomain)
        .put("smbUser", c.smbUser)
        .put("smbPassword", c.smbPassword)
        .put("tcpLogEnabled", c.tcpLogEnabled)
        .put("tcpLogPort", c.tcpLogPort)
        .put("webInterfaceEnabled", c.webInterfaceEnabled)
        .put("webInterfacePort", c.webInterfacePort)
        .put("autoApiFetchEnabled", c.autoApiFetchEnabled)
        .put("webSocketLiveEnabled", c.webSocketLiveEnabled)
        .put("mqttEnabled", c.mqttEnabled)
        .put("directModeEnabled", c.directModeEnabled)
        .put("networkDiscoveryEnabled", c.networkDiscoveryEnabled)
        .put("returnAppPackage", c.returnAppPackage)

    private fun fromJson(j: JSONObject, f: BridgeConfig) = BridgeConfig(
        j.optString("casambiMac", f.casambiMac),
        j.optString("casambiNetworkName", f.casambiNetworkName),
        j.optString("casambiPassword", f.casambiPassword),
        j.optInt("casambiProtocolVersion", f.casambiProtocolVersion),
        j.optInt("casambiKeyId", f.casambiKeyId),
        j.optString("casambiKeyHex", f.casambiKeyHex),
        j.optString("mqttHost", f.mqttHost),
        j.optInt("mqttPort", f.mqttPort),
        j.optString("mqttUser", f.mqttUser),
        j.optString("mqttPassword", f.mqttPassword),
        j.optString("baseTopic", f.baseTopic),
        j.optString("discoveryPrefix", f.discoveryPrefix),
        j.optBoolean("smbDebugEnabled", f.smbDebugEnabled),
        j.optString("smbServer", f.smbServer),
        j.optString("smbShare", f.smbShare),
        j.optString("smbPath", f.smbPath),
        j.optString("smbDomain", f.smbDomain),
        j.optString("smbUser", f.smbUser),
        j.optString("smbPassword", f.smbPassword),
        j.optBoolean("tcpLogEnabled", f.tcpLogEnabled),
        j.optInt("tcpLogPort", f.tcpLogPort),
        j.optBoolean("webInterfaceEnabled", f.webInterfaceEnabled),
        j.optInt("webInterfacePort", f.webInterfacePort),
        j.optBoolean("autoApiFetchEnabled", f.autoApiFetchEnabled),
        j.optBoolean("webSocketLiveEnabled", f.webSocketLiveEnabled),
        j.optBoolean("mqttEnabled", f.mqttEnabled),
        j.optBoolean("directModeEnabled", f.directModeEnabled),
        j.optBoolean("networkDiscoveryEnabled", f.networkDiscoveryEnabled),
        j.optString("returnAppPackage", f.returnAppPackage)
    )
}
