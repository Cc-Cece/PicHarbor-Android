package com.cccece.picharbor

import android.app.Application
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest

data class LinkProgress(val running:Boolean=false,val phase:String="",val done:Int=0,val total:Int=0,val bytes:Long=0,val error:String="",val target:String="")

/** Phone to a Windows computer on the same LAN. Original files only; motion photos stay whole. */
class ComputerLink(private val app:Application) {
    val computers=MutableStateFlow<List<DiscoveredComputer>>(emptyList())
    val progress=MutableStateFlow(LinkProgress())
    val listening=MutableStateFlow(false)
    val hint=MutableStateFlow("")
    var notifier:(LinkProgress)->Unit={}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val transport=LinkTransport()
    private val identity by lazy{LinkIdentity(app)}
    private val seen=LinkedHashMap<String,DiscoveredComputer>()
    private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var beacon:MulticastSocket?=null
    @Volatile private var discovery:Job?=null
    @Volatile private var pending:Pending?=null
    @Volatile private var generation=0
    private data class Pending(val target:LinkTarget,val ids:List<String>?)
    private data class SendPlan(val mediaId:String,val id:String,val name:String,val size:Long,val created:Long,val sha256:String)

    fun paired(fingerprint:String)=identity.isPaired(fingerprint)

    fun startDiscovery() {
        val token=++generation
        discovery?.cancel()
        runCatching{beacon?.close()}
        discovery=scope.launch {
            listening.value=true
            hint.value=""
            var socket:MulticastSocket?=null
            var lock:WifiManager.MulticastLock?=null
            try {
                val wifi=app.getSystemService(WifiManager::class.java)
                lock=wifi?.createMulticastLock("picharbor-link")
                lock?.setReferenceCounted(false)
                lock?.acquire()
                socket=MulticastSocket(null)
                beacon=socket
                socket.reuseAddress=true
                socket.bind(InetSocketAddress(LinkProtocol.PORT))
                join(socket)
                socket.soTimeout=1000
                val buffer=ByteArray(4096)
                while(isActive&&token==generation) {
                    try {
                        val packet=DatagramPacket(buffer,buffer.size)
                        socket.receive(packet)
                        val slice=packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
                        val found=LinkProtocol.parseBeacon(slice)?:continue
                        merge(found)
                    } catch(_:SocketTimeoutException) {
                    }
                    prune()
                }
            } catch(_:Exception) {
                if(token==generation)hint.value="局域网扫描不可用，可以粘贴二维码或手动填写。"
            } finally {
                if(beacon===socket)beacon=null
                runCatching{socket?.close()}
                runCatching{if(lock?.isHeld==true)lock.release()}
                if(token==generation)listening.value=false
            }
        }
    }

    fun stopDiscovery() {
        generation++
        discovery?.cancel()
        runCatching{beacon?.close()}
        listening.value=false
    }

    fun launch(target:LinkTarget,ids:List<String>?):Boolean {
        if(progress.value.running)return false
        pending=Pending(target,ids)
        cancelled.set(false)
        ContextCompat.startForegroundService(app,android.content.Intent(app,ComputerLinkService::class.java))
        return true
    }

    fun cancel() {
        cancelled.set(true)
        transport.close()
    }

