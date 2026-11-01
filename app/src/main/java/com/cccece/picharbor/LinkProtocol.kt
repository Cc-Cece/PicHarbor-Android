package com.cccece.picharbor

import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LinkCancelled:Exception("cancelled")
class LinkRejected(val status:Int,val error:String,message:String):Exception(message)

data class LinkTarget(val host:String,val port:Int,val pin:String,val code:String,val name:String,val alts:List<String> = emptyList())
data class LinkEndpoint(val host:String,val port:Int,val fingerprint:String,val name:String,val alts:List<String>)
data class DiscoveredComputer(val name:String,val host:String,val port:Int,val fingerprint:String,val seenAt:Long,val hosts:List<String> = emptyList())

object LinkProtocol {
    const val GROUP="239.255.77.77"
    const val PORT=47877
    val ID=Regex("^[A-Za-z0-9._-]{1,80}$")
    private val HEX64=Regex("^[0-9a-f]{64}$")
    private val HEX16=Regex("^[0-9a-f]{16}$")
    private val CODE=Regex("^\\d{6}$")

    fun sha256Hex(data:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(data).toHex()

    /** 64 hex must match; 16 hex is the prefix a person types from the computer. */
    fun pinMatches(actual:String,expected:String):Boolean {
        val full=actual.trim().lowercase()
        val pin=expected.trim().lowercase()
        if(!HEX64.matches(full))return false
        return when {
            HEX64.matches(pin)->full==pin
            HEX16.matches(pin)->full.startsWith(pin)
            else->false
        }
    }

    fun pairMac(code:String,deviceId:String,nonce:String,fingerprint:String):String {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.trim().toByteArray(Charsets.UTF_8),"HmacSHA256"))
        val message="picharbor-link-v1\npair\n$deviceId\n$nonce\n$fingerprint"
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun sessionMessage(nonce:String,fingerprint:String):ByteArray=
        "picharbor-link-v1\nsession\n$nonce\n$fingerprint".toByteArray(Charsets.UTF_8)

    fun nonce():String {
        val bytes=ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    /** Uncompressed P-256 point: 0x04 || X(32) || Y(32). Not SPKI. */
    fun publicPoint(key:ECPublicKey):ByteArray=byteArrayOf(0x04)+unsigned32(key.w.affineX)+unsigned32(key.w.affineY)

    fun publicKeyBase64(key:ECPublicKey):String=Base64.getEncoder().encodeToString(publicPoint(key))

    fun toP1363(signature:ByteArray):ByteArray {
        if(signature.firstOrNull()==0x30.toByte()) {
            try { return derToP1363(signature) } catch(_:Exception) { if(signature.size==64)return signature }
        }
        if(signature.size==64)return signature
        return derToP1363(signature)
    }

    fun derToP1363(der:ByteArray):ByteArray {
        if(der.isEmpty()||der[0]!=0x30.toByte())throw IllegalArgumentException("der")
        var index=skipLength(der,1)
        val r=readInteger(der,index)
        index=r.third
        val s=readInteger(der,index)
        if(s.third!=der.size)throw IllegalArgumentException("der tail")
        return r.second+s.second
    }

    fun p1363ToDer(raw:ByteArray):ByteArray {
        if(raw.size!=64)throw IllegalArgumentException("p1363")
        val body=integerDer(raw.copyOfRange(0,32))+integerDer(raw.copyOfRange(32,64))
        if(body.size>127)throw IllegalArgumentException("der")
        return byteArrayOf(0x30,body.size.toByte())+body
    }

    /** Content URIs are not blob ids. This is 32 hex characters from SHA-256(media id). */
    fun blobId(mediaId:String):String {
        val id=sha256Hex(mediaId.toByteArray(Charsets.UTF_8)).take(32)
        check(ID.matches(id))
        return id
    }

    /** A motion photo is still one original file on the computer link. */
    fun originalCount(sample:ByteArray):Int=if(sample.size>=0)1 else 0

    fun parseQr(text:String):LinkTarget? {
        val raw=text.trim()
        val prefix="picharbor://link?"
        if(!raw.startsWith(prefix))return null
        val map=LinkedHashMap<String,String>()
        for(part in raw.substring(prefix.length).split('&')) {
            if(part.isEmpty())continue
            val cut=part.indexOf('=')
            val key=URLDecoder.decode(if(cut<0)part else part.substring(0,cut),Charsets.UTF_8)
            val value=URLDecoder.decode(if(cut<0)"" else part.substring(cut+1),Charsets.UTF_8)
            map[key]=value
        }
        if(map["v"]!="1")return null
        val host=map["host"]?.trim().orEmpty()
        val port=map["port"]?.toIntOrNull()?:return null
        val fingerprint=map["fp"]?.trim()?.lowercase().orEmpty()
        val code=map["code"]?.trim().orEmpty()
        if(!validHost(host)||port !in 1..65535||!HEX64.matches(fingerprint)||!CODE.matches(code))return null
        val alts=map["alts"].orEmpty().split(',').map{it.trim()}.filter{validHost(it)&&it!=host&&it!="127.0.0.1"}
        return LinkTarget(host,port,fingerprint,code,"",alts)
    }

    fun manual(host:String,portText:String,pin:String,code:String):LinkTarget? {
        val cleanHost=host.trim()
        val port=portText.trim().toIntOrNull()?:return null
        val prefix=pin.trim().lowercase()
        val digits=code.trim()
        if(!validHost(cleanHost)||port !in 1..65535||!HEX16.matches(prefix)||!CODE.matches(digits))return null
        return LinkTarget(cleanHost,port,prefix,digits,"")
    }

    fun parseBeacon(bytes:ByteArray):DiscoveredComputer? {
        val text=bytes.toString(Charsets.UTF_8).trim{it<=' '||it=='\u0000'}
        if(!text.startsWith("{")||!text.endsWith("}"))return null
        if(jsonInt(text,"v")!=1)return null
        val host=jsonString(text,"host")?.trim().orEmpty()
        val port=jsonInt(text,"port")?:return null
        val fingerprint=jsonString(text,"fp")?.trim()?.lowercase().orEmpty()
        val name=jsonString(text,"name")?.trim().orEmpty().ifEmpty{host}
        if(!validHost(host)||port !in 1..65535||!HEX64.matches(fingerprint))return null
        val hosts=(listOf(host)+jsonStringArray(text,"hosts")).map{it.trim()}.filter{validHost(it)&&it!="127.0.0.1"}.distinct()
        return DiscoveredComputer(name,hosts.firstOrNull()?:host,port,fingerprint,0,if(hosts.isEmpty())listOf(host) else hosts)
    }

    /** Same /24 as the phone first. The computer is still one device. */
    fun orderAddresses(hosts:List<String>,phone:String?):List<String> {
        val parts=phone?.trim()?.split('.')
        val prefix=if(parts!=null&&parts.size==4)parts.take(3).joinToString(".")+"." else ""
        return hosts.map{it.trim()}.filter{it.isNotEmpty()}.distinct().sortedWith(compareBy({if(prefix.isNotEmpty()&&it.startsWith(prefix))0 else 1},{it}))
    }

    /** Single-value serial rules shared with the computer. A PH- code is included; android: is not. */
    fun usableArchive(value:String?):Boolean {
        val trimmed=value?.trim().orEmpty()
        if(trimmed.length !in 4..64||trimmed.contains('&')||trimmed.lowercase().startsWith("android:"))return false
        if(trimmed.all{it==trimmed[0]})return false
        return trimmed.lowercase() !in PLACEHOLDERS
    }

    private val PLACEHOLDERS=setOf("0123456789","0123456789abcdef","1234567890","0000000000000000","ffffffffffffffff","unknown","none","null","n/a","na","default")

    private fun validHost(host:String)=host.isNotEmpty()&&host.length<=253&&host.none{it.isWhitespace()||it=='/'||it=='\\'}

    private fun jsonString(json:String,key:String):String? {
        val match=Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json)?.groupValues?.get(1)?:return null
        return match.replace("\\\"","\"").replace("\\\\","\\").replace("\\n","\n").replace("\\r","\r").replace("\\t","\t")
    }

