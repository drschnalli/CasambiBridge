package de.pascal.casambibridge.bridge

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBus {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(String) -> Unit>()
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addListener(l: (String) -> Unit) {
        synchronized(lines) { lines.forEach { l(it) } }
        listeners += l
    }

    fun removeListener(l: (String) -> Unit) { listeners -= l }

    fun clear() { synchronized(lines) { lines.clear() } }

    fun recentLines(limit: Int = 160): List<String> = synchronized(lines) {
        lines.toList().takeLast(limit.coerceIn(1, 500))
    }

    fun log(m: String) {
        val line = "[${fmt.format(Date())}] $m"
        android.util.Log.i("CasambiBridge", line)
        DebugExporter.appendLine(line)
        TcpLogServer.broadcast(line)
        synchronized(lines) {
            if (lines.size > 220) lines.removeFirst()
            lines.addLast(line)
        }
        handler.post { listeners.forEach { it(line) } }
    }
}