    suspend fun execute() {
        val request=pending?:return
        cancelled.set(false)
        var activePin=request.target.pin
        try {
            publish(LinkProgress(running=true,phase="正在连接 ${request.target.name.ifBlank{request.target.host}}",target=request.target.host))
            var token=openSession(request.target,activePin){activePin=it}
            val plans=prepare(request.ids)
            if(plans.isEmpty()) {
                publish(LinkProgress(phase="没有可发送的照片",error="没有可发送的照片",target=request.target.host))
                return
            }
            publish(LinkProgress(running=true,phase="正在核对 ${plans.size} 项",total=plans.size,target=request.target.host))
            var manifest=authed(request.target,activePin,token,"POST","/v1/manifest",jsonHeaders(),manifestBody(plans)){activePin=it}
            if(manifest.code==401) {
                token=openSession(request.target.copy(code=""),activePin){activePin=it}
                manifest=authed(request.target,activePin,token,"POST","/v1/manifest",jsonHeaders(),manifestBody(plans)){activePin=it}
            }
            if(manifest.code!=200)throw LinkRejected(manifest.code,errorOf(manifest),"电脑没有接受文件列表")
            val listed=JSONObject(manifest.body.toString(Charsets.UTF_8))
            val need=stringSet(listed.optJSONArray("need"))
            val have=stringSet(listed.optJSONArray("have"))
            var done=0
            var failed=0
            var sent=0L
            for(plan in plans) {
                if(cancelled.get())throw LinkCancelled()
                if(plan.id in have) {
                    done++
                    publish(LinkProgress(running=true,phase="电脑上已有 ${plan.name}",done=done,total=plans.size,bytes=sent,target=request.target.host))
                    continue
                }
                if(plan.id !in need) {
                    failed++
                    continue
                }
                try {
                    publish(LinkProgress(running=true,phase="正在发送 ${plan.name}",done=done,total=plans.size,bytes=sent,target=request.target.host))
                    upload(request.target,plan,token,activePin,{activePin=it}){token=it}
                    sent+=plan.size
                    done++
                } catch(error:LinkCancelled) {
                    throw error
                } catch(_:Exception) {
                    failed++
                }
            }
            val summary=if(failed==0)"已发送 $done 项" else "已发送 $done 项"
            val problem=if(failed>0)"$failed 项没有送出" else ""
            publish(LinkProgress(phase=summary,done=done,total=plans.size,bytes=sent,error=problem,target=request.target.host))
        } catch(_:LinkCancelled) {
            publish(LinkProgress(phase="已停止发送",error="已停止发送",target=request.target.host))
        } catch(error:Exception) {
            publish(LinkProgress(phase="发送没有完成",error=error.message?:"发送没有完成",target=request.target.host))
        } finally {
            pending=null
        }
    }

    private suspend fun prepare(ids:List<String>?):List<SendPlan> {
        val graph=(app as PicHarborApp).graph
        val chosen=ids?.toSet()
        val media=graph.dao.media().filter{chosen==null||it.id in chosen}
        val plans=ArrayList<SendPlan>()
        var unreadable=0
        for(entry in media) {
            if(cancelled.get())throw LinkCancelled()
            // A motion photo is one original file. Do not split it and do not mux a second part.
            if(LinkProtocol.originalCount(entry.name.toByteArray())!=1)continue
            try {
                val (hash,size)=digest(entry.id)
                if(size<=0)continue
                val created=unixSeconds(if(entry.captured>0)entry.captured else entry.modified)
                val name=entry.name.substringAfterLast('/').substringAfterLast('\\').ifBlank{"photo"}
                plans+=SendPlan(entry.id,LinkProtocol.blobId(entry.id),name,size,created,hash)
            } catch(error:LinkCancelled){throw error}
            catch(_:Exception){unreadable++}
        }
        if(plans.isEmpty())throw LinkRejected(0,"file",if(unreadable>0)"无法读取文件" else "没有可发送的照片")
        return plans
    }

    private fun upload(target:LinkTarget,plan:SendPlan,initialToken:String,initialPin:String,pinOut:(String)->Unit,tokenOut:(String)->Unit) {
        var token=initialToken
        var pin=initialPin
        var offset=0L
        var rewound=false
        var serverTries=0
        var reauth=false
        while(offset<plan.size) {
            if(cancelled.get())throw LinkCancelled()
            val length=minOf(CHUNK.toLong(),plan.size-offset).toInt()
            val chunk=readAt(plan.mediaId,offset,length)
            val headers=linkedMapOf(
                "X-File-Name" to Uri.encode(plan.name),
                "X-Size" to plan.size.toString(),
                "X-Offset" to offset.toString(),
                "X-Created" to plan.created.toString(),
                "X-Sha256" to plan.sha256
            )
            val http=authed(target,pin,token,"PUT","/v1/blobs/${plan.id}",headers,chunk){pin=it;pinOut(it)}
            when(http.code) {
                200 -> {
                    val obj=JSONObject(http.body.toString(Charsets.UTF_8))
                    val received=obj.optLong("received",offset)
                    if(obj.optBoolean("complete")||received==plan.size){offset=plan.size;break}
                    if(received<=offset)throw LinkRejected(200,"stall","传输没有前进")
                    offset=received
                    serverTries=0
                    reauth=false
                }
                409 -> {
                    val received=JSONObject(http.body.toString(Charsets.UTF_8)).optLong("received",-1)
                    if(received<0||received>plan.size||received==offset)throw LinkRejected(409,"offset","电脑上的进度无法对齐")
                    offset=received
                }
                400 -> {
                    if(errorOf(http)=="hash"&&!rewound){rewound=true;offset=0;continue}
                    throw LinkRejected(400,"hash","文件校验失败，已停止这份文件")
                }
                401 -> {
                    if(reauth)throw LinkRejected(401,"sign_in","连接已失效")
                    reauth=true
                    token=openSession(target.copy(code=""),pin){pin=it;pinOut(it)}
                    tokenOut(token)
                }
                500 -> {
                    serverTries++
                    if(serverTries>3)throw LinkRejected(500,"import","电脑保存文件失败")
                    sleep()
                }
                else -> throw LinkRejected(http.code,errorOf(http),"电脑拒绝了这份文件")
            }
        }
    }

