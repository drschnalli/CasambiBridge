package de.pascal.casambibridge.bridge

import android.content.Context
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream

object DashboardExporter {
    private const val FILE = "casambi_jungle_dashboard.yaml"

    fun exportToSmb(context: Context, config: BridgeConfig): String {
        val yaml = generateYaml(context, config)
        val ctx = DebugExporter.smbContext(config)
        val dir = DebugExporter.smbDir(config)
        SmbFile(dir, ctx).use { if (!it.exists()) it.mkdirs() }
        val url = dir + FILE
        SmbFileOutputStream(SmbFile(url, ctx), false).use { it.write(yaml.toByteArray(Charsets.UTF_8)) }
        return url
    }

    fun generateYaml(context: Context, config: BridgeConfig): String {
        val scenes = SceneStore.loadScenes(context)
        val groups = SceneStore.loadGroups(context)
        val units = SceneStore.loadUnits(context)
        val unitTitle = yamlText(units.firstOrNull()?.name ?: "Casambi Light 1")
        val networkName = yamlText(config.casambiNetworkName.ifBlank { "Casambi Bridge" })
        val groupInfo = yamlText(if (groups.isEmpty()) "Keine Gruppen gespeichert" else groups.joinToString { it.name })
        val b = StringBuilder()
        b.appendLine("type: vertical-stack")
        b.appendLine("cards:")
        b.appendLine("  - type: custom:mushroom-title-card")
        b.appendLine("    title: \"Casambi Jungle\"")
        b.appendLine("    subtitle: \"$networkName - powered by Sambesi\"")
        b.appendLine("    alignment: center")
        b.appendLine("")
        b.appendSeparator("Bridge Diagnostics", "mdi:access-point-network")
        b.appendLine("  - type: custom:mushroom-chips-card")
        b.appendLine("    chips:")
        b.appendChip("sensor.android_casambi_bridge_casambi_bridge_status")
        b.appendChip("sensor.android_casambi_bridge_casambi_ble_status")
        b.appendLine("")
        b.appendSeparator("Light Control", "mdi:lightbulb")
        b.appendLine("  - type: custom:mushroom-light-card")
        b.appendLine("    entity: light.android_casambi_bridge_casambi_light_1")
        b.appendLine("    name: \"$unitTitle\"")
        b.appendLine("    icon: mdi:ceiling-light")
        b.appendLine("    show_brightness_control: true")
        b.appendLine("    show_color_temp_control: false")
        b.appendLine("    show_color_control: false")
        b.appendLine("    use_light_color: false")
        b.appendLine("    collapsible_controls: false")
        b.appendLine("")
        b.appendLine("  - type: grid")
        b.appendLine("    columns: 3")
        b.appendLine("    square: false")
        b.appendLine("    cards:")
        b.appendServiceButton("ON", "Licht ein", "mdi:power", "green", "light.turn_on", "light.android_casambi_bridge_casambi_light_1")
        b.appendServiceButton("OFF", "Licht aus", "mdi:power", "red", "light.turn_off", "light.android_casambi_bridge_casambi_light_1")
        b.appendLight40Button()
        b.appendLine("")
        b.appendSeparator("Scenes", "mdi:palette")
        b.appendLine("  - type: grid")
        b.appendLine("    columns: 2")
        b.appendLine("    square: false")
        b.appendLine("    cards:")
        if (scenes.isEmpty()) {
            b.appendLine("      - type: custom:mushroom-template-card")
            b.appendLine("        primary: \"Keine Szenen gespeichert\"")
            b.appendLine("        secondary: \"Bitte API Fetch ausfuehren\"")
            b.appendLine("        icon: mdi:cloud-download")
            b.appendLine("        icon_color: grey")
            b.appendLine("        layout: vertical")
        } else {
            scenes.forEach { scene ->
                val slug = slug(scene.name, "scene_${scene.id}")
                b.appendButtonPress("Casambi Scene ${yamlText(scene.name)}", "Szene ${scene.id}", "mdi:palette", "purple", "button.android_casambi_bridge_casambi_scene_$slug")
            }
        }
        b.appendLine("")
        b.appendSeparator("Bridge Settings", "mdi:tune")
        b.appendLine("  - type: grid")
        b.appendLine("    columns: 2")
        b.appendLine("    square: false")
        b.appendLine("    cards:")
        b.appendEntitySwitch("switch.android_casambi_bridge_casambi_web_interface", "Web Interface", "mdi:web")
        b.appendEntitySwitch("switch.android_casambi_bridge_casambi_smb_logging", "SMB Logging", "mdi:nas")
        b.appendEntitySwitch("switch.android_casambi_bridge_casambi_tcp_logstream", "TCP Logstream", "mdi:console-network")
        b.appendEntitySwitch("switch.android_casambi_bridge_casambi_auto_api_fetch", "Auto API Fetch", "mdi:cloud-sync")
        b.appendLine("")
        b.appendSeparator("Bridge Controls", "mdi:tools")
        b.appendLine("  - type: grid")
        b.appendLine("    columns: 2")
        b.appendLine("    square: false")
        b.appendLine("    cards:")
        b.appendButtonPress("API Fetch", "Key und Szenen aktualisieren", "mdi:cloud-download", "cyan", "button.android_casambi_bridge_casambi_api_fetch")
        b.appendButtonPress("Restart Bridge", "Dienst neu starten", "mdi:restart", "orange", "button.android_casambi_bridge_casambi_restart_bridge")
        b.appendLine("")
        b.appendLine("  - type: markdown")
        b.appendLine("    content: |")
        b.appendLine("      **Casambi Gruppen:** $groupInfo")
        b.appendLine("      ")
        b.appendLine("      Dashboard automatisch erzeugt durch Casambi Bridge v0.7.0.")
        return b.toString()
    }

