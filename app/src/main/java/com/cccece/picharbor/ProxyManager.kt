package com.cccece.picharbor

import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.net.*
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

data class ProxyHealth(val milliseconds:Long?=null,val healthy:Boolean=true,val coolingUntil:Long=0)

/** The same pinned Clash core as iOS, with an app-private loopback listener. */
class ProxyManager(private val graph:AppGraph) {
    private val mutex=Mutex()
    private val initialized=CompletableDeferred<Unit>()
    private var process:Process?=null
    private var signature=""
    private var port=0
    private var control=0
    private var secret=""
    private val group="PicHarbor-"+UUID.randomUUID().toString()
    @Volatile private var current=""
    @Volatile private var generation=0L
    val health=MutableStateFlow<Map<String,ProxyHealth>>(emptyMap())
    val library=MutableStateFlow(JSONObject().put("profiles",JSONArray()).put("selection","automatic").put("exhaustion","wait"))
    val message=MutableStateFlow("")
    val testing=MutableStateFlow(false)
    private var testJob:Job?=null
    init { graph.scope.launch {
        AtomicFile(File(graph.app.noBackupFilesDir,"proxy-runtime/configuration.json")).delete()
        try {runCatching { graph.credentials.loadProxyLibrary()?.let{library.value=JSONObject(it)} }.onFailure{message.value="无法读取代理配置，请检查安全存储"}}
        finally {initialized.complete(Unit)}
    } }
    private fun profiles()=library.value.getJSONArray("profiles")
    fun profile():JSONObject? {
        val id=library.value.optString("active")
        return (0 until profiles().length()).map{profiles().getJSONObject(it)}.firstOrNull{it.getString("id")==id}
    }
    private fun save(value:JSONObject) { graph.credentials.saveProxyLibrary(value.toString());library.value=value }
    suspend fun importText(text:String,name:String,url:String="",id:String?=null)=withContext(Dispatchers.IO) {
        initialized.await()
        require(text.toByteArray().size<=4*1024*1024)
        val options=LoaderOptions().apply{codePointLimit=4*1024*1024;maxAliasesForCollections=20;nestingDepthLimit=30;isAllowDuplicateKeys=false}
        val parsed=Yaml(SafeConstructor(options)).load<Any>(text)
        val objectValue=if(parsed is Map<*,*>)JSONObject(parsed) else throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
        val nodes=objectValue.optJSONArray("proxies")?:throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
        if(nodes.length()==0||nodes.length()>64)throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
        val names=HashSet<String>()
        for(i in 0 until nodes.length()) {
            val node=nodes.getJSONObject(i)
            if(!names.add(node.getString("name")) || node.getString("name").isBlank() || node.getString("name").toByteArray(Charsets.UTF_8).size>256 || node.optString("type").isBlank()
                || node.getString("name").uppercase() in setOf("DIRECT","REJECT","GLOBAL")
                || node.getString("type").lowercase() in setOf("direct","reject","dns")
                || listOf("dialer-proxy","interface-name","routing-mark").any{node.has(it)})throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
            // Certificate verification cannot be disabled by an imported node.
            node.put("skip-cert-verify",false)
        }
        mutex.withLock {
            val data=JSONObject(library.value.toString())
            val list=data.getJSONArray("profiles")
            val identifier=id?:UUID.randomUUID().toString()
            val old=(0 until list.length()).firstOrNull{list.getJSONObject(it).getString("id")==identifier}
            val profile=JSONObject().put("id",identifier).put("name",name.ifBlank{"代理配置"}).put("url",url).put("nodes",nodes).put("updated",System.currentTimeMillis())
            if(old==null)list.put(profile) else list.put(old,profile)
            data.put("active",identifier);save(data);stopLocked();health.value=emptyMap()
        }
    }
    suspend fun download(url:String,name:String,id:String?=null) {
        initialized.await()
        val address=URI(url.trim())
        if(address.scheme !in setOf("http","https")||address.host.isNullOrBlank()||address.userInfo!=null)throw EngineFailure("PROXY_SUBSCRIPTION_URL")
        val route=if(profile()!=null&&graph.preferences.get().proxyEnabled)url() else ""
        val transport=if(route.isBlank())java.net.Proxy.NO_PROXY else URI(route).let{java.net.Proxy(java.net.Proxy.Type.HTTP,InetSocketAddress(it.host,it.port))}
        val text=withContext(Dispatchers.IO) {
            var destination=address
            var connection:HttpURLConnection?=null
            try {
                for(hop in 0..5) {
                    val opened=destination.toURL().openConnection(transport) as HttpURLConnection
                    connection=opened
                    opened.connectTimeout=30000;opened.readTimeout=45000;opened.instanceFollowRedirects=false
                    opened.setRequestProperty("User-Agent","clash.meta")
                    val status=opened.responseCode
                    if(status in setOf(301,302,303,307,308)) {
                        if(hop==5)throw EngineFailure("PROXY_SUBSCRIPTION_DOWNLOAD")
                        val location=opened.getHeaderField("Location")?:throw EngineFailure("PROXY_SUBSCRIPTION_DOWNLOAD")
                        val next=destination.resolve(location)
                        if(next.scheme !in setOf("http","https")||next.host.isNullOrBlank()||next.userInfo!=null)throw EngineFailure("PROXY_SUBSCRIPTION_URL")
                        opened.disconnect();destination=next;connection=null
                    } else {
                        if(status!=200)throw EngineFailure("PROXY_SUBSCRIPTION_DOWNLOAD")
                        break
                    }
                }
                val response=connection?:throw EngineFailure("PROXY_SUBSCRIPTION_DOWNLOAD")
                if(response.contentLengthLong>4*1024*1024)throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
                response.inputStream.use{input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val count=input.read(buffer);if(count<0)break;if(output.size()+count>4*1024*1024)throw EngineFailure("PROXY_CONFIG_UNSUPPORTED");output.write(buffer,0,count)}
                    output.toString("UTF-8")
                }
            } finally {connection?.disconnect()}
        }
        importText(text,name,url,id)
    }
    suspend fun activate(id:String)=mutex.withLock { initialized.await();val value=JSONObject(library.value.toString()).put("active",id);save(value);stopLocked();health.value=emptyMap() }
    suspend fun remove(id:String)=mutex.withLock {
        initialized.await()
        val value=JSONObject(library.value.toString());val list=value.getJSONArray("profiles")
        value.put("profiles",JSONArray((0 until list.length()).map{list.getJSONObject(it)}.filter{it.getString("id")!=id}))
        if(value.optString("active")==id)value.remove("active")
        save(value);stopLocked();health.value=emptyMap()
    }
    suspend fun policy(key:String,value:String)=mutex.withLock {initialized.await();save(JSONObject(library.value.toString()).put(key,value));current=""}
    private fun freePort()=ServerSocket(0,0,InetAddress.getLoopbackAddress()).use{it.localPort}
    private fun stopLocked(){generation++;process?.destroy();process=null;signature="";port=0;current=""}
    suspend fun stop()=mutex.withLock{stopLocked()}
    fun outcomeToken()=generation to current
    fun recordOutcome(error:String?,token:Pair<Long,String>) {
        val node=token.second
        if(node.isBlank()||token.first!=generation||node!=current)return
        if(error==null)health.update{it+(node to ProxyHealth(it[node]?.milliseconds,true))}
        else if(error in setOf("NETWORK_ERROR","STAGE_TIMEOUT","NO_PROGRESS_TIMEOUT")) {
            health.update{it+(node to ProxyHealth(null,false,System.currentTimeMillis()+15000))};current=""
        }
    }
    suspend fun backups(names:List<String>)=mutex.withLock{initialized.await();save(JSONObject(library.value.toString()).put("backups",JSONArray(names)))}
    suspend fun url(override:JSONObject?=null):String=mutex.withLock {
        initialized.await()
        if(!graph.preferences.get().proxyEnabled){stopLocked();return@withLock ""}
        val selected=profile()?:run {
            // Retain the existing explicit HTTP proxy as an additional input.
            val config=ProxyPolicy.buildConfig(graph.preferences.get())?:throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
            if(ProxyPolicy.validate(graph.harborCore,config) !is ProxyPolicy.Validation.Valid)throw EngineFailure("PROXY_INVALID")
            return@withLock ProxyPolicy.proxyUrl(config,graph.credentials.loadProxyCredential())
        }
        startLocked(selected)
        val now=System.currentTimeMillis()
        val nodes=selected.getJSONArray("nodes")
        val candidates=JSONArray()
        for(i in 0 until nodes.length()) {
            val name=nodes.getJSONObject(i).getString("name");val h=health.value[name]
            val cooling=(h?.coolingUntil?:0)>now
            candidates.put(JSONObject().put("name",name).put("healthy",h?.healthy!=false||(!cooling&&(h?.coolingUntil?:0)>0)).put("cooling",cooling).put("latency",h?.milliseconds?:JSONObject.NULL))
        }
        val data=override?:library.value
        val policy=JSONObject().put("selection",data.optString("selection","automatic")).put("primary",data.optString("primary"))
            .put("backups",data.optJSONArray("backups")?:JSONArray()).put("exhaustion",data.optString("exhaustion","wait"))
        val choice=graph.harborCore.dispatch("select_proxy",JSONObject().put("nodes",candidates).put("policy",policy).put("current",current))
        if(!choice.optBoolean("ok")||choice.optBoolean("waiting"))throw EngineFailure("PROXY_UNAVAILABLE")
        if(choice.optBoolean("direct"))return@withLock ""
        val name=choice.getString("node")
        if(current!=name){controller("PUT","/proxies/$group",JSONObject().put("name",name).toString());current=name}
        "http://127.0.0.1:$port"
    }
    private suspend fun startLocked(selected:JSONObject) {
        val key=selected.toString()
        if(signature==key&&process?.isAlive==true)return
        stopLocked();port=freePort();control=freePort();secret=UUID.randomUUID().toString()
        val home=File(graph.app.noBackupFilesDir,"proxy-runtime").apply{mkdirs()}
        val config=JSONObject().put("mixed-port",port).put("bind-address","127.0.0.1").put("allow-lan",false)
            .put("external-controller","127.0.0.1:$control").put("secret",secret).put("log-level","silent")
            .put("ipv6",true).put("mode","rule").put("dns",JSONObject().put("enable",false))
            .put("tun",JSONObject().put("enable",false)).put("profile",JSONObject().put("store-selected",false).put("store-fake-ip",false))
            .put("proxies",selected.getJSONArray("nodes"))
            .put("proxy-groups",JSONArray().put(JSONObject().put("name",group).put("type","select")
                .put("proxies",JSONArray((0 until selected.getJSONArray("nodes").length()).map{selected.getJSONArray("nodes").getJSONObject(it).getString("name")}))))
            .put("rules",JSONArray().put("MATCH,$group"))
        val executable=File(graph.app.applicationInfo.nativeLibraryDir,"libharbor_proxy.so")
        if(!executable.isFile)throw EngineFailure("PROXY_RUNTIME_UNAVAILABLE")
        process=ProcessBuilder(executable.absolutePath,"-d",home.absolutePath,"-f","-")
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        try {process!!.outputStream.use{it.write(clashJson(config.toString()).toByteArray(Charsets.UTF_8))}}
        catch(_:Exception){stopLocked();throw EngineFailure("PROXY_RUNTIME_UNAVAILABLE")}
        repeat(100){
            if(process?.isAlive!=true)throw EngineFailure("PROXY_CONFIG_UNSUPPORTED")
            if(runCatching{Socket().use{it.connect(InetSocketAddress("127.0.0.1",control),100)}}.isSuccess){signature=key;return}
            delay(50)
        }
        stopLocked();throw EngineFailure("PROXY_RUNTIME_UNAVAILABLE")
    }
    private fun controller(method:String,path:String,body:String?=null):JSONObject {
        val connection=URL("http://127.0.0.1:$control$path").openConnection() as HttpURLConnection
        connection.requestMethod=method;connection.connectTimeout=3000;connection.readTimeout=10000
        connection.setRequestProperty("Authorization","Bearer $secret")
        try {
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
            if(connection.responseCode !in 200..299)throw EngineFailure("PROXY_UNAVAILABLE")
            return connection.inputStream.use{val text=it.bufferedReader().readText();if(text.isBlank())JSONObject() else JSONObject(text)}
        } finally {connection.disconnect()}
    }
    /** The pinned YAML reader accepts JSON with literal slashes, as in the iOS encoder. */
    private fun clashJson(value:String):String {
        val output=StringBuilder(value.length)
        var index=0
        while(index<value.length) {
            if(value[index]=='\\'&&index+1<value.length) {
                if(value[index+1]=='/')output.append('/') else output.append(value[index]).append(value[index+1])
                index+=2
            } else {output.append(value[index]);index++}
        }
        return output.toString()
    }
    fun testAll() {
        if(testing.value)return
        testJob=graph.scope.launch {
            testing.value=true
            try {
                val selected=profile()?:return@launch
                mutex.withLock{startLocked(selected)}
                val nodes=selected.getJSONArray("nodes")
                for(i in 0 until nodes.length()) {
                    ensureActive();val name=nodes.getJSONObject(i).getString("name")
                    val result=runCatching{controller("GET","/proxies/${URLEncoder.encode(name,"UTF-8")}/delay?timeout=5000&url=https%3A%2F%2Fwww.google.com%2Fgenerate_204")}
                    val next=if(result.isSuccess)ProxyHealth(result.getOrThrow().optLong("delay"),true) else ProxyHealth(null,false,System.currentTimeMillis()+300000)
                    health.update{it+(name to next)}
                }
                message.value="测速完成"
            } catch(e:CancellationException){message.value="测速已停止";throw e}
            catch(_:Exception){message.value="无法测速，请检查配置与网络"}
            finally{testing.value=false}
        }
    }
    fun cancelTest(){testJob?.cancel()}
}