    private fun openSession(target:LinkTarget,pin:String,pinOut:(String)->Unit):String {
        val http=exchangeOnce(target.host,target.port,pin) { fingerprint ->
            pinOut(fingerprint)
            if(target.code.isNotBlank()) pairBody(fingerprint,target.code) else sessionBody(fingerprint)
        }
        if(http.code==401) {
            val error=errorOf(http)
            if(error=="unknown_device"||error=="bad_signature")identity.forget(http.fingerprint)
            throw LinkRejected(401,error,when(error) {
                "bad_code"->"配对码不正确"
                "bad_signature"->"无法验证这台手机，请重新配对"
                "unknown_device"->"这台电脑还不认识这台手机，请重新输入配对码"
                else->"连接被拒绝"
            })
        }
        if(http.code!=200)throw LinkRejected(http.code,errorOf(http),"电脑没有接受连接")
        val json=JSONObject(http.body.toString(Charsets.UTF_8))
        val reported=json.optString("fp")
        if(reported.isNotEmpty()&&!reported.equals(http.fingerprint,true))throw LinkRejected(0,"pin","电脑证书和配对信息不一致")
        if(target.code.isNotBlank())identity.remember(http.fingerprint)
        return json.getString("token")
    }

    private fun pairBody(fingerprint:String,code:String):LinkRequest {
        val nonce=LinkProtocol.nonce()
        val body=JSONObject()
            .put("deviceId",identity.deviceId)
            .put("deviceName",Build.MODEL)
            .put("platform","android")
            .put("nonce",nonce)
            .put("mac",LinkProtocol.pairMac(code,identity.deviceId,nonce,fingerprint))
            .put("publicKey",try{identity.publicKey()}catch(_:Exception){throw LinkRejected(0,"key","无法准备这台手机的密钥")})
            .toString().toByteArray(Charsets.UTF_8)
        return LinkRequest("POST","/v1/pair",jsonHeaders(),body)
    }

    private fun sessionBody(fingerprint:String):LinkRequest {
        if(!identity.isPaired(fingerprint))throw LinkRejected(0,"need_code","请输入电脑上的 6 位配对码")
        val nonce=LinkProtocol.nonce()
        val signature=try{identity.sign(LinkProtocol.sessionMessage(nonce,fingerprint))}catch(_:Exception){throw LinkRejected(0,"key","无法准备这台手机的密钥")}
        val body=JSONObject().put("deviceId",identity.deviceId).put("nonce",nonce).put("signature",signature).toString().toByteArray(Charsets.UTF_8)
        return LinkRequest("POST","/v1/session",jsonHeaders(),body)
    }

    private fun authed(target:LinkTarget,pin:String,token:String,method:String,path:String,headers:Map<String,String>,body:ByteArray,pinOut:(String)->Unit):LinkHttp {
        val sent=LinkedHashMap(headers)
        sent["Authorization"]="Bearer $token"
        return exchangeOnce(target.host,target.port,pin) { fingerprint ->
            pinOut(fingerprint)
            LinkRequest(method,path,sent,body)
        }
    }