    private fun jsonInt(json:String,key:String):Int?=Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun jsonStringArray(json:String,key:String):List<String> {
        val body=Regex(""""${Regex.escape(key)}"\s*:\s*\[(.*?)]""").find(json)?.groupValues?.get(1)?:return emptyList()
        return Regex(""""((?:\\.|[^"\\])*)"""").findAll(body).map{it.groupValues[1]}.toList()
    }

    private fun unsigned32(value:BigInteger):ByteArray {
        val raw=value.toByteArray()
        var start=0
        while(start<raw.size-1&&raw[start]==0.toByte())start++
        val sliced=raw.copyOfRange(start,raw.size)
        if(sliced.size>32)throw IllegalArgumentException("coordinate")
        return ByteArray(32-sliced.size)+sliced
    }

    private fun readInteger(der:ByteArray,index:Int):Triple<Int,ByteArray,Int> {
        if(index>=der.size||der[index]!=0x02.toByte())throw IllegalArgumentException("int")
        val (length,next)=readLength(der,index+1)
        if(length<1||next+length>der.size)throw IllegalArgumentException("int len")
        val raw=der.copyOfRange(next,next+length)
        return Triple(length,unsigned32(BigInteger(1,raw)),next+length)
    }

    private fun skipLength(der:ByteArray,index:Int)=readLength(der,index).second

    private fun readLength(der:ByteArray,index:Int):Pair<Int,Int> {
        if(index>=der.size)throw IllegalArgumentException("len")
        val first=der[index].toInt() and 0xff
        if(first<0x80)return first to index+1
        val count=first and 0x7f
        if(count==0||count>2||index+1+count>der.size)throw IllegalArgumentException("len")
        var value=0
        for(n in 0 until count)value=(value shl 8) or (der[index+1+n].toInt() and 0xff)
        return value to index+1+count
    }

    private fun integerDer(fixed:ByteArray):ByteArray {
        var start=0
        while(start<fixed.size-1&&fixed[start]==0.toByte())start++
        var body=fixed.copyOfRange(start,fixed.size)
        if(body[0].toInt() and 0x80!=0)body=byteArrayOf(0)+body
        return byteArrayOf(0x02,body.size.toByte())+body
    }
}

fun ByteArray.toHex():String=joinToString(""){"%02x".format(it)}
