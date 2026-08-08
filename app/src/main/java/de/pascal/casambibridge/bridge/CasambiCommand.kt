package de.pascal.casambibridge.bridge

data class CasambiCommand(
    val unitId: Int,
    val state: String?,
    val brightness: Int?,
    val targetType: Int = 1,
    val label: String? = null
) {
    val effectiveBrightness: Int get() = when {
        targetType == 4 -> brightness?.coerceIn(0,255) ?: 255
        state.equals("OFF", true) -> 0
        brightness != null -> brightness.coerceIn(0,255)
        state.equals("ON", true) -> 255
        else -> 0
    }
}
