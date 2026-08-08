package de.pascal.casambibridge

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.pascal.casambibridge.bridge.BridgeConfig
import de.pascal.casambibridge.bridge.CasambiBridgeService
import de.pascal.casambibridge.bridge.CasambiCloudApi
import de.pascal.casambibridge.bridge.ConfigBackup
import de.pascal.casambibridge.bridge.DashboardExporter
import de.pascal.casambibridge.bridge.ConfigStore
import de.pascal.casambibridge.bridge.DebugExporter
import de.pascal.casambibridge.bridge.LogBus
import de.pascal.casambibridge.bridge.MqttBridge
import de.pascal.casambibridge.bridge.RuntimeStatus
import de.pascal.casambibridge.bridge.SceneStore
import de.pascal.casambibridge.bridge.TcpLogServer
import de.pascal.casambibridge.bridge.WebControlServer
import kotlin.random.Random
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val casambiManufacturerId = 963
    private val casambiServiceUuid = UUID.fromString("c9ffde48-ca5a-0000-ab83-8f519b482f77")
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var bleRxLed: TextView
    private lateinit var bleTxLed: TextView
    private lateinit var mqttStatusLed: TextView
    private lateinit var mqttInLed: TextView
    private lateinit var mqttOutLed: TextView
    private lateinit var smbLed: TextView
    private lateinit var webLed: TextView
    private lateinit var tcpLed: TextView
    private var lampLedRef: TextView? = null
    private var lampValueRef: TextView? = null
    private var sceneRefresh: (() -> Unit)? = null

    private val bg = Color.rgb(3, 17, 12)
    private val panel = Color.rgb(7, 28, 20)
    private val leaf = Color.rgb(20, 241, 149)
    private val lime = Color.rgb(182, 255, 77)
    private val cyan = Color.rgb(0, 229, 255)
    private val violet = Color.rgb(138, 92, 246)
    private val ps2Blue = Color.rgb(0, 140, 255)
    private val ps2Purple = Color.rgb(142, 72, 255)
    private val ps2Green = Color.rgb(20, 241, 149)
    private val amber = Color.rgb(255, 204, 102)
    private val darkLed = Color.rgb(34, 58, 45)
    private val textMain = Color.rgb(234, 255, 244)
    private val textMuted = Color.rgb(143, 187, 165)

    private val logListener: (String) -> Unit = { line ->
        statusText.text = line
        lampValueTextSafe()?.let { it.text = "Status: ${RuntimeStatus.lastState} ${RuntimeStatus.lastBrightness}" }
        if (::bleRxLed.isInitialized) setLedSafeLamp()
        if (line.contains("API Fetch OK") || line.contains("Auto API Fetch OK")) ui.post { sceneRefresh?.invoke() }
        if (::mqttStatusLed.isInitialized) {
            when {
                line.contains("MQTT verbunden") -> setLed(mqttStatusLed, true)
                line.contains("MQTT Verbindung verloren") || line.contains("MQTT Fehler") || line.contains("MQTT Host leer") -> setLed(mqttStatusLed, false)
            }
        }
        when {
            line.contains("Notify") || line.contains("UnitState") || line.contains("Authentication successful") -> flash(bleRxLed)
            line.contains("TX Frame") || line.contains("TX Encrypted") || line.contains("GATT write") -> flash(bleTxLed)
            line.contains("MQTT Command Unit") || line.contains("MQTT Command Callback") -> flash(mqttInLed)
            line.contains("MQTT State Unit") || line.contains("publish", ignoreCase = true) -> flash(mqttOutLed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        val c = ConfigStore.load(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(bg)
        }
        scroll.addView(root)

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(TextView(this).apply {
            text = "🌴 CASAMBI JUNGLE // v0.5.0"
            textSize = 21f
            setTextColor(leaf)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(Button(this).apply {
            text = "FORCE STOP"
            textSize = 9f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setBackgroundColor(Color.rgb(78, 0, 100))
            setOnClickListener {
                startService(Intent(this@MainActivity, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_STOP })
                finishAndRemoveTask()
                ui.postDelayed({ Process.killProcess(Process.myPid()) }, 250)
            }
        })
        root.addView(headerRow)
        root.addView(TextView(this).apply {
            text = "powered by Sambesi"
            textSize = 9f
            setTextColor(lime)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, 2)
        })
        root.addView(TextView(this).apply {
            text = "NEON CANOPY CONTROL CENTER"
            textSize = 12f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
            setBackgroundColor(Color.rgb(2, 12, 8))
        }
        root.addView(tabBar)

        fun page(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val homePage = page()
        val setupPage = page()
        val controlPage = page()
        val settingsPage = page()
        val advancedPage = page()
        val pages = listOf(homePage, setupPage, controlPage, settingsPage, advancedPage)
        pages.forEach { root.addView(it) }
        var currentPage: LinearLayout = homePage
        val tabButtons = linkedMapOf<LinearLayout, Button>()
        fun styleTab(button: Button, active: Boolean) {
            val base = button.tag?.toString() ?: button.text.toString()
            if (active) {
                button.text = "🌴 $base"
                button.setBackgroundColor(cyan)
                button.setTextColor(Color.rgb(0, 18, 8))
                button.textSize = 7.8f
                button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            } else {
                button.text = "🌿 $base"
                button.setBackgroundColor(leaf)
                button.setTextColor(Color.rgb(0, 18, 8))
                button.textSize = 7.3f
                button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            }
        }

        fun showPage(target: LinearLayout) {
            pages.forEach { it.visibility = if (it == target) View.VISIBLE else View.GONE }
            tabButtons.forEach { (page, button) -> styleTab(button, page == target) }
        }

        fun tab(text: String, target: LinearLayout): Button = Button(this).apply {
            this.tag = text
            this.text = text
            textSize = 8f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(0, 18, 8))
            setBackgroundColor(leaf)
            setPadding(1, 4, 1, 4)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 2, 0)
            }
            setOnClickListener { showPage(target) }
        }
        tabButtons[homePage] = tab("HOME", homePage).also { tabBar.addView(it) }
        tabButtons[setupPage] = tab("SETUP", setupPage).also { tabBar.addView(it) }
        tabButtons[controlPage] = tab("CTRL", controlPage).also { tabBar.addView(it) }
        tabButtons[settingsPage] = tab("SET", settingsPage).also { tabBar.addView(it) }
        tabButtons[advancedPage] = tab("ADV", advancedPage).also { tabBar.addView(it) }
        showPage(homePage)

        fun card(title: String): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundColor(panel)
            }
            box.addView(TextView(this).apply {
                text = title.uppercase()
                textSize = 13f
                setTextColor(cyan)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
            currentPage.addView(box)
            addGap(currentPage, 12)
            return box
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text.uppercase()
            textSize = 10f
            setTextColor(lime)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 10, 0, 2)
        }

        fun field(parent: LinearLayout, label: String, value: String, password: Boolean = false): EditText {
            parent.addView(label(label))
            return EditText(this).apply {
                setText(value)
                textSize = 13f
                setSingleLine(true)
                setTextColor(textMain)
                setHintTextColor(textMuted)
                setBackgroundColor(Color.rgb(2, 10, 7))
                setPadding(12, 8, 12, 8)
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                parent.addView(this)
            }
        }

        fun button(text: String) = Button(this).apply {
            this.text = text
            textSize = 9f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(0, 18, 8))
            setBackgroundColor(leaf)
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(4, 8, 4, 8)
        }
        fun wideButton(text: String, weight: Float = 1f) = button(text).apply {
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(4, 4, 4, 4)
            }
            minHeight = 52
            minimumHeight = 52
        }

        fun led(): TextView = TextView(this).apply {
            text = "●"
            textSize = 22f
            setTextColor(darkLed)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 8, 0)
        }

        fun signalRow(parent: LinearLayout, title: String): TextView {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = led()
            row.addView(l)
            row.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(textMain)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(3, 6, 0, 0)
            })
            parent.addView(row)
            return l
        }

        currentPage = homePage
        val jungleCard = card("🌴 Casambi Jungle")
        jungleCard.addView(TextView(this).apply {
            val sceneCount = SceneStore.loadScenes(this@MainActivity).size
            val networkName = c.casambiNetworkName.ifBlank { "nicht gesetzt" }
            val mqttText = if (c.mqttHost.isNotBlank()) "📡 MQTT ${c.mqttHost}:${c.mqttPort}" else "📡 MQTT nicht eingerichtet"
            text = "🌴 $networkName\n💡 Units: 1  |  🎭 Scenes: $sceneCount\n$mqttText"
            textSize = 12f
            setTextColor(textMain)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 6, 0, 2)
        })

        val statusCard = card("Signal Canopy")
        val sigGrid1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusCard.addView(sigGrid1)
        bleRxLed = signalRow(sigGrid1, "BLE RX")
        bleTxLed = signalRow(sigGrid1, "BLE TX")
        mqttStatusLed = signalRow(sigGrid1, "MQTT verbunden")
        mqttInLed = signalRow(sigGrid1, "MQTT IN")
        mqttOutLed = signalRow(sigGrid1, "MQTT OUT")
        smbLed = signalRow(sigGrid1, "SMB Logging")
        webLed = signalRow(sigGrid1, "Webserver")
        tcpLed = signalRow(sigGrid1, "TCP Logstream")
        statusText = TextView(this).apply {
            text = "Live-Log in der App entfernt. Diagnose laeuft primaer ueber SMB."
            textSize = 11f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 10, 0, 0)
        }
        statusCard.addView(statusText)
        currentPage = advancedPage
        val logCard = card("Current Log")
        logCard.addView(TextView(this).apply {
            text = "Live Status wird oben im HOME-Tab gezeigt. Ausfuehrliche Logs laufen ueber SMB/TCP."
            textSize = 11f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 10, 0, 0)
        })
        currentPage = homePage

        val controlCard = card("Light Control")
        val lampLed = signalRow(controlCard, "Unit 1 Connected / Online")
        val lampValueText = TextView(this).apply {
            text = "Status: ${RuntimeStatus.lastState} ${RuntimeStatus.lastBrightness}"
            textSize = 12f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 4, 0, 10)
        }
        controlCard.addView(lampValueText)
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlCard.addView(quickRow)
        val onBtn = wideButton("ON", 1.2f)
        val offBtn = wideButton("OFF", 1.2f)
        val p40 = wideButton("40%", 0.85f).apply { textSize = 10f }
        listOf(onBtn, offBtn, p40).forEach { quickRow.addView(it) }
        val seek = SeekBar(this).apply { max = 255; progress = RuntimeStatus.lastBrightness.coerceIn(0,255) }
        controlCard.addView(label("Auto Apply Brightness"))
        controlCard.addView(seek)
        lampLedRef = lampLed
        lampValueRef = lampValueText
        setLed(lampLed, RuntimeStatus.lastOnline)

        val roadmapCard = card("Bridge Status")
        roadmapCard.addView(TextView(this).apply {
            text = "Licht: App/Web/MQTT • Szenen: App/Web/MQTT/Home Assistant"
            textSize = 12f
            setTextColor(lime)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 6, 0, 4)
        })
        roadmapCard.addView(TextView(this).apply {
            text = "Setup: Scan → Auswahl → Passwort → API Fetch"
            textSize = 11f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 4, 0, 4)
        })
        val roadmapRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scenePreview = button("SCENES OK")
        val groupPreview = button("GROUPS READY")
        scenePreview.isEnabled = false
        groupPreview.isEnabled = false
        roadmapRow.addView(scenePreview)
        roadmapRow.addView(groupPreview)
        roadmapCard.addView(roadmapRow)

        val scenesCard = card("Scenes")
        val scenesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scenesCard.addView(scenesContainer)
        fun sceneCommand(sceneId: Int, sceneName: String) {
            statusText.text = "Scene wird geschaltet: $sceneName"
            startService(Intent(this, CasambiBridgeService::class.java).apply {
                action = CasambiBridgeService.ACTION_SCENE
                putExtra(CasambiBridgeService.EXTRA_SCENE_ID, sceneId)
                putExtra(CasambiBridgeService.EXTRA_SCENE_NAME, sceneName)
            })
        }
        fun refreshSceneButtons() {
            scenesContainer.removeAllViews()
            val scenes = SceneStore.loadScenes(this)
            if (scenes.isEmpty()) {
                scenesContainer.addView(TextView(this).apply {
                    text = "Noch keine Szenen gespeichert. Bitte FETCH API ausführen."
                    textSize = 11f
                    setTextColor(textMuted)
                    setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                })
            } else {
                scenes.forEach { scene ->
                    val b = button(scene.name.uppercase()).apply {
                        textSize = 11f
                        minHeight = 52
                        minimumHeight = 52
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 4, 0, 4)
                        }
                    }
                    b.setOnClickListener { sceneCommand(scene.id, scene.name) }
                    scenesContainer.addView(b)
                }
            }
        }
        sceneRefresh = { refreshSceneButtons() }
        refreshSceneButtons()

        currentPage = settingsPage
        val configCard = card("Config Matrix")
        val mac = field(configCard, "Casambi BLE MAC", c.casambiMac)
        val network = field(configCard, "Casambi Netzwerkname optional", c.casambiNetworkName)
        val casambiPass = field(configCard, "Casambi Passwort optional", c.casambiPassword, true)
        currentPage = advancedPage
        val advancedKeyCard = card("Advanced Key / Legacy")
        advancedKeyCard.addView(TextView(this).apply {
            text = "Nur Fallback. Normalerweise API Fetch verwenden."
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 4, 0, 6)
        })
        val protocol = field(advancedKeyCard, "Casambi Protocol Version", c.casambiProtocolVersion.toString())
        val keyId = field(advancedKeyCard, "Casambi Key ID API Fallback", c.casambiKeyId.toString())
        val keyHex = field(advancedKeyCard, "Casambi Key HEX API Fallback", c.casambiKeyHex, true)
        val mqttHost = field(configCard, "MQTT Host/IP", c.mqttHost)
        val mqttPort = field(configCard, "MQTT Port", c.mqttPort.toString())
        val mqttUser = field(configCard, "MQTT User", c.mqttUser)
        val mqttPass = field(configCard, "MQTT Passwort", c.mqttPassword, true)
        val baseTopic = field(configCard, "MQTT Base Topic", c.baseTopic)
        val discoveryPrefix = field(configCard, "HA Discovery Prefix", c.discoveryPrefix)
        val smbServer = field(configCard, "SMB Server/IP", c.smbServer)
        val smbShare = field(configCard, "SMB Freigabe", c.smbShare)
        val smbPath = field(configCard, "SMB Pfad", c.smbPath)
        val smbDomain = field(configCard, "SMB Domain/Workgroup", c.smbDomain)
        val smbUser = field(configCard, "SMB User", c.smbUser)
        val smbPassword = field(configCard, "SMB Passwort", c.smbPassword, true)
        val tcpPort = field(configCard, "TCP Logstream Port", c.tcpLogPort.toString())
        val webPort = field(configCard, "Webinterface Port", c.webInterfacePort.toString())
        val saveSettings = button("EINSTELLUNGEN SPEICHERN").apply { visibility = View.GONE }
        configCard.addView(saveSettings)

        fun switchRow(parent: LinearLayout, text: String, checked: Boolean): Pair<Switch, TextView> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = led()
            val sw = Switch(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(textMain)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                isChecked = checked
            }
            row.addView(l)
            row.addView(sw)
            parent.addView(row)
            return sw to l
        }

        currentPage = settingsPage
        val toggleCard = card("System Toggles")
        val (smbSwitch, smbSwitchLed) = switchRow(toggleCard, "SMB Logging", c.smbDebugEnabled)
        val (webSwitch, webSwitchLed) = switchRow(toggleCard, "Web Server", c.webInterfaceEnabled)
        val (tcpSwitch, tcpSwitchLed) = switchRow(toggleCard, "TCP Logstream", c.tcpLogEnabled)
        val (autoApiSwitch, autoApiSwitchLed) = switchRow(toggleCard, "Auto API Fetch", c.autoApiFetchEnabled)

        currentPage = controlPage
        val actionCard = card("Actions")
        fun actionGridButton(label: String): Button = button(label).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 4, 4, 4)
            }
        }
        fun actionRow(left: Button, right: Button) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(left)
            row.addView(right)
            actionCard.addView(row)
        }
        val start = actionGridButton("START")
        val stop = actionGridButton("STOP")
        val mqttTest = actionGridButton("MQTT")
        val save = actionGridButton("SAVE")
        val ble = actionGridButton("BLE")
        val fetchApi = actionGridButton("FETCH API")
        val scanBt = actionGridButton("SCAN CASAMBI")
        val backup = actionGridButton("BACKUP SMB")
        val restore = actionGridButton("RESTORE SMB")
        val dashboardExport = actionGridButton("DASHBOARD")
        val spacer = actionGridButton(" ").apply { isEnabled = false; setBackgroundColor(panel) }
        actionRow(start, stop)
        actionRow(fetchApi, scanBt)
        actionRow(save, mqttTest)
        actionRow(ble, backup)
        actionRow(restore, dashboardExport)

        setContentView(scroll)

        data class DiscoveredCasambiDevice(
            val address: String,
            val name: String,
            val rssi: Int,
            val manufacturer963: Boolean,
            val casaUuid: Boolean
        )
        val discoveredCasambiDevices = linkedMapOf<String, DiscoveredCasambiDevice>()

        fun currentConfig() = BridgeConfig(
            casambiMac = mac.text.toString().trim(),
            casambiNetworkName = network.text.toString(),
            casambiPassword = casambiPass.text.toString(),
            casambiProtocolVersion = protocol.text.toString().toIntOrNull() ?: 11,
            casambiKeyId = keyId.text.toString().toIntOrNull() ?: 2,
            casambiKeyHex = keyHex.text.toString().trim(),
            mqttHost = mqttHost.text.toString().trim(),
            mqttPort = mqttPort.text.toString().toIntOrNull() ?: 1883,
            mqttUser = mqttUser.text.toString(),
            mqttPassword = mqttPass.text.toString(),
            baseTopic = baseTopic.text.toString().ifBlank { "casambi_bridge" },
            discoveryPrefix = discoveryPrefix.text.toString().ifBlank { "homeassistant" },
            smbDebugEnabled = smbSwitch.isChecked,
            smbServer = smbServer.text.toString().trim(),
            smbShare = smbShare.text.toString().trim(),
            smbPath = smbPath.text.toString().trim().ifBlank { "casambi_debug" },
            smbDomain = smbDomain.text.toString().trim(),
            smbUser = smbUser.text.toString(),
            smbPassword = smbPassword.text.toString(),
            tcpLogEnabled = tcpSwitch.isChecked,
            tcpLogPort = tcpPort.text.toString().toIntOrNull() ?: 5555,
            webInterfaceEnabled = webSwitch.isChecked,
            webInterfacePort = webPort.text.toString().toIntOrNull() ?: 8080,
            autoApiFetchEnabled = autoApiSwitch.isChecked
        )

        fun setSwitchLeds() {
            if (mqttHost.text.toString().isBlank()) setLed(mqttStatusLed, false)
            setLed(smbLed, smbSwitch.isChecked)
            setLed(webLed, webSwitch.isChecked)
            setLed(tcpLed, tcpSwitch.isChecked)
            setLed(smbSwitchLed, smbSwitch.isChecked)
            setLed(webSwitchLed, webSwitch.isChecked)
            setLed(tcpSwitchLed, tcpSwitch.isChecked)
            setLed(autoApiSwitchLed, autoApiSwitch.isChecked)
        }
        setSwitchLeds()
        var suppressDirty = false
        fun markSettingsDirty() { if (!suppressDirty) saveSettings.visibility = View.VISIBLE }
        fun watchField(e: EditText, onChange: (() -> Unit)? = null) {
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { markSettingsDirty(); onChange?.invoke() }
                override fun afterTextChanged(editable: Editable?) {}
            })
        }
        listOf(mac, network, casambiPass, protocol, keyId, keyHex, mqttHost, mqttPort, mqttUser, mqttPass, baseTopic, discoveryPrefix, smbServer, smbShare, smbPath, smbDomain, smbUser, smbPassword, tcpPort, webPort).forEach { watchField(it) }
        smbSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        webSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        tcpSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        autoApiSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }

        currentPage = setupPage
        val setupCard = card("Casambi Setup")
        setupCard.addView(TextView(this).apply {
            text = "1. Scan starten  2. Gerät auswählen  3. Passwort eingeben  4. Hinzufügen / API Fetch"
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 6, 0, 8)
        })
        val setupScan = button("SCAN CASAMBI")
        val setupReset = button("RESET CASAMBI CONFIG")
        val setupAddFetch = button("ADD / API FETCH")
        val setupDeviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val setupStatus = TextView(this).apply {
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 8, 0, 8)
        }
        setupCard.addView(setupScan)
        setupCard.addView(setupDeviceList)
        val setupPass = field(setupCard, "Netzwerkpasswort", c.casambiPassword, true)
        setupCard.addView(setupStatus)
        setupCard.addView(setupAddFetch)
        setupCard.addView(setupReset)
        setupCard.addView(TextView(this).apply {
            text = "Reset löscht nur Casambi-MAC, Netzwerkname, Key und Szenen. MQTT/SMB/Web bleiben erhalten."
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 8, 0, 0)
        })
        fun refreshSetupStatus() {
            val scenes = SceneStore.loadScenes(this)
            val hasMac = mac.text.toString().isNotBlank()
            val hasPassword = setupPass.text.toString().isNotBlank() || casambiPass.text.toString().isNotBlank()
            val hasKey = keyHex.text.toString().isNotBlank()
            setupStatus.text = "Gerät: ${if (hasMac) "OK" else "fehlt"} • Passwort: ${if (hasPassword) "OK" else "fehlt"} • API Key: ${if (hasKey) "OK" else "fehlt"} • Szenen: ${scenes.size}"
            setupAddFetch.isEnabled = hasMac && hasPassword
            setupAddFetch.alpha = if (setupAddFetch.isEnabled) 1.0f else 0.45f
        }
        fun refreshSetupDeviceList() {
            setupDeviceList.removeAllViews()
            if (discoveredCasambiDevices.isEmpty()) {
                setupDeviceList.addView(TextView(this).apply {
                    text = "Noch keine Casambi-Geräte gefunden."
                    textSize = 11f
                    setTextColor(textMuted)
                    setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    setPadding(0, 10, 0, 10)
                })
                refreshSetupStatus()
                return
            }
            discoveredCasambiDevices.values.sortedByDescending { it.rssi }.forEach { d ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
                row.addView(TextView(this).apply {
                    val ok = if (d.manufacturer963 && d.casaUuid) "CASAMBI OK" else "Kandidat"
                    text = "$ok • ${d.name.ifBlank { "Unbenannt" }} • ${d.address} • RSSI ${d.rssi}\nmanufacturer963=${d.manufacturer963} casaUuid=${d.casaUuid}"
                    textSize = 10f
                    setTextColor(textMain)
                    setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                })
                val choose = button("AUSWÄHLEN")
                choose.setOnClickListener {
                    mac.setText(d.address)
                    refreshSetupStatus()
                    statusText.text = "Casambi Gerät ausgewählt: ${d.address}"
                    LogBus.log("Setup Device ausgewaehlt: name=${d.name} address=${d.address} rssi=${d.rssi} manufacturer963=${d.manufacturer963} casaUuid=${d.casaUuid}")
                }
                row.addView(choose)
                setupDeviceList.addView(row)
            }
            refreshSetupStatus()
        }
        watchField(setupPass) {
            if (!suppressDirty && casambiPass.text.toString() != setupPass.text.toString()) casambiPass.setText(setupPass.text.toString())
            refreshSetupStatus()
        }
        refreshSetupDeviceList()

        fun scanBluetoothForCasambi() {
            statusText.text = "Bluetooth Scan nach Casambi gestartet"
            LogBus.log("Bluetooth Scan nach Casambi gestartet: manufacturer=$casambiManufacturerId service=$casambiServiceUuid")
            val manager = getSystemService(BluetoothManager::class.java)
            val scanner = manager?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                LogBus.log("Bluetooth Scan Fehler: Scanner nicht verfuegbar")
                statusText.text = "Bluetooth Scanner nicht verfuegbar"
                return
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord
                    val device = result.device
                    val name = try { device.name ?: record?.deviceName ?: "" } catch (_: SecurityException) { record?.deviceName ?: "" }
                    val address = device.address ?: ""
                    val manufacturerMatch = record?.manufacturerSpecificData?.get(casambiManufacturerId) != null
                    val serviceMatch = record?.serviceUuids?.any { it.uuid == casambiServiceUuid } == true
                    val currentMacMatches = address.replace(":", "").equals(mac.text.toString().replace(":", ""), true)
                    val nameFallback = name.contains("casambi", true)
                    if ((manufacturerMatch && serviceMatch) || currentMacMatches || nameFallback) {
                        val deviceInfo = DiscoveredCasambiDevice(address, name, result.rssi, manufacturerMatch, serviceMatch)
                        discoveredCasambiDevices[address] = deviceInfo
                        refreshSetupDeviceList()
                        if (mac.text.toString().isBlank()) mac.setText(address)
                        statusText.text = "Casambi gefunden: $name $address"
                        LogBus.log("Bluetooth Scan Treffer: name=$name address=$address rssi=${result.rssi} manufacturer963=$manufacturerMatch casaUuid=$serviceMatch")
                        flash(bleRxLed)
                    } else if (manufacturerMatch || serviceMatch) {
                        LogBus.log("Bluetooth Scan Kandidat ignoriert: name=$name address=$address manufacturer963=$manufacturerMatch casaUuid=$serviceMatch")
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    LogBus.log("Bluetooth Scan Fehler code=$errorCode")
                    statusText.text = "Bluetooth Scan Fehler code=$errorCode"
                }
            }
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                ui.postDelayed({
                    try { scanner.stopScan(callback) } catch (_: Throwable) {}
                    LogBus.log("Bluetooth Scan beendet")
                }, 9000)
            } catch (t: Throwable) {
                LogBus.log("Bluetooth Scan Fehler: ${t.message}")
                statusText.text = "Bluetooth Scan Fehler"
            }
        }

        fun applyConfig(x: BridgeConfig) {
            suppressDirty = true
            mac.setText(x.casambiMac); network.setText(x.casambiNetworkName); casambiPass.setText(x.casambiPassword); setupPass.setText(x.casambiPassword)
            protocol.setText(x.casambiProtocolVersion.toString()); keyId.setText(x.casambiKeyId.toString()); keyHex.setText(x.casambiKeyHex)
            mqttHost.setText(x.mqttHost); mqttPort.setText(x.mqttPort.toString()); mqttUser.setText(x.mqttUser); mqttPass.setText(x.mqttPassword)
            baseTopic.setText(x.baseTopic); discoveryPrefix.setText(x.discoveryPrefix)
            smbServer.setText(x.smbServer); smbShare.setText(x.smbShare); smbPath.setText(x.smbPath); smbDomain.setText(x.smbDomain); smbUser.setText(x.smbUser); smbPassword.setText(x.smbPassword)
            tcpPort.setText(x.tcpLogPort.toString()); webPort.setText(x.webInterfacePort.toString())
            smbSwitch.isChecked = x.smbDebugEnabled; webSwitch.isChecked = x.webInterfaceEnabled; tcpSwitch.isChecked = x.tcpLogEnabled; autoApiSwitch.isChecked = x.autoApiFetchEnabled
            setSwitchLeds()
            refreshSetupStatus()
            saveSettings.visibility = View.GONE
            suppressDirty = false
        }

        fun saveNow() {
            if (setupPass.text.toString() != casambiPass.text.toString()) casambiPass.setText(setupPass.text.toString())
            val cfg = currentConfig()
            ConfigStore.save(this, cfg)
            DebugExporter.configure(cfg)
            TcpLogServer.configure(cfg)
            WebControlServer.configure(this, cfg)
            saveSettings.visibility = View.GONE
            refreshSetupStatus()
            statusText.text = "Konfiguration gespeichert"
            LogBus.log("Konfiguration gespeichert")
        }

        fun command(state: String, brightness: Int? = null) {
            saveNow()
            startService(Intent(this, CasambiBridgeService::class.java).apply {
                action = CasambiBridgeService.ACTION_COMMAND
                putExtra(CasambiBridgeService.EXTRA_STATE, state)
                if (brightness != null) putExtra(CasambiBridgeService.EXTRA_BRIGHTNESS, brightness)
            })
        }

        onBtn.setOnClickListener { command("ON", null) }
        offBtn.setOnClickListener { command("OFF", null) }
        p40.setOnClickListener { command("ON", 102) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var pending: Runnable? = null
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                pending?.let { ui.removeCallbacks(it) }
                pending = Runnable { command(if (progress <= 0) "OFF" else "ON", progress.coerceIn(0,255)) }
                ui.postDelayed(pending!!, 450)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val progress = bar?.progress ?: 0
                pending?.let { ui.removeCallbacks(it) }
                command(if (progress <= 0) "OFF" else "ON", progress.coerceIn(0,255))
            }
        })

        save.setOnClickListener { saveNow() }
        saveSettings.setOnClickListener { saveNow() }
        start.setOnClickListener { saveNow(); startService(Intent(this, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START }) }
        stop.setOnClickListener { startService(Intent(this, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_STOP }) }
        mqttTest.setOnClickListener { saveNow(); MqttBridge(currentConfig(), LogBus::log).also { it.connectSafe(); it.publishTest(); it.disconnect() } }
        ble.setOnClickListener { saveNow(); startService(Intent(this, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_BLE_TEST }) }
        backup.setOnClickListener {
            val cfg = currentConfig()
            Thread {
                try {
                    val url = ConfigBackup.exportFullToSmb(this@MainActivity, cfg)
                    runOnUiThread { statusText.text = "Full Backup gespeichert"; LogBus.log("Full Backup gespeichert: $url") }
                } catch (t: Throwable) {
                    runOnUiThread { statusText.text = "Config Backup Fehler"; LogBus.log("Config Backup Fehler: ${t.message}") }
                }
            }.start()
        }
        restore.setOnClickListener {
            val cfg = currentConfig()
            Thread {
                try {
                    val restored = ConfigBackup.restoreFullFromSmb(this@MainActivity, cfg)
                    ConfigStore.save(this, restored)
                    runOnUiThread { applyConfig(restored); statusText.text = "Full Restore erfolgreich"; LogBus.log("Full Restore erfolgreich") }
                } catch (t: Throwable) {
                    runOnUiThread { statusText.text = "Config Restore Fehler"; LogBus.log("Config Restore Fehler: ${t.message}") }
                }
            }.start()
        }

        dashboardExport.setOnClickListener {
            val cfg = currentConfig()
            Thread {
                try {
                    val url = DashboardExporter.exportToSmb(this@MainActivity, cfg)
                    runOnUiThread { statusText.text = "Dashboard YAML exportiert"; LogBus.log("Dashboard YAML exportiert: $url") }
                } catch (t: Throwable) {
                    runOnUiThread { statusText.text = "Dashboard Export Fehler"; LogBus.log("Dashboard Export Fehler: ${t.message}") }
                }
            }.start()
        }
        scanBt.setOnClickListener { scanBluetoothForCasambi() }
        setupScan.setOnClickListener { scanBluetoothForCasambi() }
        setupReset.setOnClickListener {
            val cfg = currentConfig().copy(casambiMac = "", casambiNetworkName = "", casambiProtocolVersion = 11, casambiKeyId = 2, casambiKeyHex = "")
            ConfigStore.save(this, cfg)
            SceneStore.saveScenes(this, emptyList())
            SceneStore.saveGroups(this, emptyList())
            SceneStore.saveUnits(this, emptyList())
            discoveredCasambiDevices.clear()
            applyConfig(cfg)
            refreshSceneButtons()
            refreshSetupDeviceList()
            statusText.text = "Casambi Config zurückgesetzt"
            LogBus.log("Setup Reset: Casambi Config, KeyStore und Szenen lokal geloescht")
        }
        setupAddFetch.setOnClickListener { fetchApi.performClick() }

        if (mac.text.toString().isBlank()) ui.postDelayed({ scanBluetoothForCasambi() }, 900)

        fetchApi.setOnClickListener {
            val cfg = currentConfig()
            statusText.text = "Casambi API Fetch gestartet"
            LogBus.log("Casambi API Fetch gestartet")
            Thread {
                try {
                    val result = CasambiCloudApi.fetch(cfg)
                    val updated = cfg.copy(
                        casambiNetworkName = result.networkName ?: cfg.casambiNetworkName,
                        casambiProtocolVersion = result.protocolVersion ?: cfg.casambiProtocolVersion,
                        casambiKeyId = result.keyId ?: cfg.casambiKeyId,
                        casambiKeyHex = result.keyHex ?: cfg.casambiKeyHex
                    )
                    ConfigStore.save(this, updated)
                    SceneStore.saveScenes(this, result.scenes)
                    SceneStore.saveGroups(this, result.groups)
                    SceneStore.saveUnits(this, result.units)
                    RuntimeStatus.markSync()
                    val sceneNames = result.scenes.joinToString { "${it.first}:${it.second}" }
                    val groupNames = result.groups.joinToString { "${it.first}:${it.second}" }
                    val unitNames = result.units.joinToString { "${it.first}:${it.second}" }
                    runOnUiThread {
                        applyConfig(updated)
                        refreshSceneButtons()
                        refreshSetupStatus()
                        statusText.text = "API Fetch OK: ${result.rawSummary}"
                        LogBus.log("Casambi API Fetch OK: ${result.rawSummary}")
                        if (sceneNames.isNotBlank()) LogBus.log("Scenes: $sceneNames")
                        if (groupNames.isNotBlank()) LogBus.log("Groups: $groupNames")
                        if (unitNames.isNotBlank()) LogBus.log("Units: $unitNames")
                        if (result.keyHex != null) LogBus.log("KeyStore ueber API geladen und lokal gespeichert")
                        LogBus.log("API Fetch: Bridge wird mit aktualisiertem Key neu gestartet")
                        startService(Intent(this, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        statusText.text = "API Fetch Fehler"
                        LogBus.log("Casambi API Fetch Fehler: ${t.message}")
                    }
                }
            }.start()
        }

        ui.postDelayed({
            saveNow()
            statusText.text = "Auto-Start: Bridge wird gestartet"
            startService(Intent(this, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_START })
        }, 650)
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, height) })
    }

    private fun lampValueTextSafe(): TextView? = lampValueRef

    private fun setLedSafeLamp() {
        lampLedRef?.let { setLed(it, RuntimeStatus.lastOnline) }
    }

    private fun setLed(led: TextView, on: Boolean) {
        led.setTextColor(if (on) ps2Green else darkLed)
    }

    private fun flash(led: TextView) {
        val colors = listOf(ps2Blue, ps2Purple, ps2Green, ps2Blue)
        colors.forEachIndexed { index, color ->
            ui.postDelayed({ led.setTextColor(color) }, (index * 70L) + Random.nextLong(0, 35))
        }
        ui.postDelayed({ led.setTextColor(darkLed) }, 520 + Random.nextLong(0, 120))
    }

    override fun onResume() {
        super.onResume()
        LogBus.addListener(logListener)
    }

    override fun onPause() {
        super.onPause()
        LogBus.removeListener(logListener)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }
}