    private fun StringBuilder.appendSeparator(name: String, icon: String) { appendLine("  - type: custom:bubble-card"); appendLine("    card_type: separator"); appendLine("    name: \"${yamlText(name)}\""); appendLine("    icon: $icon"); appendLine("") }
    private fun StringBuilder.appendChip(entityId: String) { appendLine("      - type: entity"); appendLine("        entity: $entityId") }
    private fun StringBuilder.appendServiceButton(primary: String, secondary: String, icon: String, color: String, service: String, entityId: String) { appendLine("      - type: custom:mushroom-template-card"); appendLine("        primary: \"${yamlText(primary)}\""); appendLine("        secondary: \"${yamlText(secondary)}\""); appendLine("        icon: $icon"); appendLine("        icon_color: $color"); appendLine("        layout: vertical"); appendLine("        tap_action:"); appendLine("          action: call-service"); appendLine("          service: $service"); appendLine("          target:"); appendLine("            entity_id: $entityId") }
    private fun StringBuilder.appendLight40Button() { appendLine("      - type: custom:mushroom-template-card"); appendLine("        primary: \"40%\""); appendLine("        secondary: \"Soft Light\""); appendLine("        icon: mdi:brightness-5"); appendLine("        icon_color: amber"); appendLine("        layout: vertical"); appendLine("        tap_action:"); appendLine("          action: call-service"); appendLine("          service: light.turn_on"); appendLine("          target:"); appendLine("            entity_id: light.android_casambi_bridge_casambi_light_1"); appendLine("          data:"); appendLine("            brightness_pct: 40") }
    private fun StringBuilder.appendButtonPress(primary: String, secondary: String, icon: String, color: String, entityId: String) { appendLine("      - type: custom:mushroom-template-card"); appendLine("        entity: $entityId"); appendLine("        primary: \"${yamlText(primary)}\""); appendLine("        secondary: \"${yamlText(secondary)}\""); appendLine("        icon: $icon"); appendLine("        icon_color: $color"); appendLine("        layout: vertical"); appendLine("        tap_action:"); appendLine("          action: call-service"); appendLine("          service: button.press"); appendLine("          target:"); appendLine("            entity_id: $entityId") }
    private fun StringBuilder.appendEntitySwitch(entityId: String, name: String, icon: String) { appendLine("      - type: custom:mushroom-entity-card"); appendLine("        entity: $entityId"); appendLine("        name: \"${yamlText(name)}\""); appendLine("        icon: $icon"); appendLine("        tap_action:"); appendLine("          action: toggle") }
    private fun slug(value: String, fallback: String): String { val s = value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); return s.ifBlank { fallback } }
    private fun yamlText(value: String): String = value.replace("\\", "\\\\").replace("\"", "'")
}
