package de.pascal.casambibridge.bridge
import android.util.Log
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
object DebugExporter { private val executor=Executors.newSingleThreadExecutor(); @Volatile private var config:BridgeConfig?=null; private val fileFmt=SimpleDateFormat("yyyy-MM-dd",Locale.US)
 fun configure(c:BridgeConfig){config=c;if(c.smbDebugEnabled)appendLine("=== Casambi Bridge debug export configured v0.5.8 ===")}
 fun appendLine(line:String){val c=config?:return;if(!c.smbDebugEnabled||c.smbServer.isBlank()||c.smbShare.isBlank())return;executor.execute{try{val ctx=smbContext(c);val dirUrl=smbDir(c);SmbFile(dirUrl,ctx).use{if(!it.exists())it.mkdirs()};val fileUrl=dirUrl+"casambi_debug_${fileFmt.format(Date())}.log";SmbFileOutputStream(SmbFile(fileUrl,ctx),true).use{it.write((line+"\n").toByteArray(Charsets.UTF_8))}}catch(t:Throwable){Log.e("CasambiDebugExporter","SMB export failed: ${t.message}",t)}}}
 fun writeTest(c:BridgeConfig){configure(c);appendLine("=== SMB test from Casambi Bridge ${Date()} ===")}
 internal fun smbContext(c:BridgeConfig)=BaseContext(PropertyConfiguration(Properties().apply{setProperty("jcifs.smb.client.minVersion","SMB202");setProperty("jcifs.smb.client.maxVersion","SMB311");setProperty("jcifs.smb.client.responseTimeout","5000");setProperty("jcifs.smb.client.soTimeout","5000")})).withCredentials(NtlmPasswordAuthenticator(c.smbDomain,c.smbUser,c.smbPassword))
 internal fun smbDir(c:BridgeConfig):String{val clean=c.smbPath.trim('/').trim();return buildString{append("smb://");append(c.smbServer.trim('/'));append('/');append(c.smbShare.trim('/'));append('/');if(clean.isNotBlank()){append(clean);append('/')}}}
}
