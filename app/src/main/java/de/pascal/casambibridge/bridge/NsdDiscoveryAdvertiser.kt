package de.pascal.casambibridge.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

object NsdDiscoveryAdvertiser {
    private const val SERVICE_TYPE = "_casambi-jungle._tcp."
    private val lock = Any()
    @Volatile private var nsdManager: NsdManager? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var currentPort: Int = -1

    fun configure(context: Context, config: BridgeConfig) {
        synchronized(lock) {
            if (!config.directModeEnabled || !config.networkDiscoveryEnabled) {
                stopLocked(true)
                return
            }
            val port = config.webInterfacePort.coerceIn(1024, 65535)
            if (registrationListener != null && currentPort == port) return
            stopLocked(false)
            startLocked(context.applicationContext, config, port)
        }
    }

    fun stop() = synchronized(lock) { stopLocked(true) }

    private fun startLocked(context: Context, config: BridgeConfig, port: Int) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val serviceName = (config.casambiNetworkName.ifBlank { "Casambi Jungle Bridge" })
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .ifBlank { "Casambi Jungle Bridge" }
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
            if (Build.VERSION.SDK_INT >= 21) {
                setAttribute("name", serviceName)
                setAttribute("version", "0.10.0")
                setAttribute("api", "/api/info")
                setAttribute("status", "/api/status")
                setAttribute("ws", "/ws")
                setAttribute("base_topic", config.baseTopic)
                setAttribute("network", config.casambiNetworkName.ifBlank { "unknown" })
                setAttribute("auth", "none")
                setAttribute("mqtt", if (config.mqttEnabled) "on" else "off")
                setAttribute("direct", if (config.directModeEnabled) "on" else "off")
                setAttribute("discovery", if (config.networkDiscoveryEnabled) "on" else "off")
                setAttribute("mode", if (config.mqttEnabled && config.directModeEnabled) "hybrid" else if (config.directModeEnabled) "direct" else "mqtt")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                LogBus.log("Network Discovery aktiv: ${serviceInfo.serviceName} ${serviceInfo.serviceType} port=${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LogBus.log("Network Discovery Registrierung fehlgeschlagen error=$errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                LogBus.log("Network Discovery gestoppt")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LogBus.log("Network Discovery Stop fehlgeschlagen error=$errorCode")
            }
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            registrationListener = listener
            currentPort = port
        } catch (t: Throwable) {
            LogBus.log("Network Discovery Fehler: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun stopLocked(logStop: Boolean) {
        val manager = nsdManager
        val listener = registrationListener
        if (manager != null && listener != null) {
            try { manager.unregisterService(listener) } catch (_: Throwable) {}
        }
        registrationListener = null
        nsdManager = null
        currentPort = -1
        if (logStop) LogBus.log("Network Discovery deaktiviert")
    }
}
