package de.pascal.casambibridge.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CasambiSceneInfo(val id: Int, val name: String)
data class CasambiGroupInfo(val id: Int, val name: String)
data class CasambiUnitInfo(val id: Int, val name: String)

object SceneStore {
    private const val PREF = "casambi_bridge_scenes"
    private const val KEY_SCENES = "scenes"
    private const val KEY_GROUPS = "groups"
    private const val KEY_UNITS = "units"

    fun saveScenes(context: Context, scenes: List<Pair<Int, String>>) {
        save(context, KEY_SCENES, scenes)
    }

    fun saveGroups(context: Context, groups: List<Pair<Int, String>>) {
        save(context, KEY_GROUPS, groups)
    }

    fun saveUnits(context: Context, units: List<Pair<Int, String>>) {
        save(context, KEY_UNITS, units)
    }

    fun loadScenes(context: Context): List<CasambiSceneInfo> = load(context, KEY_SCENES).map { CasambiSceneInfo(it.first, it.second) }
    fun loadGroups(context: Context): List<CasambiGroupInfo> = load(context, KEY_GROUPS).map { CasambiGroupInfo(it.first, it.second) }
    fun loadUnits(context: Context): List<CasambiUnitInfo> = load(context, KEY_UNITS).map { CasambiUnitInfo(it.first, it.second) }

    private fun save(context: Context, key: String, items: List<Pair<Int, String>>) {
        val arr = JSONArray()
        items.forEach { (id, name) -> arr.put(JSONObject().put("id", id).put("name", name)) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }

    private fun load(context: Context, key: String): List<Pair<Int, String>> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += o.optInt("id", i + 1) to o.optString("name", "Item ${i + 1}")
        }
        return out
    }
}
