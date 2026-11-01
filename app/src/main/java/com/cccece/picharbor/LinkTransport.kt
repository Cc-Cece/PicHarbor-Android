package com.cccece.picharbor

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LinkRequest(val method:String,val path:String,val headers:Map<String,String>,val body:ByteArray)
data class LinkHttp(val code:Int,val body:ByteArray,val fingerprint:String)

/** One HTTPS request per TLS connection. The certificate pin is the authentication. */
class LinkTransport {
    @Volatile private var active:Socket?=null
    private val gate=Any()

    fun close(){runCatching{active?.close()}}

    fun exchange(host:String,port:Int,pin:String,build:(String)->LinkRequest):LinkHttp = synchronized(gate) {
        val trust=PinTrust(pin)
        val context=SSLContext.getInstance("TLS")
        context.init(null,arrayOf<TrustManager>(trust),SecureRandom())
        val tcp=Socket()
        active=tcp
        try {
            tcp.connect(InetSocketAddress(host,port),10_000)
            tcp.soTimeout=30_000
            val ssl=context.socketFactory.createSocket(tcp,host,port,true) as SSLSocket
            active=ssl
            ssl.useClientMode=true
            val allowed=ssl.supportedProtocols.filter{it=="TLSv1.2"||it=="TLSv1.3"}
            if(allowed.isNotEmpty())ssl.enabledProtocols=allowed.toTypedArray()
            val parameters=ssl.sslParameters
            parameters.endpointIdentificationAlgorithm=null
            ssl.sslParameters=parameters
            ssl.startHandshake()
            val verifier=HostnameVerifier{_,session->
                val certificate=session.peerCertificates.firstOrNull() as? X509Certificate?:return@HostnameVerifier false
                LinkProtocol.pinMatches(LinkProtocol.sha256Hex(certificate.encoded),pin)
            }
            if(!verifier.verify(host,ssl.session)||!LinkProtocol.pinMatches(trust.fingerprint,pin))throw SSLHandshakeException("pin")
            val request=build(trust.fingerprint)
            writeRequest(ssl.outputStream,host,request)
            val (code,body)=readHttp(ssl.inputStream)
            LinkHttp(code,body,trust.fingerprint)
        } finally {
            runCatching{active?.close()}
            active=null
        }
    }

    private fun writeRequest(output:OutputStream,host:String,request:LinkRequest) {
        val head=StringBuilder()
        head.append(request.method).append(' ').append(request.path).append(" HTTP/1.1\r\n")
        head.append("Host: ").append(host).append("\r\n")
        head.append("Content-Length: ").append(request.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        for((name,value) in request.headers)head.append(name).append(": ").append(value).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.US_ASCII))
        if(request.body.isNotEmpty())output.write(request.body)
        output.flush()
    }

    private fun readHttp(input:InputStream):Pair<Int,ByteArray> {
        val raw=ByteArrayOutputStream()
        val window=ArrayDeque<Int>()
        while(true) {
            val value=input.read()
            if(value<0)throw java.io.IOException("closed")
            raw.write(value)
            window.addLast(value)
            if(window.size>4)window.removeFirst()
            if(window.size==4&&window.elementAt(0)==13&&window.elementAt(1)==10&&window.elementAt(2)==13&&window.elementAt(3)==10)break
            if(raw.size()>65536)throw java.io.IOException("header")
        }
        val text=raw.toString(Charsets.ISO_8859_1.name())
        val lines=text.substringBefore("\r\n\r\n").split("\r\n")
        val code=lines.first().split(' ').getOrNull(1)?.toIntOrNull()?:throw java.io.IOException("status")
        var length=-1
        for(line in lines.drop(1)) {
            val cut=line.indexOf(':')
            if(cut<0)continue
            if(line.substring(0,cut).trim().equals("Content-Length",true))length=line.substring(cut+1).trim().toIntOrNull()?:-1
        }
        if(length<0||length>1024*1024)throw java.io.IOException("length")
        val body=ByteArray(length)
        var filled=0
        while(filled<length) {
            val count=input.read(body,filled,length-filled)
            if(count<0)throw java.io.IOException("body")
            filled+=count
        }
        return code to body
    }

    private class PinTrust(private val pin:String):X509TrustManager {
        var fingerprint:String=""
        override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String)=throw CertificateException("client")
        override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
            val leaf=chain.firstOrNull()?:throw CertificateException("leaf")
            val actual=LinkProtocol.sha256Hex(leaf.encoded)
            if(!LinkProtocol.pinMatches(actual,pin))throw CertificateException("pin")
            fingerprint=actual
        }
        override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
    }

    companion object {
        fun pinFailure(error:Throwable):Boolean {
            var current:Throwable?=error
            while(current!=null) {
                if(current is CertificateException&&current.message=="pin")return true
                if(current.message=="pin")return true
                current=current.cause
            }
            return false
        }
    }
}
