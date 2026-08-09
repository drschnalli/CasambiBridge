package de.pascal.casambibridge

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream

class MainActivity : AppCompatActivity() {
    private val casambiManufacturerId = 963
    private val casambiServiceUuid = UUID.fromString("c9ffde48-ca5a-0000-ab83-8f519b482f77")
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var bleRxLed: TextView
    private lateinit var bleTxLed: TextView
    private lateinit var bleConnectedLed: TextView
    private lateinit var directRxLed: TextView
    private lateinit var directTxLed: TextView
    private lateinit var mqttStatusLed: TextView
    private lateinit var mqttInLed: TextView
    private lateinit var mqttOutLed: TextView
    private lateinit var smbLed: TextView
    private lateinit var webLed: TextView
    private lateinit var tcpLed: TextView
    private lateinit var autoStartLed: TextView
    private lateinit var directLed: TextView
    private lateinit var mdnsLed: TextView
    private var lampLedRef: TextView? = null
    private var lampValueRef: TextView? = null
    private var sceneRefresh: (() -> Unit)? = null
    private var mqttMonitorVisible: Boolean = false
    private var selectedReturnAppPackage: String = ""
    private var returnAppShowIcon: Boolean = false

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
        if (::bleRxLed.isInitialized) {
            setLedSafeLamp()
            setLed(bleConnectedLed, RuntimeStatus.bleConnected, ps2Blue)
        }
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
            (line.contains("MQTT Command Unit") || line.contains("MQTT Command Callback")) && mqttMonitorVisible -> flash(mqttInLed)
            (line.contains("MQTT State Unit") || line.contains("publish", ignoreCase = true)) && mqttMonitorVisible -> flash(mqttOutLed)
            line.contains("Direct API RX") -> flashPulse(directRxLed, ps2Purple)
            line.contains("Direct API TX") || line.contains("Direct Command") -> flashPulse(directTxLed, lime)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        val c = ConfigStore.load(this)
        RuntimeStatus.lastSyncMillis = ConfigStore.lastSyncMillis(this)
        selectedReturnAppPackage = c.returnAppPackage
        returnAppShowIcon = c.returnAppShowIcon

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(bg)
        }
        scroll.addView(root)

        val headerBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerBlock.addView(TextView(this).apply {
            text = "🌴 CASAMBI JUNGLE\n// v0.8.3"
            textSize = 20f
            setTextColor(leaf)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        })
        val topButtonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun topButton(label: String, bgColor: Int): Button = Button(this).apply {
            text = label
            textSize = 7.8f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setBackgroundColor(bgColor)
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(3, 0, 3, 0) }
        }
        val menuButton = topButton("☰ MENU", Color.rgb(4, 88, 98))
        val backgroundButton = topButton("BACKGROUND", Color.rgb(0, 84, 112))
        val returnAppButton = topButton("RETURN APP", Color.rgb(0, 112, 82))
        val forceStopButton = topButton("FORCE STOP", Color.rgb(78, 0, 100))
        backgroundButton.setOnClickListener {
            LogBus.log("App in Background verschoben, Bridge bleibt aktiv")
            moveTaskToBack(true)
        }
        returnAppButton.setOnClickListener {
            val pkg = selectedReturnAppPackage.ifBlank { ConfigStore.load(this).returnAppPackage }
            val launch = if (pkg.isNotBlank()) packageManager.getLaunchIntentForPackage(pkg) else null
            if (launch != null) {
                LogBus.log("Return App gestartet: $pkg")
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                LogBus.log("Return App nicht konfiguriert oder nicht startbar, gehe in Background")
                moveTaskToBack(true)
            }
        }
        forceStopButton.setOnClickListener {
            startService(Intent(this@MainActivity, CasambiBridgeService::class.java).apply { action = CasambiBridgeService.ACTION_STOP })
            finishAndRemoveTask()
            ui.postDelayed({ Process.killProcess(Process.myPid()) }, 250)
        }
        topButtonRow.addView(menuButton)
        topButtonRow.addView(backgroundButton)
        topButtonRow.addView(returnAppButton)
        topButtonRow.addView(forceStopButton)
        headerBlock.addView(topButtonRow)
        root.addView(headerBlock)
        root.addView(TextView(this).apply {
            text = "powered by Sambesi"
            textSize = 9f
            setTextColor(lime)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 7, 0, 2)
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
        val sideMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(4, 32, 24))
        }
        // Tabs are added below the pages so they stay out of the way on small screens.

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
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val pageArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sideMenu.layoutParams = LinearLayout.LayoutParams(260, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 10, 0) }
        contentRow.addView(sideMenu)
        contentRow.addView(pageArea)
        pages.forEach { pageArea.addView(it) }
        pageArea.addView(tabBar)
        root.addView(contentRow)
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

        fun menuItem(label: String, target: LinearLayout): Button = Button(this).apply {
            text = label
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(0, 18, 8))
            setBackgroundColor(leaf)
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 4) }
            setOnClickListener { showPage(target); sideMenu.visibility = View.GONE }
        }
        sideMenu.addView(TextView(this).apply {
            text = "☰ CASAMBI JUNGLE MENU"
            textSize = 14f
            setTextColor(leaf)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        sideMenu.addView(menuItem("HOME / MONITOR", homePage))
        sideMenu.addView(menuItem("SETUP", setupPage))
        sideMenu.addView(menuItem("CONTROL", controlPage))
        sideMenu.addView(menuItem("SETTINGS", settingsPage))
        sideMenu.addView(menuItem("TOOLS / ADVANCED", advancedPage))
        menuButton.setOnClickListener { sideMenu.visibility = if (sideMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE; statusText.takeIf { ::statusText.isInitialized }?.text = if (sideMenu.visibility == View.VISIBLE) "Menu geöffnet" else "Menu geschlossen" }

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
        val statusCard = card("Djungle Monitor")
        statusCard.addView(TextView(this).apply {
            val sceneCount = SceneStore.loadScenes(this@MainActivity).size
            val networkName = c.casambiNetworkName.ifBlank { "nicht gesetzt" }
            val mqttText = if (c.mqttEnabled && c.mqttHost.isNotBlank()) "MQTT aktiv" else "MQTT aus"
            val directText = if (c.directModeEnabled) "Direct aktiv" else "Direct aus"
            text = "🌴 $networkName  •  💡 Units: 1  •  🎭 Scenes: $sceneCount\n$mqttText  •  $directText"
            textSize = 11f
            setTextColor(textMain)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 6, 0, 8)
        })
        val sigGrid1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusCard.addView(sigGrid1)
        val sigRowBle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sigRowDirect = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sigRowSystem = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sigRowMqtt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sigGrid1.addView(sigRowBle)
        sigGrid1.addView(sigRowDirect)
        sigGrid1.addView(sigRowSystem)
        sigGrid1.addView(sigRowMqtt)
        bleConnectedLed = signalRow(sigRowBle, "ᛒ BLE")
        bleRxLed = signalRow(sigRowBle, "BLE RX")
        bleTxLed = signalRow(sigRowBle, "BLE TX")
        directLed = signalRow(sigRowDirect, "⇄ API")
        directRxLed = signalRow(sigRowDirect, "← RX")
        directTxLed = signalRow(sigRowDirect, "→ TX")
        mdnsLed = signalRow(sigRowSystem, "mDNS")
        webLed = signalRow(sigRowSystem, "Web")
        smbLed = signalRow(sigRowSystem, "💾 SMB")
        tcpLed = signalRow(sigRowSystem, "📡 TCP")
        autoStartLed = signalRow(sigRowSystem, "Auto")
        mqttStatusLed = signalRow(sigRowMqtt, "MQTT")
        mqttInLed = signalRow(sigRowMqtt, "MQTT IN")
        mqttOutLed = signalRow(sigRowMqtt, "MQTT OUT")
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
        val currentUnitNameForControl = SceneStore.loadUnits(this).firstOrNull()?.name ?: "Casambi Light 1"
        val lampLed = signalRow(controlCard, "$currentUnitNameForControl Connected / Online")
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
        currentPage = settingsPage
        val returnAppCard = card("Return App")
        data class LaunchableApp(val label: String, val packageName: String)
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = packageManager.queryIntentActivities(launchIntent, 0)
            .map { LaunchableApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        val appChoices = listOf(LaunchableApp("Launcher / keine Ziel-App", "")) + launchableApps
        fun updateReturnAppButtonText() {
            val choice = appChoices.firstOrNull { it.packageName == selectedReturnAppPackage }
            val label = choice?.label ?: "Launcher"
            returnAppButton.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            returnAppButton.compoundDrawablePadding = 0
            if (returnAppShowIcon && !selectedReturnAppPackage.isBlank()) {
                val icon = runCatching { packageManager.getApplicationIcon(selectedReturnAppPackage) }.getOrNull()
                if (icon != null) {
                    icon.setBounds(0, 0, 22, 22)
                    returnAppButton.setCompoundDrawables(null, icon, null, null)
                    returnAppButton.compoundDrawablePadding = 2
                    returnAppButton.text = "RETURN APP"
                } else {
                    returnAppButton.text = "RETURN APP\n$label"
                }
            } else {
                returnAppButton.text = if (selectedReturnAppPackage.isBlank()) "RETURN APP\nLauncher" else "RETURN APP\n$label"
            }
        }
        updateReturnAppButtonText()
        returnAppCard.addView(TextView(this).apply {
            text = "Ziel-App fuer RETURN APP aus installierten Launcher-Apps auswaehlen. BACKGROUND nutzt immer den Android Launcher und laesst die Bridge weiterlaufen."
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 6, 0, 8)
        })
        val returnAppSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, appChoices.map { it.label })
        }
        returnAppCard.addView(returnAppSpinner)
        val initialReturnIndex = appChoices.indexOfFirst { it.packageName == c.returnAppPackage }.takeIf { it >= 0 } ?: 0
        returnAppSpinner.setSelection(initialReturnIndex)
        selectedReturnAppPackage = appChoices[initialReturnIndex].packageName
        updateReturnAppButtonText()
        val saveSettings = button("EINSTELLUNGEN SPEICHERN").apply { visibility = View.GONE }
        configCard.addView(saveSettings)
        val permissionsCard = card("Scanner Permissions")
        val permissionStatusText = TextView(this).apply {
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 8, 0, 8)
        }
        val requestScanPermissionsButton = button("REQUEST SCANNER PERMISSIONS")
        val openLocationSettingsButton = button("OPEN LOCATION SETTINGS")
        val openWifiSettingsButton = button("OPEN WIFI SETTINGS")
        permissionsCard.addView(permissionStatusText)
        permissionsCard.addView(requestScanPermissionsButton)
        permissionsCard.addView(openLocationSettingsButton)
        permissionsCard.addView(openWifiSettingsButton)
        fun scannerPermissionStatus(): String {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val btScan = android.os.Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val btConnect = android.os.Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val changeWifi = ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
            val wifiNearby = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            val locationEnabled = runCatching { android.provider.Settings.Secure.getInt(contentResolver, android.provider.Settings.Secure.LOCATION_MODE) != android.provider.Settings.Secure.LOCATION_MODE_OFF }.getOrDefault(false)
            return "Location=$fine/$coarse  BLE_SCAN=$btScan  BLE_CONNECT=$btConnect  WIFI_CHANGE=$changeWifi  NEARBY_WIFI=$wifiNearby  LOC_ON=$locationEnabled\nAndroid 8.1: Legacy WiFi Scan nutzt BroadcastReceiver + cached scanResults."
        }
        fun refreshPermissionStatus() { permissionStatusText.text = scannerPermissionStatus() }
        refreshPermissionStatus()
        requestScanPermissionsButton.setOnClickListener {
            val before = scannerPermissionStatus()
            requestPermissionsIfNeeded()
            ui.postDelayed({
                refreshPermissionStatus()
                val after = scannerPermissionStatus()
                statusText.text = if (before == after && after.contains("true")) "Scanner Permissions: bereits erteilt" else "Scanner Permissions aktualisiert"
                LogBus.log("Scanner Permissions geprüft: $after")
            }, 600)
        }
        openLocationSettingsButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            statusText.text = "Android Standortdienste öffnen"
            LogBus.log("Scanner Permissions: Standortdienste Einstellungen geöffnet")
        }
        openWifiSettingsButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            statusText.text = "Android WLAN Einstellungen öffnen"
            LogBus.log("Scanner Permissions: WLAN Einstellungen geöffnet")
        }

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
        val (returnAppIconSwitch, returnAppIconSwitchLed) = switchRow(returnAppCard, "RETURN APP Icon anzeigen", c.returnAppShowIcon)

        currentPage = settingsPage
        val toggleCard = card("System Toggles")
        val (smbSwitch, smbSwitchLed) = switchRow(toggleCard, "SMB Logging", c.smbDebugEnabled)
        val (webSwitch, webSwitchLed) = switchRow(toggleCard, "Web Server", c.webInterfaceEnabled)
        val (tcpSwitch, tcpSwitchLed) = switchRow(toggleCard, "TCP Logstream", c.tcpLogEnabled)
        val (autoApiSwitch, autoApiSwitchLed) = switchRow(toggleCard, "Auto API Fetch", c.autoApiFetchEnabled)
        val (webSocketSwitch, webSocketSwitchLed) = switchRow(toggleCard, "WebSocket Live Updates", c.webSocketLiveEnabled)
        val (mqttModeSwitch, mqttModeSwitchLed) = switchRow(toggleCard, "MQTT Mode", c.mqttEnabled)
        val (directModeSwitch, directModeSwitchLed) = switchRow(toggleCard, "Direct Mode", c.directModeEnabled)
        val (networkDiscoverySwitch, networkDiscoverySwitchLed) = switchRow(toggleCard, "Network Discovery / mDNS", c.networkDiscoveryEnabled)
        val (autoStartSwitch, autoStartSwitchLed) = switchRow(toggleCard, "Autostart Bridge", c.autoStartEnabled)

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
        val dashboardExport = actionGridButton("MQTT YAML")
        val dashboardDirectExport = actionGridButton("DIRECT YAML")
        val spacer = actionGridButton(" ").apply { isEnabled = false; setBackgroundColor(panel) }
        actionRow(start, stop)
        actionRow(fetchApi, scanBt)
        actionRow(save, mqttTest)
        actionRow(ble, backup)
        actionRow(restore, dashboardExport)
        actionRow(dashboardDirectExport, spacer)


        currentPage = advancedPage
        val scanToolsCard = card("Scan Tools")
        scanToolsCard.addView(TextView(this).apply {
            text = "Netzwerk-, mDNS-, Bluetooth- und WLAN-Scanner. Ergebnisse koennen per SMB exportiert werden."
            textSize = 10f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 6, 0, 8)
        })
        val netRange = field(scanToolsCard, "Network Range CIDR leer = aktuelles /24", "")
        val netFilter = field(scanToolsCard, "Network Filter Name/IP/Port", "")
        val netPortMode = field(scanToolsCard, "Port Mode none/known/all/custom", "known")
        val netCustomPorts = field(scanToolsCard, "Custom Ports komma-separiert", "22,80,443,445,1883,5555,8080,8123")
        val netBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scanIpButton = actionGridButton("SCAN IP")
        val scanHostsButton = actionGridButton("SCAN HOSTS")
        val stopIpButton = actionGridButton("STOP IP")
        netBtnRow.addView(scanIpButton)
        netBtnRow.addView(scanHostsButton)
        scanToolsCard.addView(netBtnRow)
        val netBtnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        netBtnRow2.addView(stopIpButton)
        val scanMdnsButton = actionGridButton("SCAN mDNS")
        netBtnRow2.addView(scanMdnsButton)
        scanToolsCard.addView(netBtnRow2)
        val netBtnRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stopMdnsButton = actionGridButton("STOP mDNS")
        netBtnRow3.addView(stopMdnsButton)
        scanToolsCard.addView(netBtnRow3)
        val exportNetButton = button("EXPORT NETWORK SCAN SMB")
        scanToolsCard.addView(exportNetButton)
        val networkResultText = TextView(this).apply {
            text = "Noch kein Network Scan."
            textSize = 9f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 8, 0, 8)
        }
        scanToolsCard.addView(networkResultText)

        val btToolsCard = card("Bluetooth Scanner")
        val btFilter = field(btToolsCard, "Bluetooth Filter Name/MAC", "")
        val btRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scanBtAllButton = actionGridButton("SCAN BT ALL")
        val stopBtButton = actionGridButton("STOP BT")
        btRow.addView(scanBtAllButton)
        btRow.addView(stopBtButton)
        btToolsCard.addView(btRow)
        val btRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exportBtButton = actionGridButton("EXPORT BT")
        btRow2.addView(exportBtButton)
        btToolsCard.addView(btRow2)
        val bluetoothResultText = TextView(this).apply {
            text = "Noch kein Bluetooth Scan."
            textSize = 9f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 8, 0, 8)
        }
        btToolsCard.addView(bluetoothResultText)

        val wifiToolsCard = card("WiFi Scanner")
        val wifiFilter = field(wifiToolsCard, "WiFi Filter SSID/BSSID", "")
        val wifiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scanWifiButton = actionGridButton("SCAN WIFI")
        val stopWifiButton = actionGridButton("STOP WIFI")
        wifiRow.addView(scanWifiButton)
        wifiRow.addView(stopWifiButton)
        wifiToolsCard.addView(wifiRow)
        val wifiRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exportWifiButton = actionGridButton("EXPORT WIFI")
        wifiRow2.addView(exportWifiButton)
        wifiToolsCard.addView(wifiRow2)
        val wifiResultText = TextView(this).apply {
            text = "Noch kein WiFi Scan."
            textSize = 9f
            setTextColor(textMuted)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 8, 0, 8)
        }
        wifiToolsCard.addView(wifiResultText)

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
            autoApiFetchEnabled = autoApiSwitch.isChecked,
            webSocketLiveEnabled = webSocketSwitch.isChecked,
            mqttEnabled = mqttModeSwitch.isChecked,
            directModeEnabled = directModeSwitch.isChecked,
            networkDiscoveryEnabled = networkDiscoverySwitch.isChecked,
            autoStartEnabled = autoStartSwitch.isChecked,
            returnAppShowIcon = returnAppIconSwitch.isChecked,
            returnAppPackage = selectedReturnAppPackage
        )


        var lastNetworkScanText = ""
        var lastBluetoothScanText = ""
        var lastWifiScanText = ""
        var stopIpScan = false
        var stopBtScan = false
        var stopMdnsScan = false
        var stopWifiScan = false
        var activeBtScanner: android.bluetooth.le.BluetoothLeScanner? = null
        var activeBtCallback: ScanCallback? = null
        var activeMdnsManager: NsdManager? = null
        val activeMdnsListeners = mutableListOf<NsdManager.DiscoveryListener>()
        val bluetoothSessions = ArrayDeque<String>()
        val wifiSessions = ArrayDeque<String>()
        fun rememberSession(queue: ArrayDeque<String>, value: String) {
            if (value.isBlank()) return
            queue.addFirst(value)
            while (queue.size > 2) queue.removeLast()
        }
        fun currentSubnet24(): String {
            val ip = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.254") }
                    ?.hostAddress
            }.getOrNull() ?: "192.168.1.1"
            return ip.substringBeforeLast('.', "192.168.1") + ".0/24"
        }
        fun arpMacFor(ip: String): String {
            return runCatching {
                java.io.File("/proc/net/arp").readLines().drop(1).firstOrNull { line ->
                    line.trim().split(Regex("\\s+")).firstOrNull() == ip
                }?.trim()?.split(Regex("\\s+"))?.getOrNull(3) ?: "-"
            }.getOrDefault("-")
        }
        fun portListForScan(): List<Int> {
            val mode = netPortMode.text.toString().trim().lowercase()
            val custom = netCustomPorts.text.toString().split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct()
            val known = listOf(22, 53, 80, 443, 445, 1883, 5555, 8080, 8123)
            return when (mode) {
                "none", "noports", "off", "ping" -> emptyList()
                "all" -> (1..65535).toList()
                "custom" -> custom.ifEmpty { known }
                else -> custom.ifEmpty { known }
            }
        }
        fun simplePortOpen(host: String, port: Int, timeoutMs: Int = 170): Boolean = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)
        fun exportScanToSmb(prefix: String, content: String) {
            val cfg = currentConfig()
            Thread {
                try {
                    val ctx = DebugExporter.smbContext(cfg)
                    val dir = DebugExporter.smbDir(cfg)
                    SmbFile(dir, ctx).use { if (!it.exists()) it.mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val url = dir + "${prefix}_${stamp}.txt"
                    SmbFileOutputStream(SmbFile(url, ctx), false).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    runOnUiThread { statusText.text = "Scan Export gespeichert: $url"; LogBus.log("Scan Export gespeichert: $url") }
                } catch (t: Throwable) {
                    runOnUiThread { statusText.text = "Scan Export Fehler"; LogBus.log("Scan Export Fehler: ${t.message}") }
                }
            }.start()
        }
        fun scanIpNetwork() {
            stopIpScan = false
            val input = netRange.text.toString().trim().ifBlank { currentSubnet24() }
            val filter = netFilter.text.toString().trim().lowercase()
            val ports = portListForScan()
            networkResultText.text = "IP Scan laeuft: $input  Ports=${if (ports.isEmpty()) "none" else ports.size.toString()}"
            LogBus.log("Network Scanner: IP Scan gestartet range=$input ports=${if (ports.isEmpty()) "none" else ports.size.toString()}")
            Thread {
                val base = input.substringBefore('/').substringBeforeLast('.', "192.168.1")
                val rows = mutableListOf<String>()
                for (i in 1..254) {
                    if (stopIpScan) break
                    val ip = "$base.$i"
                    try {
                        val addr = InetAddress.getByName(ip)
                        val open = if (ports.isEmpty()) emptyList() else ports.filter { simplePortOpen(ip, it) }
                        val reachable = if (ports.isEmpty()) runCatching { addr.isReachable(220) }.getOrDefault(false) else open.isNotEmpty() || runCatching { addr.isReachable(180) }.getOrDefault(false)
                        if (reachable) {
                            val name = runCatching { addr.canonicalHostName }.getOrDefault("")
                            val macAddr = arpMacFor(ip)
                            val portText = if (ports.isEmpty()) "not scanned" else if (open.isEmpty()) "-" else open.joinToString(",")
                            val line = "$ip  name=${if (name == ip) "-" else name}  mac=$macAddr  ports=$portText"
                            if (filter.isBlank() || line.lowercase().contains(filter)) rows += line
                        }
                        if (i % 10 == 0 || rows.size % 5 == 0) {
                            runOnUiThread { networkResultText.text = "IP Scan $i/254  Treffer=${rows.size}  Ports=${if (ports.isEmpty()) "none" else ports.size.toString()}\n" + rows.sorted().joinToString("\n").ifBlank { "Suche..." } }
                        }
                    } catch (_: Throwable) {}
                }
                val result = "Network IP Scan $input  ports=${if (ports.isEmpty()) "none" else ports.take(40).joinToString(",")}${if (ports.size > 40) ",..." else ""}\n" + (rows.sorted().joinToString("\n").ifBlank { if (stopIpScan) "Scan gestoppt" else "Keine Treffer" })
                lastNetworkScanText = result
                runOnUiThread { networkResultText.text = result; LogBus.log("Network Scanner: IP Scan fertig Treffer=${rows.size}") }
            }.start()
        }
        fun scanMdnsServices() {
            stopMdnsScan = false
            networkResultText.text = "mDNS/Zeroconf Scan laeuft..."
            LogBus.log("Network Scanner: mDNS Scan gestartet")
            val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            activeMdnsManager = manager
            activeMdnsListeners.clear()
            val types = listOf("_casambi-jungle._tcp.", "_home-assistant._tcp.", "_http._tcp.", "_mqtt._tcp.", "_workstation._tcp.", "_googlecast._tcp.", "_hap._tcp.")
            val results = linkedSetOf<String>()
            fun render(finalText: Boolean = false) {
                val txt = "mDNS/Zeroconf Scan\n" + results.sorted().joinToString("\n").ifBlank { if (finalText) "Keine Treffer" else "Suche..." }
                lastNetworkScanText = txt
                runOnUiThread { networkResultText.text = txt }
            }
            types.forEachIndexed { typeIndex, type ->
                if (stopMdnsScan) return@forEachIndexed
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {}
                    override fun onServiceFound(service: NsdServiceInfo) {
                        runCatching {
                            manager.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    results += "${service.serviceName}  type=${service.serviceType}  resolveError=$errorCode"
                                    render(false)
                                }
                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    val host = serviceInfo.host?.hostAddress ?: "?"
                                    val attrs = if (android.os.Build.VERSION.SDK_INT >= 21) serviceInfo.attributes.map { (k, v) -> "$k=${String(v)}" }.joinToString(" ") else ""
                                    results += "${serviceInfo.serviceName}  type=${serviceInfo.serviceType}  host=$host:${serviceInfo.port}  $attrs"
                                    render(false)
                                }
                            })
                        }
                    }
                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { results += "$serviceType startError=$errorCode"; render(false); runCatching { manager.stopServiceDiscovery(this) } }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { runCatching { manager.stopServiceDiscovery(this) } }
                }
                activeMdnsListeners += listener
                runOnUiThread { networkResultText.text = "mDNS Scan ${typeIndex + 1}/${types.size}: $type\n" + results.sorted().joinToString("\n") }
                runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            }
            ui.postDelayed({ activeMdnsListeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }; activeMdnsListeners.clear(); render(true); LogBus.log("Network Scanner: mDNS Scan fertig Treffer=${results.size}") }, 8000)
        }
        fun scanBluetoothAll() {
            stopBtScan = false
            val filter = btFilter.text.toString().trim().lowercase()
            bluetoothResultText.text = "Bluetooth Scan laeuft..."
            LogBus.log("Bluetooth Scanner: Scan all gestartet")
            val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            if (scanner == null) { bluetoothResultText.text = "Bluetooth Scanner nicht verfuegbar"; return }
            val found = linkedMapOf<String, String>()
            val scanStarted = System.currentTimeMillis()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (stopBtScan) return
                    val record = result.scanRecord
                    val name = runCatching { result.device.name ?: record?.deviceName ?: "" }.getOrDefault(record?.deviceName ?: "")
                    val address = result.device.address ?: "?"
                    val line = "rssi=${result.rssi.toString().padStart(4)}  ${name.ifBlank { "-" }}  $address"
                    if (filter.isBlank() || line.lowercase().contains(filter)) found[address] = line
                    val sorted = found.values.sortedBy { it.substringAfter("rssi=").substringBefore(" ").trim().toIntOrNull() ?: 0 }.reversed()
                    val elapsed = ((System.currentTimeMillis() - scanStarted) / 1000L).coerceAtLeast(0)
                    lastBluetoothScanText = "Bluetooth Scan  ${elapsed}s  Treffer=${found.size}\n" + sorted.joinToString("\n")
                    runOnUiThread { bluetoothResultText.text = lastBluetoothScanText }
                }
                override fun onScanFailed(errorCode: Int) { runOnUiThread { bluetoothResultText.text = "Bluetooth Scan Fehler code=$errorCode" } }
            }
            try {
                activeBtScanner = scanner
                activeBtCallback = callback
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                ui.postDelayed({ runCatching { scanner.stopScan(callback) }; activeBtScanner = null; activeBtCallback = null; rememberSession(bluetoothSessions, lastBluetoothScanText); LogBus.log("Bluetooth Scanner: Scan all fertig Treffer=${found.size}") }, 9000)
            } catch (t: Throwable) { bluetoothResultText.text = "Bluetooth Scan Fehler: ${t.message}" }
        }
        @Suppress("DEPRECATION")
        fun renderWifiRows(rowsRaw: List<android.net.wifi.ScanResult>, filter: String) {
            val rows = rowsRaw.map { r ->
                val ssid = (r.SSID ?: "").ifBlank { "<hidden>" }
                "rssi=${r.level.toString().padStart(4)}  ssid=$ssid  bssid=${r.BSSID}  freq=${r.frequency}  caps=${r.capabilities}"
            }.filter { filter.isBlank() || it.lowercase().contains(filter) }.sortedByDescending { it.substringAfter("rssi=").substringBefore(" ").trim().toIntOrNull() ?: -999 }
            val grouped = rows.groupBy { it.substringAfter("ssid=").substringBefore("  bssid=") }
                .flatMap { (ssid, items) -> listOf("SSID: $ssid (${items.size})") + items.map { "  $it" } }
            lastWifiScanText = "WiFi Scan  Treffer=${rows.size}\n" + grouped.joinToString("\n").ifBlank { "Keine Treffer. Android 8.1 Tipp: WLAN-Einstellungen öffnen, Standortdienste aktivieren, danach erneut scannen.\n" + scannerPermissionStatus() }
            rememberSession(wifiSessions, lastWifiScanText)
            wifiResultText.text = lastWifiScanText
            LogBus.log("WiFi Scanner: Scan fertig Treffer=${rows.size}")
        }
        @Suppress("DEPRECATION")
        fun scanWifiSignals() {
            stopWifiScan = false
            refreshPermissionStatus()
            val filter = wifiFilter.text.toString().trim().lowercase()
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi == null) { wifiResultText.text = "WiFi Manager nicht verfuegbar"; return }
            wifiResultText.text = "WiFi Legacy Scan gestartet...\n" + scannerPermissionStatus()
            LogBus.log("WiFi Scanner: Legacy Broadcast Scan gestartet")
            val scanReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    runCatching { unregisterReceiver(this) }
                    if (stopWifiScan) { wifiResultText.text = "WiFi Scan gestoppt"; return }
                    val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                    val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())
                    LogBus.log("WiFi Scanner: Broadcast success=$success cached=${results.size}")
                    renderWifiRows(results, filter)
                }
            }
            runCatching { registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) }
            val startOk = runCatching { wifi.startScan() }.getOrDefault(false)
            val cached = runCatching { wifi.scanResults }.getOrDefault(emptyList())
            wifiResultText.text = "WiFi Scan angefordert startScan=$startOk cached=${cached.size}\n" + scannerPermissionStatus()
            if (cached.isNotEmpty()) renderWifiRows(cached, filter)
            ui.postDelayed({
                runCatching { unregisterReceiver(scanReceiver) }
                if (!stopWifiScan && lastWifiScanText.isBlank()) {
                    val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())
                    renderWifiRows(results, filter)
                }
            }, 4500)
        }
        fun setSwitchLeds() {
            val mqttVisible = mqttModeSwitch.isChecked && mqttHost.text.toString().isNotBlank()
            mqttMonitorVisible = mqttVisible
            listOf(mqttStatusLed, mqttInLed, mqttOutLed).forEach { led ->
                (led.parent as? View)?.visibility = if (mqttVisible) View.VISIBLE else View.GONE
            }
            setLed(mqttStatusLed, mqttVisible, amber)
            if (!mqttVisible) { setLed(mqttInLed, false, violet); setLed(mqttOutLed, false, violet) }
            setLed(smbLed, smbSwitch.isChecked, amber)
            setLed(webLed, webSwitch.isChecked, cyan)
            setLed(tcpLed, tcpSwitch.isChecked, violet)
            setLed(smbSwitchLed, smbSwitch.isChecked, amber)
            setLed(webSwitchLed, webSwitch.isChecked, cyan)
            setLed(tcpSwitchLed, tcpSwitch.isChecked, violet)
            setLed(autoApiSwitchLed, autoApiSwitch.isChecked, lime)
            setLed(webSocketSwitchLed, webSocketSwitch.isChecked, ps2Blue)
            setLed(mqttModeSwitchLed, mqttModeSwitch.isChecked, amber)
            setLed(directModeSwitchLed, directModeSwitch.isChecked, cyan)
            setLed(networkDiscoverySwitchLed, networkDiscoverySwitch.isChecked, lime)
            setLed(autoStartSwitchLed, autoStartSwitch.isChecked, amber)
            setLed(autoStartLed, autoStartSwitch.isChecked, amber)
            setLed(returnAppIconSwitchLed, returnAppIconSwitch.isChecked, ps2Purple)
            setLed(directLed, directModeSwitch.isChecked, cyan)
            setLed(mdnsLed, networkDiscoverySwitch.isChecked && directModeSwitch.isChecked, lime)
            setLed(bleConnectedLed, RuntimeStatus.bleConnected, ps2Blue)
            setLed(directRxLed, false, ps2Purple)
            setLed(directTxLed, false, lime)
        }
        setSwitchLeds()
        var suppressDirty = false
        fun markSettingsDirty() { if (!suppressDirty) saveSettings.visibility = View.VISIBLE }
        returnAppSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedReturnAppPackage = appChoices.getOrNull(position)?.packageName ?: ""
                updateReturnAppButtonText()
                markSettingsDirty()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        fun watchField(e: EditText, onChange: (() -> Unit)? = null) {
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { markSettingsDirty(); onChange?.invoke() }
                override fun afterTextChanged(editable: Editable?) {}
            })
        }
        listOf(mac, network, casambiPass, protocol, keyId, keyHex, mqttHost, mqttPort, mqttUser, mqttPass, baseTopic, discoveryPrefix, smbServer, smbShare, smbPath, smbDomain, smbUser, smbPassword, tcpPort, webPort, netPortMode, netCustomPorts).forEach { watchField(it) }
        smbSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        webSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        tcpSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        autoApiSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        webSocketSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        mqttModeSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        directModeSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        networkDiscoverySwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        autoStartSwitch.setOnCheckedChangeListener { _, _ -> setSwitchLeds(); markSettingsDirty() }
        returnAppIconSwitch.setOnCheckedChangeListener { _, checked -> returnAppShowIcon = checked; updateReturnAppButtonText(); setSwitchLeds(); markSettingsDirty() }

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
            smbSwitch.isChecked = x.smbDebugEnabled; webSwitch.isChecked = x.webInterfaceEnabled; tcpSwitch.isChecked = x.tcpLogEnabled; autoApiSwitch.isChecked = x.autoApiFetchEnabled; webSocketSwitch.isChecked = x.webSocketLiveEnabled; mqttModeSwitch.isChecked = x.mqttEnabled; directModeSwitch.isChecked = x.directModeEnabled; networkDiscoverySwitch.isChecked = x.networkDiscoveryEnabled; autoStartSwitch.isChecked = x.autoStartEnabled
            selectedReturnAppPackage = x.returnAppPackage
            returnAppShowIcon = x.returnAppShowIcon
            returnAppIconSwitch.isChecked = x.returnAppShowIcon
            updateReturnAppButtonText()
            val returnIndex = appChoices.indexOfFirst { it.packageName == x.returnAppPackage }.takeIf { it >= 0 } ?: 0
            returnAppSpinner.setSelection(returnIndex)
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
                    runOnUiThread { statusText.text = "MQTT Dashboard YAML exportiert"; LogBus.log("MQTT Dashboard YAML exportiert: $url") }
                } catch (t: Throwable) {
                    runOnUiThread { statusText.text = "MQTT Dashboard Export Fehler"; LogBus.log("MQTT Dashboard Export Fehler: ${t.message}") }
                }
            }.start()
        }
        dashboardDirectExport.setOnClickListener {
            val cfg = currentConfig()
            Thread {
                try {
                    val url = DashboardExporter.exportDirectToSmb(this@MainActivity, cfg)
                    runOnUiThread { statusText.text = "Direct Dashboard YAML exportiert"; LogBus.log("Direct Dashboard YAML exportiert: $url") }
                } catch (t: Throwable) {
                    runOnUiThread { statusText.text = "Direct Dashboard Export Fehler"; LogBus.log("Direct Dashboard Export Fehler: ${t.message}") }
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

        scanIpButton.setOnClickListener { scanIpNetwork() }
        scanHostsButton.setOnClickListener { netPortMode.setText("none"); scanIpNetwork() }
        stopIpButton.setOnClickListener { stopIpScan = true; networkResultText.text = networkResultText.text.toString() + "\nSTOP angefordert" }
        scanMdnsButton.setOnClickListener { scanMdnsServices() }
        stopMdnsButton.setOnClickListener { stopMdnsScan = true; activeMdnsListeners.forEach { runCatching { activeMdnsManager?.stopServiceDiscovery(it) } }; activeMdnsListeners.clear(); networkResultText.text = networkResultText.text.toString() + "\nmDNS STOP" }
        exportNetButton.setOnClickListener { exportScanToSmb("network_scan", lastNetworkScanText.ifBlank { networkResultText.text.toString() }) }
        scanBtAllButton.setOnClickListener { scanBluetoothAll() }
        stopBtButton.setOnClickListener { stopBtScan = true; activeBtCallback?.let { cb -> runCatching { activeBtScanner?.stopScan(cb) } }; activeBtScanner = null; activeBtCallback = null; rememberSession(bluetoothSessions, lastBluetoothScanText); bluetoothResultText.text = bluetoothResultText.text.toString() + "\nBT STOP" }
        exportBtButton.setOnClickListener { exportScanToSmb("bluetooth_scan", lastBluetoothScanText.ifBlank { bluetoothResultText.text.toString() }) }
        scanWifiButton.setOnClickListener { scanWifiSignals() }
        stopWifiButton.setOnClickListener { stopWifiScan = true; wifiResultText.text = wifiResultText.text.toString() + "\nWIFI STOP" }
        exportWifiButton.setOnClickListener { exportScanToSmb("wifi_scan", lastWifiScanText.ifBlank { wifiResultText.text.toString() }) }

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
                    ConfigStore.saveLastSyncMillis(this, RuntimeStatus.lastSyncMillis)
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

    private fun setLed(led: TextView, on: Boolean, color: Int = ps2Green) {
        led.setTextColor(if (on) color else darkLed)
    }

    private fun flashPulse(led: TextView, color: Int) {
        led.setTextColor(color)
        ui.postDelayed({ led.setTextColor(darkLed) }, 260)
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
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CHANGE_WIFI_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }
}
