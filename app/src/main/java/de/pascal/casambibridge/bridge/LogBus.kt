package de.pascal.casambibridge.bridge
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.*
object LogBus { private val handler=Handler(Looper.getMainLooper()); private val listeners=mutableSetOf<(String)->Unit>(); private val lines=ArrayDeque<String>(); private val fmt=SimpleDateFormat("HH:mm:ss",Locale.US)
 fun addListener(l:(String)->Unit){listeners+=l; lines.forEach{l(it)}}; fun removeListener(l:(String)->Unit){listeners-=l}; fun clear(){lines.clear()}
 fun log(m:String){val line="[${fmt.format(Date())}] $m"; android.util.Log.i("CasambiBridge",line); DebugExporter.appendLine(line); TcpLogServer.broadcast(line); if(lines.size>160)lines.removeFirst(); lines.addLast(line); handler.post{listeners.forEach{it(line)}}}
}
