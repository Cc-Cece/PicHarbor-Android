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
data class LinkAsk(val offerId:String,val count:Int,val title:String,val target:LinkTarget,val mediaIds:List<String>)

/** Phone to a Windows computer on the same LAN. Original files only; motion photos stay whole. */
class ComputerLink(private val app:Application) {
    val computers=MutableStateFlow<List<DiscoveredComputer>>(emptyList())
    val progress=MutableStateFlow(LinkProgress())
    val listening=MutableStateFlow(false)
    val hint=MutableStateFlow("")
    val incoming=MutableStateFlow<LinkAsk?>(null)
    var notifier:(LinkProgress)->Unit={}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val transport=LinkTransport()
    private val identity by lazy{LinkIdentity(app)}
    private val seen=LinkedHashMap<String,DiscoveredComputer>()
    private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var beacon:MulticastSocket?=null
    @Volatile private var discovery:Job?=null
    @Volatile private var presence:Job?=null
    @Volatile private var pending:Pending?=null
    @Volatile private var generation=0
    private val catalogStamps=java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val catalogRevisions=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val responding=java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Pending(val target:LinkTarget,val ids:List<String>?,val offerId:String?=null)
    private data class Session(val target:LinkTarget,val token:String,val pin:String,val blocked:String?=null)
    private data class SendPlan(val mediaId:String,val id:String,val name:String,val size:Long,val created:Long,val sha256:String,val group:String=id,val role:String="")

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

    fun startPresence() {
        if(presence?.isActive==true)return
        presence=scope.launch {
            while(isActive) {
                runCatching{checkIncoming()}
                delay(3_000)
            }
        }
    }

    fun stopPresence() {
        presence?.cancel()
        presence=null
    }

    fun confirmIncoming() {
        val ask=incoming.value?:return
        if(progress.value.running)return
        responding.set(true)
        incoming.value=null
        pending=Pending(ask.target,ask.mediaIds,ask.offerId)
        cancelled.set(false)
        ContextCompat.startForegroundService(app,android.content.Intent(app,ComputerLinkService::class.java))
    }

