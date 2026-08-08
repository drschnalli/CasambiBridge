package de.pascal.casambibridge.bridge

object RuntimeStatus {
    val appStartMillis: Long = System.currentTimeMillis()
    @Volatile var bridgeStartMillis: Long = 0L
    @Volatile var lastState: String = "OFF"
    @Volatile var lastBrightness: Int = 0
    @Volatile var lastOnline: Boolean = false
    @Volatile var lastRawState: String = ""
    @Volatile var lastUpdateMillis: Long = 0L
    @Volatile var lastSyncMillis: Long = 0L
    @Volatile var mqttConnected: Boolean = false
    @Volatile var bleConnected: Boolean = false
    @Volatile var cloudConnected: Boolean = false
    @Volatile var bridgeState: String = "stopped"
    @Volatile var lastSceneId: Int = -1
    @Volatile var lastSceneName: String = ""

    fun markBridgeStarted() {
        bridgeStartMillis = System.currentTimeMillis()
        bridgeState = "online"
    }

    fun markSync() {
        lastSyncMillis = System.currentTimeMillis()
        cloudConnected = true
    }

    fun markScene(id: Int, name: String) {
        lastSceneId = id
        lastSceneName = name
    }

    fun clearScene() {
        lastSceneId = -1
        lastSceneName = ""
    }

    fun update(state: String, brightness: Int, online: Boolean, raw: String) {
        lastState = state
        lastBrightness = brightness.coerceIn(0, 255)
        lastOnline = online
        lastRawState = raw
        lastUpdateMillis = System.currentTimeMillis()
    }

    fun uptimeText(): String {
        val start = if (bridgeStartMillis > 0L) bridgeStartMillis else appStartMillis
        val totalMinutes = ((System.currentTimeMillis() - start).coerceAtLeast(0L) / 60000L)
        val days = totalMinutes / (60L * 24L)
        val hours = (totalMinutes / 60L) % 24L
        val minutes = totalMinutes % 60L
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
