package de.pascal.casambibridge.bridge

object RuntimeStatus {
    @Volatile var lastState: String = "OFF"
    @Volatile var lastBrightness: Int = 0
    @Volatile var lastOnline: Boolean = false
    @Volatile var lastRawState: String = ""
    @Volatile var lastUpdateMillis: Long = 0L

    fun update(state: String, brightness: Int, online: Boolean, raw: String) {
        lastState = state
        lastBrightness = brightness.coerceIn(0, 255)
        lastOnline = online
        lastRawState = raw
        lastUpdateMillis = System.currentTimeMillis()
    }
}