    private fun exchangeOnce(host:String,port:Int,pin:String,build:(String)->LinkRequest):LinkHttp {
        var last:Exception?=null
        for(attempt in 0 until 3) {
            if(cancelled.get())throw LinkCancelled()
            try { return transport.exchange(host,port,pin,build) }
            catch(error:LinkCancelled){throw error}
            catch(error:LinkRejected){throw error}
            catch(error:Exception) {
                if(cancelled.get())throw LinkCancelled()
                if(LinkTransport.pinFailure(error))throw LinkRejected(0,"pin","电脑证书和配对信息不一致")
                last=error
                if(attempt==2)break
                sleep()
            }
        }
        throw LinkRejected(0,"network",last?.message?:"无法连接这台电脑")
    }

    private fun manifestBody(plans:List<SendPlan>):ByteArray {
        val items=JSONArray()
        for(plan in plans) {
            items.put(JSONObject().put("id",plan.id).put("name",plan.name).put("size",plan.size).put("created",plan.created)
                .put("sha256",plan.sha256).put("pairId","").put("role",""))
        }
        return JSONObject().put("model",Build.MODEL).put("items",items).toString().toByteArray(Charsets.UTF_8)
    }

    private fun digest(uri:String):Pair<String,Long> {
        val digest=MessageDigest.getInstance("SHA-256")
        var total=0L
        app.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val buffer=ByteArray(1024*1024)
            while(true) {
                if(cancelled.get())throw LinkCancelled()
                val count=input.read(buffer)
                if(count<0)break
                digest.update(buffer,0,count)
                total+=count
            }
        }?:throw LinkRejected(0,"file","无法读取文件")
        return digest.digest().toHex() to total
    }

    private fun readAt(uri:String,offset:Long,length:Int):ByteArray {
        val descriptor=app.contentResolver.openFileDescriptor(Uri.parse(uri),"r")?:throw LinkRejected(0,"file","无法读取文件")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            try { input.channel.position(offset) } catch(_:Exception) {
                var skip=offset
                val buffer=ByteArray(64*1024)
                while(skip>0) {
                    val count=input.read(buffer,0,minOf(buffer.size.toLong(),skip).toInt())
                    if(count<0)throw LinkRejected(0,"file","无法读取文件")
                    skip-=count
                }
            }
            val out=ByteArray(length)
            var filled=0
            while(filled<length) {
                if(cancelled.get())throw LinkCancelled()
                val count=input.read(out,filled,length-filled)
                if(count<0)throw LinkRejected(0,"file","文件在传输中发生变化")
                filled+=count
            }
            out
        }
    }

    private fun join(socket:MulticastSocket) {
        val group=InetAddress.getByName(LinkProtocol.GROUP)
        var joined=0
        val interfaces=NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for(nif in interfaces) {
            if(!nif.isUp||nif.isLoopback||!nif.supportsMulticast())continue
            if(nif.inetAddresses.toList().none{it is Inet4Address&&!it.isLoopbackAddress})continue
            try {
                socket.joinGroup(InetSocketAddress(group,LinkProtocol.PORT),nif)
                joined++
            } catch(_:Exception) {}
        }
        if(joined==0)throw java.io.IOException("multicast")
    }

    private fun merge(found:DiscoveredComputer) {
        val key="${found.host}:${found.port}:${found.fingerprint}"
        synchronized(seen){seen[key]=found.copy(seenAt=System.currentTimeMillis())}
        publishSeen()
    }

    private fun prune() {
        val now=System.currentTimeMillis()
        synchronized(seen){seen.entries.removeIf{now-it.value.seenAt>7_000}}
        publishSeen()
    }

    private fun publishSeen() {
        computers.value=synchronized(seen){seen.values.sortedBy{it.name.lowercase()}}
    }

    private fun publish(value:LinkProgress) {
        progress.value=value
        notifier(value)
    }

    private fun sleep() {
        try { Thread.sleep(800) } catch(_:InterruptedException){throw LinkCancelled()}
    }

    private fun errorOf(http:LinkHttp)=try{JSONObject(http.body.toString(Charsets.UTF_8)).optString("error")}catch(_:Exception){""}
    private fun stringSet(array:JSONArray?):Set<String> {
        if(array==null)return emptySet()
        return (0 until array.length()).map{array.optString(it)}.filter{it.isNotEmpty()}.toSet()
    }
    private fun jsonHeaders()=mapOf("Content-Type" to "application/json; charset=utf-8")
    private fun unixSeconds(value:Long):Long {
        var number=value
        if(number>10_000_000_000L)number/=1000
        return if(number>0)number else 0
    }

    companion object { private const val CHUNK=4*1024*1024 }
}