    fun declineIncoming() {
        if(responding.get())return
        val ask=incoming.value?:return
        incoming.value=null
        scope.launch {
            runCatching {
                val session=connect(ask.target)
                call(session,"POST","/v1/offers/${ask.offerId}/reject",ByteArray(0))
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        transport.close()
    }

    private val unreadableNames=mutableListOf<String>()

    suspend fun execute() {
        val request=pending?:return
        cancelled.set(false)
        unreadableNames.clear()
        try {
            publish(LinkProgress(running=true,phase="正在连接 ${request.target.name.ifBlank{request.target.host}}",target=request.target.host))
            var session=connect(request.target)
            val checked=checkIn(session)
            session=checked
            val blocked=checked.blocked
            if(blocked!=null) {
                publish(LinkProgress(phase=blocked,error=blocked,target=session.target.host))
                return
            }
            if(request.offerId!=null) {
                val offerId=request.offerId
                call(session,"POST","/v1/offers/$offerId/accept",ByteArray(0))
                var complete=false
                try {
                    val plans=if(request.ids.isNullOrEmpty())emptyList() else prepare(request.ids)
                    sendPlans(session,plans)
                    complete=unreadableNames.isEmpty()
                } finally { finishOffer(session,offerId,complete) }
                return
            }
            val plans=prepare(request.ids)
            publish(LinkProgress(running=true,phase="正在等待电脑确认…",total=plans.map{it.group}.toSet().size,target=session.target.host))
            val offerId=createOffer(session,if(request.ids==null)"library" else "selected",plans.map{it.group}.toSet().size)
            if(!waitForComputer(session,offerId)) {
                publish(LinkProgress(phase="电脑没有确认",error="电脑没有确认",target=session.target.host))
                return
            }
            var complete=false
            try{sendPlans(session,plans);complete=unreadableNames.isEmpty()}finally{finishOffer(session,offerId,complete)}
        } catch(_:LinkCancelled) {
            publish(LinkProgress(phase="已停止发送",error="已停止发送",target=request.target.host))
        } catch(error:Exception) {
            publish(LinkProgress(phase="发送没有完成",error=error.message?:"发送没有完成",target=request.target.host))
        } finally {
            pending=null
            responding.set(false)
        }
    }

    private fun finishOffer(session:Session,id:String,complete:Boolean) {
        val details=if(unreadableNames.isEmpty())"读取或传输失败，可重新发起备份重试" else "无法读取："+unreadableNames.take(20).joinToString("、")
        val body=JSONObject().put("success",complete).put("error",if(complete)JSONObject.NULL else details)
        runCatching{call(session,"POST","/v1/offers/$id/finish",body.toString().toByteArray(Charsets.UTF_8))}
        if(!complete)hint.value=details
    }

    private fun connect(target:LinkTarget):Session {
        val hosts=LinkProtocol.orderAddresses(listOf(target.host)+target.alts,localIpv4())
        var last:Exception?=null
        for((index,host) in hosts.withIndex()) {
            try {
                var pin=target.pin
                val token=openSession(target.copy(host=host),pin,if(index==0)3 else 1){pin=it}
                return Session(target.copy(host=host),token,pin)
            } catch(error:LinkRejected) {
                if(error.error!="network"||index==hosts.lastIndex)throw error
                last=error
            }
        }
        throw last?:LinkRejected(0,"network","无法连接这台电脑")
    }

    private suspend fun checkIncoming() {
        if(progress.value.running||responding.get()||incoming.value!=null)return
        if(progress.value.running||responding.get())return
        val endpoint=identity.endpoint()?:return
        val target=LinkTarget(endpoint.host,endpoint.port,endpoint.fingerprint,"",endpoint.name,endpoint.alts)
        val session=try{checkIn(connect(target))}catch(_:Exception){return}
        exchangeCatalog(session)
        if(progress.value.running||responding.get()||incoming.value!=null)return
        val blocked=session.blocked
        if(blocked!=null) {
            if(hint.value!=blocked)hint.value=blocked
            return
        }
        if(incoming.value!=null)return
        val listed=JSONObject(call(session,"GET","/v1/offers",ByteArray(0)).toString(Charsets.UTF_8))
        val offers=listed.optJSONArray("offers")?:JSONArray()
        var offer:JSONObject?=null
        for(index in 0 until offers.length()) {
            val item=offers.optJSONObject(index)?:continue
            if(item.optString("direction")=="computer-to-phone"){offer=item;break}
        }
        val chosen=offer?:return
        val id=chosen.optString("id")
        if(id.isEmpty())return
        val scope=chosen.optString("scope").ifBlank{"all"}
        val entries=try { mediaFor(scope,null) } catch(error:Exception) {
            hint.value=error.message?:"范围无效，请更新应用"
            runCatching{call(session,"POST","/v1/offers/$id/reject",ByteArray(0))}
            return
        }
        incoming.value=LinkAsk(id,LinkScope.logicalCount(entries),"将发送 ${LinkScope.logicalCount(entries)} 项到电脑\n${LinkScope.parse(scope).summary()}",session.target,entries.map{it.id})
    }

    private fun checkIn(session:Session):Session {
        val report=LinkArchiveFile.report()
        val raw=report.value?.trim()?.ifEmpty{null}
        val presented=when {
            raw!=null->raw
            report.unreadable->null
            else->identity.archiveCode()
        }
        val unreadable=report.unreadable&&presented==null
        val body=JSONObject().put("name",Build.MODEL).put("platform","android").put("directory",report.directoryReady).put("unreadable",unreadable).put("capabilities",JSONArray(listOf("scope2","catalog2")))
        if(presented!=null)body.put("archiveId",presented)
        val hello=JSONObject(call(session,"POST","/v1/hello",body.toString().toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
        val deliver=hello.optString("deliver").trim()
        var current=session
        if(LinkProtocol.usableArchive(deliver)) {
            identity.storeArchive(deliver)
            LinkArchiveFile.write(deliver)
            val again=JSONObject().put("name",Build.MODEL).put("platform","android").put("directory",true).put("unreadable",false).put("archiveId",deliver).put("capabilities",JSONArray(listOf("scope2","catalog2")))
            val echoed=JSONObject(call(session,"POST","/v1/hello",again.toString().toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
            if(echoed.optBoolean("conflict"))return session.copy(blocked="这台手机的身份和电脑上的记录不一致。请确认是不是连错了设备。")
            current=session
        } else if(hello.optBoolean("conflict")) {
            return session.copy(blocked="这台手机的身份和电脑上的记录不一致。请确认是不是连错了设备。")
        }
        val usable=when {
            LinkProtocol.usableArchive(deliver)->deliver
            presented!=null&&LinkProtocol.usableArchive(presented)->presented
            else->identity.archiveCode()
        }
        if(usable==null) {
            val message=when {
                unreadable->"暂时读不到识别码，没有生成新的识别码。"
                raw!=null->"识别码文件里的内容不能用。"
                else->"还没有这台手机的识别码。"
            }
            return current.copy(blocked=message)
        }
        return current
    }

    private fun createOffer(session:Session,scope:String,count:Int):String {
        val body=JSONObject().put("direction","phone-to-computer").put("scope",scope).put("count",count)
        val created=JSONObject(call(session,"POST","/v1/offers",body.toString().toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
        val id=created.optString("id")
        if(id.isEmpty())throw LinkRejected(0,"no_offer","电脑没有接受这次请求")
        return id
    }

    private fun waitForComputer(session:Session,offerId:String):Boolean {
        val deadline=System.currentTimeMillis()+120_000
        while(System.currentTimeMillis()<deadline) {
            if(cancelled.get()) {
                runCatching{call(session,"POST","/v1/offers/$offerId/reject",ByteArray(0))}
                throw LinkCancelled()
            }
            val listed=JSONObject(call(session,"GET","/v1/offers",ByteArray(0)).toString(Charsets.UTF_8))
            if(listed.optBoolean("upload"))return true
            val offers=listed.optJSONArray("offers")?:JSONArray()
            var seen=false
            for(index in 0 until offers.length()) if(offers.optJSONObject(index)?.optString("id")==offerId)seen=true
            if(!seen)return false
            try{Thread.sleep(1_000)}catch(_:InterruptedException){throw LinkCancelled()}
        }
        return false
    }

    private fun sendPlans(session:Session,plans:List<SendPlan>) {
        if(plans.isEmpty()) {
            publish(LinkProgress(phase="已发送 0 项",done=0,total=0,target=session.target.host))
            return
        }
        val groups=plans.groupBy{it.group}.values
        publish(LinkProgress(running=true,phase="正在核对 ${groups.size} 项",total=groups.size,target=session.target.host))
        var token=session.token
        var pin=session.pin
        var manifest=authed(session.target,pin,token,"POST","/v1/manifest",jsonHeaders(),manifestBody(plans)){pin=it}
        if(manifest.code==401) {
            token=openSession(session.target.copy(code=""),pin){pin=it}
            manifest=authed(session.target,pin,token,"POST","/v1/manifest",jsonHeaders(),manifestBody(plans)){pin=it}
        }
        if(manifest.code!=200)throw LinkRejected(manifest.code,errorOf(manifest),reason(errorOf(manifest),"电脑没有接受文件列表"))
        val listed=JSONObject(manifest.body.toString(Charsets.UTF_8))
        val need=stringSet(listed.optJSONArray("need"))
        val have=stringSet(listed.optJSONArray("have"))
        var done=0
        var failed=0
        var sent=0L
        for(group in groups) {
            var complete=true
            for(plan in group) {
                if(cancelled.get())throw LinkCancelled()
                if(plan.id in have)continue
                if(plan.id !in need) { unreadableNames+=plan.name; complete=false; break }
                try {
                    publish(LinkProgress(running=true,phase="正在发送 ${plan.name}",done=done,total=groups.size,bytes=sent,target=session.target.host))
                    upload(session.target,plan,token,pin,{pin=it}){token=it}
                    sent+=plan.size
                } catch(error:LinkCancelled) { throw error }
                catch(_:Exception) { unreadableNames+=plan.name; complete=false; break }
            }
            if(complete)done++ else failed++
        }
        val problem=if(failed>0)"$failed 项没有送出" else ""
        publish(LinkProgress(phase="已发送 $done 项",done=done,total=groups.size,bytes=sent,error=problem,target=session.target.host))
    }

    private fun call(session:Session,method:String,path:String,body:ByteArray):ByteArray {
        var pin=session.pin
        var token=session.token
        var http=authed(session.target,pin,token,method,path,jsonHeaders(),body){pin=it}
        if(http.code==401) {
            token=openSession(session.target.copy(code=""),pin){pin=it}
            http=authed(session.target,pin,token,method,path,jsonHeaders(),body){pin=it}
        }
        if(http.code!=200)throw LinkRejected(http.code,errorOf(http),reason(errorOf(http),"电脑没有接受这次请求"))
        return http.body
    }

    private suspend fun exchangeCatalog(session:Session) {
        val response=runCatching{JSONObject(call(session,"GET","/v1/catalog",ByteArray(0)).toString(Charsets.UTF_8))}.getOrNull()?:return
        val revision=response.optLong("revision")
        val graph=(app as PicHarborApp).graph
        val previous=graph.dao.media()
        val stamp=previous.hashCode()*31+graph.media.hasAccess().hashCode()
        if(catalogRevisions[session.pin]==revision&&catalogStamps[session.pin]==stamp)return
        graph.media.scan()
        val entries=graph.dao.media().filter{it.source!="media"||graph.media.hasAccess()}
        val report=try{val criteria=LinkScope.parse(response.getString("scope")); criteria.report(revision,entries,protectedMotion(entries,criteria))}
            catch(error:Exception){JSONObject().put("revision",revision).put("timeZone",java.time.ZoneId.systemDefault().id)
                .put("albums",JSONArray()).put("count",0).put("bytes",0).put("unknownSize",0).put("unknownDate",0).put("error",error.message?:"范围无效")}
        call(session,"POST","/v1/catalog",report.toString().toByteArray(Charsets.UTF_8))
        catalogRevisions[session.pin]=revision
        catalogStamps[session.pin]=entries.hashCode()*31+graph.media.hasAccess().hashCode()
    }

    private fun protectedMotion(entries:List<MediaEntry>,criteria:LinkScope):Set<String> {
        if(!criteria.ignoreSmallImages)return emptySet()
        return entries.filter{!it.video&&it.size in 1 until criteria.minImageKb*1024L}.filter{entry->
            runCatching { app.contentResolver.openInputStream(Uri.parse(entry.id))?.use{stream ->
                val bytes=ByteArray(256*1024); val count=stream.read(bytes)
                val header=if(count>0)String(bytes,0,count,Charsets.ISO_8859_1) else ""
                header.contains("MotionPhoto")||header.contains("MicroVideo")
            }?:false }.getOrDefault(false)
        }.map{it.id}.toSet()
    }

    private suspend fun mediaFor(scope:String,ids:List<String>?):List<MediaEntry> {
        val graph=(app as PicHarborApp).graph
        graph.media.scan()
        val chosen=ids?.toSet()
        val entries=graph.dao.media().filter{chosen==null||it.id in chosen}.filter{it.source!="media"||graph.media.hasAccess()}
        val criteria=LinkScope.parse(scope)
        return criteria.select(entries,protectedMotion(entries,criteria))
    }

    private fun localIpv4():String? {
        val interfaces=NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for(nif in interfaces) {
            if(!nif.isUp||nif.isLoopback)continue
            for(address in nif.inetAddresses) if(address is Inet4Address&&!address.isLoopbackAddress)return address.hostAddress
        }
        return null
    }

    private fun reason(error:String,fallback:String)=when(error) {
        "no_archive"->"请先在电脑设置中配置归档目录"
        "no_offer"->"电脑没有接受这次请求"
        "need_accept"->"电脑还没有确认"
        else->fallback
    }

    private fun millis(value:Long):Long {
        if(value<=0)return 0
        return if(value>10_000_000_000L)value else value*1000
    }

    private suspend fun prepare(ids:List<String>?):List<SendPlan> {
        val graph=(app as PicHarborApp).graph
        val chosen=ids?.toSet()
        val media=graph.dao.media().filter{chosen==null||it.id in chosen}
        val pairs=media.groupBy(LinkScope::groupKey).filterValues{it.size==2&&it.any{e->e.video}&&it.any{e->!e.video}}.keys
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
                val paired=LinkScope.groupKey(entry) in pairs
                plans+=SendPlan(entry.id,LinkProtocol.blobId(entry.id),name,size,created,hash,if(paired)LinkProtocol.blobId(LinkScope.groupKey(entry)) else LinkProtocol.blobId(entry.id),if(paired)if(entry.video)"video" else "still" else "")
            } catch(error:LinkCancelled){throw error}
            catch(_:Exception){unreadable++;unreadableNames+=entry.name}
        }
        val preparedIds=plans.map{it.mediaId}.toSet()
        val incomplete=media.groupBy(LinkScope::groupKey).values.filter{group->group.size==2&&group.any{it.video}&&group.any{!it.video}&&group.any{it.id !in preparedIds}}
        val exclude=incomplete.flatten().map{it.id}.toSet()
        incomplete.flatten().forEach{if(it.name !in unreadableNames)unreadableNames+=it.name}
        val complete=plans.filter{it.mediaId !in exclude}
        if(complete.isEmpty())throw LinkRejected(0,"file",if(unreadable>0||incomplete.isNotEmpty())"无法读取完整媒体，请刷新后重试" else "没有可发送的照片")
        return complete
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

    private fun openSession(target:LinkTarget,pin:String,attempts:Int=3,pinOut:(String)->Unit):String {
        val http=exchangeOnce(target.host,target.port,pin,attempts) { fingerprint ->
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
        identity.saveEndpoint(target.host,target.port,http.fingerprint,json.optString("name").ifBlank{target.name},target.alts)
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

    private fun exchangeOnce(host:String,port:Int,pin:String,attempts:Int=3,build:(String)->LinkRequest):LinkHttp {
        var last:Exception?=null
        for(attempt in 0 until attempts) {
            if(cancelled.get())throw LinkCancelled()
            try { return transport.exchange(host,port,pin,build) }
            catch(error:LinkCancelled){throw error}
            catch(error:LinkRejected){throw error}
            catch(error:Exception) {
                if(cancelled.get())throw LinkCancelled()
                if(LinkTransport.pinFailure(error))throw LinkRejected(0,"pin","电脑证书和配对信息不一致")
                last=error
                if(attempt==attempts-1)break
                sleep()
            }
        }
        throw LinkRejected(0,"network",last?.message?:"无法连接这台电脑")
    }

    private fun manifestBody(plans:List<SendPlan>):ByteArray {
        val items=JSONArray()
        for(plan in plans) {
            items.put(JSONObject().put("id",plan.id).put("name",plan.name).put("size",plan.size).put("created",plan.created)
                .put("sha256",plan.sha256).put("pairId",if(plan.role.isEmpty())"" else plan.group).put("role",plan.role))
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
        val now=System.currentTimeMillis()
        synchronized(seen) {
            val previous=seen[found.fingerprint]
            val hosts=LinkProtocol.orderAddresses((found.hosts+listOf(found.host)+(previous?.hosts?:emptyList())),localIpv4())
            val host=hosts.firstOrNull()?:found.host
            seen[found.fingerprint]=found.copy(host=host,seenAt=now,hosts=hosts)
            val known=identity.endpoint()
            if(known?.fingerprint==found.fingerprint&&(known.host!=host||known.port!=found.port)) {
                identity.saveEndpoint(host,found.port,found.fingerprint,found.name,hosts.filter{address->address!=host})
            }
        }
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
