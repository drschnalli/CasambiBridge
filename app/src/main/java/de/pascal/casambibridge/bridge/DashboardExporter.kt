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
        val sceneCards = if (scenes.isEmpty()) {
            """
      - type: custom:mushroom-template-card
        primary: Keine Szenen gespeichert
        secondary: Bitte API Fetch ausfuehren
        icon: mdi:cloud-download
        icon_color: grey
"""
        } else scenes.joinToString("
") { scene ->
            val slug = scene.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "scene_${scene.id}" }
            """
      - type: custom:mushroom-template-card
        entity: button.android_casambi_bridge_casambi_scene_$slug
        primary: "${scene.name.replace(""", "'")}"
        secondary: Szene ${scene.id}
        icon: mdi:palette
        icon_color: purple
        layout: vertical
        tap_action:
          action: call-service
          service: button.press
          target:
            entity_id: button.android_casambi_bridge_casambi_scene_$slug
""".trimEnd()
        }
        val unitTitle = units.firstOrNull()?.name ?: "Casambi Light 1"
        val groupInfo = if (groups.isEmpty()) "Keine Gruppen gespeichert" else groups.joinToString { it.name }
        return """
type: vertical-stack
cards:
  - type: custom:mushroom-title-card
    title: "🌴 Casambi Jungle"
    subtitle: "${config.casambiNetworkName.ifBlank { "Casambi Bridge" }} · powered by Sambesi"
    alignment: center

  - type: custom:bubble-card
    card_type: separator
    name: Bridge Diagnostics
    icon: mdi:access-point-network

  - type: custom:mushroom-chips-card
    chips:
      - type: entity
        entity: sensor.android_casambi_bridge_casambi_bridge_status
      - type: entity
        entity: sensor.android_casambi_bridge_casambi_ble_status
      - type: entity
        entity: binary_sensor.android_casambi_bridge_casambi_mqtt_connected
      - type: entity
        entity: sensor.android_casambi_bridge_casambi_bridge_uptime
      - type: entity
        entity: sensor.android_casambi_bridge_casambi_scene_count
      - type: entity
        entity: sensor.android_casambi_bridge_casambi_group_count

  - type: grid
    columns: 3
    square: false
    cards:
      - type: custom:mushroom-template-card
        primary: Bridge
        secondary: "{{ states('sensor.android_casambi_bridge_casambi_bridge_status') }}"
        icon: mdi:bridge
        icon_color: >
          {% if is_state('sensor.android_casambi_bridge_casambi_bridge_status', 'online') %} green {% else %} red {% endif %}
        layout: vertical
      - type: custom:mushroom-template-card
        primary: BLE
        secondary: "{{ states('sensor.android_casambi_bridge_casambi_ble_status') }}"
        icon: mdi:bluetooth
        icon_color: >
          {% if is_state('sensor.android_casambi_bridge_casambi_ble_status', 'connected') %} blue {% else %} grey {% endif %}
        layout: vertical
      - type: custom:mushroom-template-card
        primary: Unit 1
        secondary: >
          {% if is_state('binary_sensor.android_casambi_bridge_casambi_unit_1_online', 'on') %} Online {% else %} Offline {% endif %}
        icon: mdi:lightbulb-on-outline
        icon_color: >
          {% if is_state('binary_sensor.android_casambi_bridge_casambi_unit_1_online', 'on') %} green {% else %} grey {% endif %}
        layout: vertical

  - type: custom:bubble-card
    card_type: separator
    name: Light Control
    icon: mdi:lightbulb

  - type: custom:mushroom-light-card
    entity: light.android_casambi_bridge_casambi_light_1
    name: "💡 $unitTitle"
    icon: mdi:ceiling-light
    show_brightness_control: true
    show_color_temp_control: false
    show_color_control: false
    use_light_color: false
    collapsible_controls: false

  - type: grid
    columns: 3
    square: false
    cards:
      - type: custom:mushroom-template-card
        primary: "ON"
        secondary: Licht ein
        icon: mdi:power
        icon_color: green
        layout: vertical
        tap_action:
          action: call-service
          service: light.turn_on
          target:
            entity_id: light.android_casambi_bridge_casambi_light_1
      - type: custom:mushroom-template-card
        primary: "OFF"
        secondary: Licht aus
        icon: mdi:power
        icon_color: red
        layout: vertical
        tap_action:
          action: call-service
          service: light.turn_off
          target:
            entity_id: light.android_casambi_bridge_casambi_light_1
      - type: custom:mushroom-template-card
        primary: "40%"
        secondary: Soft Light
        icon: mdi:brightness-5
        icon_color: amber
        layout: vertical
        tap_action:
          action: call-service
          service: light.turn_on
          target:
            entity_id: light.android_casambi_bridge_casambi_light_1
          data:
            brightness_pct: 40

  - type: custom:bubble-card
    card_type: separator
    name: Scenes
    icon: mdi:palette

  - type: grid
    columns: 2
    square: false
    cards:
$sceneCards

  - type: custom:bubble-card
    card_type: separator
    name: Bridge Settings
    icon: mdi:tune

  - type: grid
    columns: 2
    square: false
    cards:
      - type: custom:mushroom-entity-card
        entity: switch.android_casambi_bridge_casambi_web_interface
        name: Web Interface
        icon: mdi:web
        tap_action:
          action: toggle
      - type: custom:mushroom-entity-card
        entity: switch.android_casambi_bridge_casambi_smb_logging
        name: SMB Logging
        icon: mdi:nas
        tap_action:
          action: toggle
      - type: custom:mushroom-entity-card
        entity: switch.android_casambi_bridge_casambi_tcp_logstream
        name: TCP Logstream
        icon: mdi:console-network
        tap_action:
          action: toggle
      - type: custom:mushroom-entity-card
        entity: switch.android_casambi_bridge_casambi_auto_api_fetch
        name: Auto API Fetch
        icon: mdi:cloud-sync
        tap_action:
          action: toggle

  - type: custom:bubble-card
    card_type: separator
    name: Bridge Controls
    icon: mdi:tools

  - type: grid
    columns: 2
    square: false
    cards:
      - type: custom:mushroom-template-card
        entity: button.android_casambi_bridge_casambi_api_fetch
        primary: API Fetch
        secondary: Key/Szenen aktualisieren
        icon: mdi:cloud-download
        icon_color: cyan
        layout: vertical
        tap_action:
          action: call-service
          service: button.press
          target:
            entity_id: button.android_casambi_bridge_casambi_api_fetch
      - type: custom:mushroom-template-card
        entity: button.android_casambi_bridge_casambi_restart_bridge
        primary: Restart Bridge
        secondary: Dienst neu starten
        icon: mdi:restart
        icon_color: orange
        layout: vertical
        tap_action:
          action: call-service
          service: button.press
          target:
            entity_id: button.android_casambi_bridge_casambi_restart_bridge

  - type: markdown
    content: |
      **Casambi Gruppen:** $groupInfo
      
      Dashboard automatisch erzeugt durch Casambi Bridge v0.5.0.
""".trimIndent()
    }
}
