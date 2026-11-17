package com.cccece.picharbor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.security.*
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

data class ComputerTarget(val host:String,val port:Int,val fingerprint:String,val code:String)
data class LinkProgress(val busy:Boolean=false,val status:String="",val fraction:Float=0f,val sent:Int=0,val existing:Int=0,val failed:Int=0)

object LinkWire {
    fun hex(value:ByteArray)=value.joinToString(""){"%02x".format(it)}
    fun hash(value:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(value))
    fun ipv4(value:String):Boolean {
        val parts=value.split('.')
        return parts.size==4&&parts.all{it.matches(Regex("0|[1-9][0-9]{0,2}"))&&(it.toIntOrNull()?:256) in 0..255}
    }
    fun target(host:String,port:String,fp:String,code:String):ComputerTarget {
        val h=host.trim();val p=port.toIntOrNull()?:0;val f=fp.trim().lowercase();val c=code.trim()
        require(ipv4(h)&&p in 1..65535&&f.matches(Regex("[0-9a-f]{16}|[0-9a-f]{64}"))&&(c.isEmpty()||c.matches(Regex("[0-9]{6}"))))
        return ComputerTarget(h,p,f,c)
    }
    fun qr(value:String):ComputerTarget {
        require(value.length<=2048)
        val uri=URI(value.trim());require(uri.scheme=="picharbor"&&uri.host=="link"&&uri.path.orEmpty() in setOf("","/"))
        val fields=uri.rawQuery.orEmpty().split('&').associate{val pair=it.split('=',limit=2);pair[0] to java.net.URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")}
        require(fields["v"]=="1"&&fields["fp"].orEmpty().length==64)
        return target(fields["host"].orEmpty(),fields["port"].orEmpty(),fields["fp"].orEmpty(),fields["code"].orEmpty())
    }
    fun mac(id:String,nonce:String,fp:String,code:String):String {
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(code.toByteArray(),"HmacSHA256"))
        return hex(mac.doFinal(listOf("picharbor-link-v1","pair",id,nonce,fp.lowercase()).joinToString("\n").toByteArray()))
    }
    fun rawSignature(der:ByteArray):ByteArray {
        var at=2;require(der[0].toInt()==0x30&&der[at++].toInt()==2)
        val rLength=der[at++].toInt() and 255;val r=der.copyOfRange(at,at+rLength);at+=rLength
        require(der[at++].toInt()==2);val sLength=der[at++].toInt() and 255;val s=der.copyOfRange(at,at+sLength)
        fun integer(value:ByteArray):ByteArray {val bytes=value.dropWhile{it==0.toByte()}.toByteArray();require(bytes.size<=32);return ByteArray(32-bytes.size)+bytes}
        return integer(r)+integer(s)
    }
}

data class IncomingComputerOffer(val id:String,val scope:String,val count:Int,val host:String)

