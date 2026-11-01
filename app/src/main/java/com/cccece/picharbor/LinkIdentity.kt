package com.cccece.picharbor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Device id and the long-term P-256 key survive a Google account switch. */
class LinkIdentity(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("picharbor_link",Context.MODE_PRIVATE)
    private val alias="picharbor-link-p256"

    val deviceId:String get() {
        val saved=prefs.getString("device_id",null)
        if(saved!=null&&LinkProtocol.ID.matches(saved))return saved
        val created=UUID.randomUUID().toString()
        check(LinkProtocol.ID.matches(created))
        prefs.edit().putString("device_id",created).apply()
        return created
    }

    fun publicKey():String=LinkProtocol.publicKeyBase64(ensure().public as ECPublicKey)

    fun sign(message:ByteArray):String {
        val signature=Signature.getInstance("SHA256withECDSA")
        signature.initSign(ensure().private)
        signature.update(message)
        return java.util.Base64.getEncoder().encodeToString(LinkProtocol.toP1363(signature.sign()))
    }

    fun isPaired(fingerprint:String)=fingerprint.lowercase() in paired()

    fun remember(fingerprint:String) {
        val next=paired().toMutableSet()
        next+=fingerprint.lowercase()
        prefs.edit().putStringSet("paired",next).apply()
    }

    fun forget(fingerprint:String) {
        val next=paired().toMutableSet()
        next-=fingerprint.lowercase()
        prefs.edit().putStringSet("paired",next).apply()
    }

    fun archiveCode():String? = prefs.getString("archive_code",null)?.trim()?.takeIf{LinkProtocol.usableArchive(it)}

    fun storeArchive(code:String) {
        val trimmed=code.trim()
        if(!LinkProtocol.usableArchive(trimmed))return
        prefs.edit().putString("archive_code",trimmed).apply()
    }

    fun endpoint():LinkEndpoint? {
        val host=prefs.getString("endpoint_host",null)?.trim().orEmpty()
        val port=prefs.getInt("endpoint_port",0)
        val fingerprint=prefs.getString("endpoint_fp",null)?.trim()?.lowercase().orEmpty()
        val name=prefs.getString("endpoint_name",null).orEmpty()
        if(host.isEmpty()||port !in 1..65535||fingerprint.length!=64)return null
        val alts=prefs.getString("endpoint_alts",null).orEmpty().split(',').map{it.trim()}.filter{it.isNotEmpty()&&it!=host}
        return LinkEndpoint(host,port,fingerprint,name,alts)
    }

    fun saveEndpoint(host:String,port:Int,fingerprint:String,name:String,alts:List<String>) {
        val clean=fingerprint.trim().lowercase()
        if(host.isBlank()||port !in 1..65535||clean.length!=64)return
        prefs.edit()
            .putString("endpoint_host",host.trim())
            .putInt("endpoint_port",port)
            .putString("endpoint_fp",clean)
            .putString("endpoint_name",name)
            .putString("endpoint_alts",alts.filter{it.isNotBlank()&&it!=host.trim()}.distinct().joinToString(","))
            .apply()
    }

    private fun paired()=prefs.getStringSet("paired",emptySet())?.toSet()?:emptySet()

    private fun ensure():KeyPair=synchronized(this) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val existing=store.getCertificate(alias)?.publicKey
        val privateKey=if(existing!=null)store.getKey(alias,null) else null
        if(existing is ECPublicKey&&privateKey is PrivateKey)return KeyPair(existing,privateKey)
        if(store.containsAlias(alias))store.deleteEntry(alias)
        val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
        val spec=KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }
}
