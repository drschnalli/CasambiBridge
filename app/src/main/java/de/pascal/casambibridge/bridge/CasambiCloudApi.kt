package de.pascal.casambibridge.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CasambiCloudApi {
    data class Result(
        val networkId: String?,
        val networkName: String?,
        val protocolVersion: Int?,
        val keyId: Int?,
        val keyHex: String?,
        val scenes: List<Pair<Int, String>>,
        val groups: List<Pair<Int, String>>,
        val units: List<Pair<Int, String>>,
        val rawSummary: String
    )

    fun fetch(config: BridgeConfig): Result {
        val uuid = config.casambiMac.replace(":", "").replace("-", "").lowercase().trim()
        require(uuid.isNotBlank()) { "Casambi MAC fehlt" }
        require(config.casambiPassword.isNotBlank()) { "Casambi Netzwerk-Passwort fehlt" }

        val networkIdJson = getJson("https://api.casambi.com/network/uuid/$uuid")
        val networkId = networkIdJson.optString("id", "")
        require(networkId.isNotBlank()) { "API lieferte keine network id" }

        val sessionJson = postJson(
            "https://api.casambi.com/network/$networkId/session",
            JSONObject().put("password", config.casambiPassword).put("deviceName", "Android Casambi Bridge")
        )
        val session = sessionJson.optString("session", "")
        require(session.isNotBlank()) { "API lieferte keine session" }

        val networkJson = putJson(
            "https://api.casambi.com/network/$networkId/",
            JSONObject().put("formatVersion", 1).put("deviceName", "Android Casambi Bridge").put("revision", 0),
            mapOf("X-Casambi-Session" to session)
        )
        val net = networkJson.optJSONObject("network") ?: networkJson
        val key = findBestKey(net, sessionJson.optInt("keyID", -1))
        val scenes = parseScenes(net.optJSONArray("scenes"))
        val groups = parseGroups(net.optJSONObject("grid"))
        val units = parseUnits(net.optJSONArray("units"))
        val summary = "network=${net.optString("name", "-")} protocol=${net.optInt("protocolVersion", -1)} scenes=${scenes.size} groups=${groups.size} units=${units.size} key=${if (key != null) "yes" else "no"}"
        return Result(
            networkId = networkId,
            networkName = net.optString("name", null),
            protocolVersion = if (net.has("protocolVersion")) net.optInt("protocolVersion") else null,
            keyId = key?.first ?: sessionJson.optInt("keyID", -1).takeIf { it >= 0 },
            keyHex = key?.second,
            scenes = scenes,
            groups = groups,
            units = units,
            rawSummary = summary
        )
    }

    private fun getJson(url: String): JSONObject = request("GET", url, null, emptyMap())
    private fun postJson(url: String, body: JSONObject): JSONObject = request("POST", url, body, emptyMap())
    private fun putJson(url: String, body: JSONObject, headers: Map<String, String>): JSONObject = request("PUT", url, body, headers)

    private fun request(method: String, urlString: String, body: JSONObject?, headers: Map<String, String>): JSONObject {
        val conn = (URL(urlString).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 18000
        conn.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code $text")
        return JSONObject(text)
    }

    private fun parseScenes(arr: JSONArray?): List<Pair<Int, String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("sceneID", o.optInt("id", i + 1))
            val name = o.optString("name", "Scene $id")
            out += id to name
        }
        return out
    }

    private fun parseGroups(grid: JSONObject?): List<Pair<Int, String>> {
        val cells = grid?.optJSONArray("cells") ?: return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until cells.length()) {
            val c = cells.optJSONObject(i) ?: continue
            if (c.optInt("type", -1) == 2) {
                val id = c.optInt("groupID", c.optInt("id", i + 1))
                val name = c.optString("name", "Group $id")
                out += id to name
            }
        }
        return out
    }

    private fun parseUnits(arr: JSONArray?): List<Pair<Int, String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val id = u.optInt("deviceID", u.optInt("id", i + 1))
            val name = u.optString("name", "Unit $id")
            out += id to name
        }
        return out
    }

    private fun findBestKey(net: JSONObject, preferredId: Int): Pair<Int, String>? {
        val keys = net.optJSONObject("keyStore")?.optJSONArray("keys") ?: return null
        val candidates = mutableListOf<Pair<Int, String>>()
        for (i in 0 until keys.length()) {
            val obj = keys.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("keyID", obj.optInt("keyId", obj.optInt("ID", -1))))
            val hex = keyHexFromObject(obj) ?: continue
            if (id >= 0) candidates += id to hex
        }
        return candidates.firstOrNull { it.first == preferredId } ?: candidates.firstOrNull()
    }

    private fun keyHexFromObject(obj: JSONObject): String? {
        val names = listOf("key", "value", "networkKey", "sessionKey", "data")
        for (n in names) {
            if (!obj.has(n)) continue
            val v = obj.opt(n)
            val hex = when (v) {
                is String -> normalizeHex(v)
                is JSONArray -> jsonArrayToHex(v)
                else -> null
            }
            if (!hex.isNullOrBlank() && hex.length >= 32) return hex
        }
        return null
    }

    private fun normalizeHex(value: String): String? {
        val cleaned = value.replace(" ", "").replace(":", "").replace("-", "").trim()
        return if (cleaned.matches(Regex("[0-9a-fA-F]+")) && cleaned.length % 2 == 0) cleaned.uppercase() else null
    }

    private fun jsonArrayToHex(arr: JSONArray): String {
        val b = ByteArray(arr.length()) { i -> (arr.optInt(i) and 255).toByte() }
        return HexUtil.bytesToHex(b)
    }
}