class LiveLinkTransport(private val graph:AppGraph) {
    private data class Session(val target:ComputerTarget,val token:String)
    private data class Prepared(val file:File,val item:JSONObject)
    val progress=MutableStateFlow(LinkProgress())
    val incoming=MutableStateFlow<IncomingComputerOffer?>(null)
    private var job:Job?=null
    private var presence:Job?=null
    @Volatile private var cached:Session?=null
    @Volatile private var incomingSession:Session?=null
    private val connections=java.util.concurrent.ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val prefs=graph.app.getSharedPreferences("computer-link",0)
    fun saved():ComputerTarget?=runCatching{LinkWire.target(prefs.getString("host","").orEmpty(),prefs.getInt("port",0).toString(),prefs.getString("fp","").orEmpty(),"")}.getOrNull()
    fun forget(){stop();cached=null;incoming.value=null;incomingSession=null;prefs.edit().remove("host").remove("port").remove("fp").apply()}
    fun stopSending(){job?.cancel();connections.forEach{it.disconnect()}}
    fun stop(){presence?.cancel();stopSending();incoming.value=null;incomingSession=null}
    private fun identity():Pair<String,KeyPair> {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val alias="picharbor.mobile.link.p256"
        if(!store.containsAlias(alias))KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        val id=prefs.getString("device",null)?:java.util.UUID.randomUUID().toString().also{check(prefs.edit().putString("device",it).commit())}
        return id to KeyPair(store.getCertificate(alias).publicKey,store.getKey(alias,null) as PrivateKey)
    }
    private fun nonce()=ByteArray(16).also{SecureRandom().nextBytes(it)}.let(LinkWire::hex)
    private suspend fun request(target:ComputerTarget,method:String,path:String,body:ByteArray?=null,token:String="",headers:Map<String,String> = emptyMap()):Triple<Int,JSONObject,String> {
        if(!path.endsWith("/finish"))currentCoroutineContext().ensureActive()
        require(path.startsWith("/")&&!path.contains(".."))
        var seen=""
        val manager=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String){throw java.security.cert.CertificateException()}
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                if(chain.isEmpty())throw java.security.cert.CertificateException()
                seen=LinkWire.hash(chain.first().encoded)
                if(!seen.startsWith(target.fingerprint)||target.fingerprint.length !in setOf(16,64))throw java.security.cert.CertificateException("Certificate pin mismatch")
            }
        }
        val context=SSLContext.getInstance("TLS").apply{init(null,arrayOf(manager),SecureRandom())}
        val http=java.net.URL("https://${target.host}:${target.port}$path").openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection
        connections.add(http)
        http.sslSocketFactory=context.socketFactory;http.hostnameVerifier=HostnameVerifier{host,_->host==target.host}
        http.instanceFollowRedirects=false;http.requestMethod=method;http.connectTimeout=20000;http.readTimeout=if(method=="PUT")180000 else 30000
        if(token.isNotBlank()){require(token.none{it=='\n'||it=='\r'});http.setRequestProperty("Authorization","Bearer $token")}
        headers.forEach{(k,v)->http.setRequestProperty(k,v)}
        try {
            if(body!=null){http.doOutput=true;http.setFixedLengthStreamingMode(body.size);http.setRequestProperty("Content-Type",if(method=="PUT")"application/octet-stream" else "application/json");http.outputStream.use{it.write(body)}}
            val status=http.responseCode
            val stream=if(status in 200..299)http.inputStream else http.errorStream
            val text=stream?.use{input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=4*1024*1024);output.write(buffer,0,count)}
                output.toString("UTF-8")
            }.orEmpty()
            return Triple(status,if(text.isBlank())JSONObject() else runCatching{JSONObject(text)}.getOrDefault(JSONObject()),seen)
        } finally {connections.remove(http);http.disconnect()}
    }
    private suspend fun json(peer:Session,method:String,path:String,body:JSONObject?=null):JSONObject {
        val result=request(peer.target,method,path,body?.toString()?.toByteArray(),peer.token)
        if(result.first!=200)throw EngineFailure("LIVELINK_REJECTED")
        return result.second
    }
    private suspend fun establish(target:ComputerTarget):Session {
        val full=request(target,"GET","/").third
        if(full.length!=64)throw EngineFailure("LIVELINK_PIN")
        val pinned=target.copy(fingerprint=full)
        val(id,key)=identity();val nonce=nonce()
        val response=if(target.code.isNotBlank()) {
            val ec=key.public as ECPublicKey
            fun bytes(value:java.math.BigInteger)=value.toByteArray().takeLast(32).toByteArray().let{ByteArray(32-it.size)+it}
            val point=byteArrayOf(4)+bytes(ec.w.affineX)+bytes(ec.w.affineY)
            request(pinned,"POST","/v1/pair",JSONObject().put("deviceId",id).put("deviceName",android.os.Build.MODEL).put("platform","android").put("nonce",nonce)
                .put("mac",LinkWire.mac(id,nonce,full,target.code)).put("publicKey",Base64.encodeToString(point,Base64.NO_WRAP)).toString().toByteArray())
        } else {
            val signature=Signature.getInstance("SHA256withECDSA").apply{initSign(key.private);update(listOf("picharbor-link-v1","session",nonce,full).joinToString("\n").toByteArray())}.sign()
            request(pinned,"POST","/v1/session",JSONObject().put("deviceId",id).put("nonce",nonce).put("signature",Base64.encodeToString(LinkWire.rawSignature(signature),Base64.NO_WRAP)).toString().toByteArray())
        }
        if(response.first!=200)throw EngineFailure("LIVELINK_REJECTED")
        val token=response.second.getString("token");require(token.isNotBlank()&&token.none{it=='\r'||it=='\n'})
        val peer=Session(pinned.copy(code=""),token)
        check(prefs.edit().putString("host",pinned.host).putInt("port",pinned.port).putString("fp",full).commit())
        cached=peer
        return peer
    }
    private suspend fun hello(peer:Session) {
        val response=json(peer,"POST","/v1/hello",JSONObject().put("name",android.os.Build.MODEL).put("platform","android").put("directory",false).put("unreadable",false).put("capabilities",JSONArray().put("scope2").put("catalog2")))
        val result=graph.harborCore.dispatch("livelink_handshake",JSONObject().put("capabilities",response.optJSONArray("capabilities")?:JSONArray()))
        if(!result.optBoolean("ok"))throw EngineFailure("LIVELINK_CAPABILITY_MISSING")
    }
    private fun logical(all:List<MediaEntry>)=all.filter{!it.video||LivePhotoPairing.findPhotoPartner(it,all)==null}
    private suspend fun select(scope:String,all:List<MediaEntry>):List<MediaEntry> {
        val selected=ArrayList<MediaEntry>()
        for(media in logical(all)) {
            val paired=LivePhotoPairing.findPair(media,all)!=null
            val asset=LiveLink.assetJson(media.id,media.name,media.size,if(paired)"live_photo" else if(media.video)"video" else "photo",media.captured,media.folderKey)
                .put("created_at",java.time.Instant.ofEpochMilli(media.captured).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
                .put("in_default_album",media.source=="media"&&(media.folderName.equals("Camera",true)||media.folderName.equals("Screenshots",true)))
            if(media.captured<=0)asset.remove("created_at")
            val result=graph.harborCore.dispatch("livelink_scope",JSONObject().put("scope",scope).put("asset",asset))
            if(!result.optBoolean("ok"))throw EngineFailure("INVALID_SCOPE")
            if(result.optBoolean("in_scope"))selected+=media
        }
        // Validate even an empty library, so an unsupported scope never appears accepted.
        if(all.isEmpty()) {
            val check=graph.harborCore.dispatch("livelink_scope",JSONObject().put("scope",scope).put("asset",JSONObject().put("local_id","empty").put("filename","empty").put("media_kind","photo").put("size",0)))
            if(!check.optBoolean("ok"))throw EngineFailure("INVALID_SCOPE")
        }
        return selected
    }
    fun startPresence() {
        if(presence?.isActive==true)return
        presence=graph.scope.launch {
            graph.startup.await()
            var revision=-1L;var catalogAt=0L
            while(isActive&&graph.visible.get()) {
                try {
                    val target=saved()?:break
                    if(!progress.value.busy) {
                        val peer=cached?.takeIf{it.target.host==target.host&&it.target.fingerprint==target.fingerprint}?:establish(target)
                        hello(peer)
                        val request=json(peer,"GET","/v1/catalog")
                        if(request.optLong("revision")!=revision||System.currentTimeMillis()-catalogAt>15000) {
                            val all=graph.backup.identifiedMedia()
                            val scope=request.optString("scope","all")
                            val report=JSONObject().put("revision",request.optLong("revision")).put("timeZone",java.time.ZoneId.systemDefault().id)
                            try {
                                val selected=select(scope,all)
                                val albums=JSONArray()
                                for((id,rows) in all.groupBy{it.folderKey}) {
                                    val entries=logical(rows)
                                    albums.put(JSONObject().put("id",id).put("name",rows.first().folderName).put("count",entries.size).put("bytes",rows.sumOf{it.size.coerceAtLeast(0)})
                                        .put("isDefault",rows.first().folderName.equals("Camera",true)||rows.first().folderName.equals("Screenshots",true)))
                                }
                                report.put("albums",albums).put("count",selected.size).put("bytes",selected.sumOf{it.size.coerceAtLeast(0)+(LivePhotoPairing.findPair(it,all)?.size?:0).coerceAtLeast(0)})
                                    .put("unknownSize",selected.count{it.size<=0}).put("unknownDate",selected.count{it.captured<=0})
                            } catch(_:Exception) {report.put("albums",JSONArray()).put("count",0).put("bytes",0).put("unknownSize",0).put("unknownDate",0).put("error","不支持此备份范围，请更新 PicHarbor。")}
                            json(peer,"POST","/v1/catalog",report);revision=request.optLong("revision");catalogAt=System.currentTimeMillis()
                        }
                        if(incoming.value==null) {
                            val offers=json(peer,"GET","/v1/offers").optJSONArray("offers")?:JSONArray()
                            for(index in 0 until offers.length()) {
                                val offer=offers.getJSONObject(index)
                                if(offer.optString("direction")!="computer-to-phone")continue
                                val all=graph.backup.identifiedMedia();val scope=offer.optString("scope","all")
                                val selected=try{select(scope,all)}catch(_:Exception){json(peer,"POST","/v1/offers/${offer.getString("id")}/reject",JSONObject());continue}
                                incomingSession=peer;incoming.value=IncomingComputerOffer(offer.getString("id"),scope,selected.size,peer.target.host)
                                break
                            }
                        }
                    }
                } catch(error:CancellationException){throw error}
                catch(_:Exception){cached=null}
                delay(5000)
            }
        }
    }
    fun rejectIncoming() {
        val offer=incoming.value?:return;val peer=incomingSession
        incoming.value=null;incomingSession=null
        if(peer!=null)graph.scope.launch{runCatching{json(peer,"POST","/v1/offers/${offer.id}/reject",JSONObject())}}
    }
    fun confirmIncoming() {
        val offer=incoming.value?:return;val peer=incomingSession?:return
        incoming.value=null;incomingSession=null
        begin(peer.target,offer,peer)
    }
    fun send(target:ComputerTarget)=begin(target,null,null)
    private fun begin(target:ComputerTarget,incomingOffer:IncomingComputerOffer?,existing:Session?) {
        if(progress.value.busy)return
        progress.value=LinkProgress(true,"正在连接电脑…")
        job=graph.scope.launch {
            var offer="";var peer:Session?=null
            val temporary=File(graph.app.cacheDir,"computer-send/${java.util.UUID.randomUUID()}").apply{mkdirs()}
            try {
                graph.startup.await()
                peer=existing?:establish(target);hello(peer)
                val all=graph.backup.identifiedMedia()
                val settings=graph.preferences.get()
                val assets=if(incomingOffer!=null)select(incomingOffer.scope,all) else logical(all).filter{settings.includes(it)}
                if(assets.isEmpty())throw EngineFailure("NO_MEDIA")
                if(incomingOffer!=null) {
                    if(saved()?.fingerprint!=peer.target.fingerprint)throw EngineFailure("LIVELINK_PIN")
                    offer=incomingOffer.id
                    json(peer,"POST","/v1/offers/$offer/accept",JSONObject())
                } else {
                    offer=json(peer,"POST","/v1/offers",JSONObject().put("direction","phone-to-computer").put("scope","library").put("count",assets.size)).getString("id")
                    progress.value=progress.value.copy(status="正在等待电脑确认…")
                    var accepted=false
                    for(i in 0 until 120) {
                        ensureActive();val response=json(peer,"GET","/v1/offers")
                        if(response.optBoolean("upload")){accepted=true;break}
                        val offers=response.optJSONArray("offers")?:JSONArray()
                        if((0 until offers.length()).none{offers.getJSONObject(it).optString("id")==offer})break
                        delay(1000)
                    }
                    if(!accepted)throw EngineFailure("RECEIVER_NOT_CONFIRMED")
                }
                for((index,media) in assets.withIndex()) {
                    ensureActive();progress.value=progress.value.copy(status="正在发送 ${index+1}/${assets.size}",fraction=index.toFloat()/assets.size)
                    val members=listOfNotNull(media,if(media.video)null else LivePhotoPairing.findPair(media,all))
                    val prepared=members.map{prepare(it,media.id,temporary)}
                    try {
                        val manifest=json(peer,"POST","/v1/manifest",JSONObject().put("model",android.os.Build.MODEL).put("items",JSONArray(prepared.map{it.item})))
                        val have=manifest.optJSONArray("have")?:JSONArray();val need=manifest.optJSONArray("need")?:JSONArray()
                        var sent=false
                        for(resource in prepared) {
                            val id=resource.item.getString("id")
                            if((0 until have.length()).any{have.getString(it)==id})continue
                            require((0 until need.length()).any{need.getString(it)==id})
                            upload(peer,resource);sent=true
                        }
                        progress.value=if(sent)progress.value.copy(sent=progress.value.sent+1) else progress.value.copy(existing=progress.value.existing+1)
                    } finally {prepared.forEach{it.file.delete()}}
                }
                json(peer,"POST","/v1/offers/$offer/finish",JSONObject().put("success",true))
                progress.value=progress.value.copy(status="发送完成",fraction=1f)
            } catch(error:CancellationException){progress.value=progress.value.copy(status="发送已停止");throw error}
            catch(_:Exception){progress.value=progress.value.copy(status="发送失败，请检查地址、配对信息、权限和网络后重试",failed=progress.value.failed+1)}
            finally {
                val connection=peer
                if(offer.isNotBlank()&&progress.value.fraction<1f&&connection!=null)withContext(NonCancellable){runCatching{json(connection,"POST","/v1/offers/$offer/finish",JSONObject().put("success",false))}}
                temporary.listFiles()?.forEach{it.delete()};temporary.delete();progress.value=progress.value.copy(busy=false)
                if(graph.visible.get())startPresence()
            }
        }
    }
    private suspend fun prepare(media:MediaEntry,logicalID:String,directory:File):Prepared {
        currentCoroutineContext().ensureActive()
        val before=graph.media.current(media)?:throw EngineFailure("FILE_ACCESS")
        val file=File(directory,java.util.UUID.randomUUID().toString())
        val sha=MessageDigest.getInstance("SHA-256")
        graph.app.contentResolver.openInputStream(android.net.Uri.parse(media.id))!!.use{input->file.outputStream().use{output->
            val buffer=ByteArray(1024*1024)
            while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;sha.update(buffer,0,count);output.write(buffer,0,count)}
        }}
        if(graph.media.current(media)!=before)throw EngineFailure("SOURCE_CHANGED")
        val size=file.length();require(size>0)
        val role=if(media.video)"video" else "still"
        val item=JSONObject().put("id",LinkWire.hash((media.id+"\n"+role).toByteArray()).take(32)).put("name",media.name).put("size",size)
            .put("created",(media.captured/1000).coerceAtLeast(0)).put("sha256",LinkWire.hex(sha.digest())).put("pairId",LinkWire.hash(logicalID.toByteArray()).take(32)).put("role",role)
        return Prepared(file,item)
    }
    private suspend fun upload(peer:Session,resource:Prepared) {
        val item=resource.item;val size=item.getLong("size");val id=item.getString("id")
        var offset=0L;var stalls=0;var retries=0;var restarts=0
        RandomAccessFile(resource.file,"r").use{source->while(offset<size){
            currentCoroutineContext().ensureActive();source.seek(offset)
            val bytes=ByteArray(minOf(4L*1024*1024,size-offset).toInt());source.readFully(bytes)
            try {
                val step=request(peer.target,"PUT","/v1/blobs/$id",bytes,peer.token,mapOf("X-File-Name" to URLEncoder.encode(item.getString("name"),"UTF-8").replace("+","%20"),
                    "X-Size" to size.toString(),"X-Offset" to offset.toString(),"X-Created" to item.getLong("created").toString(),"X-Sha256" to item.getString("sha256")))
                if(step.first==400&&step.second.optString("error")=="hash"&&restarts++<2){offset=0;continue}
                if(step.first==500&&step.second.optString("error")=="import"&&retries++<3){delay(400);continue}
                if(step.first !in setOf(200,409))throw EngineFailure("LIVELINK_REJECTED")
                if(step.second.optBoolean("complete"))return
                val received=step.second.optLong("received",-1);require(received in 0..size)
                stalls=if(received<=offset)stalls+1 else 0
                if(stalls>3)throw EngineFailure("LIVELINK_REJECTED")
                offset=received;retries=0
            } catch(error:java.io.IOException){if(retries++>=3)throw error;delay(400)}
        }}
    }
}
