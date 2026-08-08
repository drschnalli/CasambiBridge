package de.pascal.casambibridge.bridge
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
object TcpLogServer { private val lock=Any(); private val clients=CopyOnWriteArrayList<Socket>(); @Volatile private var serverSocket:ServerSocket?=null; @Volatile private var runningPort=-1
 fun configure(c:BridgeConfig){ synchronized(lock){ if(!c.tcpLogEnabled){stopLocked(true);return}; val port=c.tcpLogPort.coerceIn(1024,65535); if(serverSocket!=null&&runningPort==port){LogBus.log("TCP Logstream laeuft bereits auf Port $port");return}; stopLocked(false); startLocked(port) } }
 private fun startLocked(port:Int){ thread(name="casambi-tcp-log-server",isDaemon=true){ var local:ServerSocket?=null; try{ val s=ServerSocket(); s.reuseAddress=true; s.bind(InetSocketAddress("0.0.0.0",port)); synchronized(lock){serverSocket=s;runningPort=port}; local=s; LogBus.log("TCP Logstream aktiv auf Port $port"); while(!s.isClosed){val sock=s.accept(); sock.tcpNoDelay=true; clients.add(sock); send(sock,"Casambi Bridge TCP Logstream verbunden.\r\n")}}catch(t:Throwable){LogBus.log("TCP Logstream Fehler auf Port $port: ${t.message ?: t.javaClass.simpleName}")}finally{synchronized(lock){if(serverSocket===local){serverSocket=null;runningPort=-1}}} } }
 fun stop(){ synchronized(lock){stopLocked(true)} }
 private fun stopLocked(logStop:Boolean){ val old=runningPort; try{serverSocket?.close()}catch(_:Throwable){}; serverSocket=null; runningPort=-1; clients.forEach{try{it.close()}catch(_:Throwable){}}; clients.clear(); if(logStop&&old>0)LogBus.log("TCP Logstream gestoppt auf Port $old") }
 fun broadcast(line:String){ val msg=line+"\r\n"; clients.forEach{try{send(it,msg)}catch(_:Throwable){clients.remove(it);try{it.close()}catch(_:Throwable){}}} }
 private fun send(s:Socket,text:String){ val w=BufferedWriter(OutputStreamWriter(s.getOutputStream(),Charsets.UTF_8)); w.write(text); w.flush() }
}
