package com.cccece.picharbor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import coil.imageLoader

data class SavedAccount(val email:String,val needsLogin:Boolean=false,val displayName:String="",val avatarPath:String="",val profileCheckedAt:Long=0)

/** One AES-GCM key per account. Removing an account never removes other keys. */
class CredentialStore(context:Context) {
    private val appContext=context.applicationContext
    private val avatars=java.io.File(context.filesDir,"account-avatars")
    private val prefs=context.getSharedPreferences("accounts_v2",Context.MODE_PRIVATE)
    private val legacy=context.getSharedPreferences("credential",Context.MODE_PRIVATE)
    val accounts=MutableStateFlow(listOf<SavedAccount>())
    init { publish() }
    private fun normal(account:String)=account.trim().lowercase(Locale.ROOT)
    private fun slot(account:String)=MessageDigest.getInstance("SHA-256").digest(normal(account).toByteArray()).joinToString(""){"%02x".format(it)}
    private fun alias(account:String)="picharbor.account.${slot(account)}"
    private fun key(name:String,create:Boolean=false):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(name,null) as? SecretKey)?.let{return it}
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(name,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun publish(){accounts.value=prefs.getStringSet("accounts",emptySet()).orEmpty().sorted().map{
        val id=slot(it);val avatar=java.io.File(avatars,"$id.img")
        SavedAccount(it,prefs.getBoolean("expired_$id",false),prefs.getString("name_$id","").orEmpty(),if(avatar.isFile)avatar.absolutePath else "",prefs.getLong("profile_at_$id",0))
    }}
    @Synchronized fun saveProfile(account:String,subject:String,name:String,image:ByteArray?) {
        val email=normal(account);val id=slot(email)
        if(email !in prefs.getStringSet("accounts",emptySet()).orEmpty())return
        require(subject.matches(Regex("[A-Za-z0-9_-]{1,255}")))
        val previous=prefs.getString("subject_$id",null)
        if(previous!=null&&previous!=subject)return
        val avatar=android.util.AtomicFile(java.io.File(avatars,"$id.img"))
        if(image!=null&&image.size in 1..512*1024) {
            val size=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeByteArray(image,0,image.size,size)
            if(size.outWidth in 1..4096&&size.outHeight in 1..4096) {
                avatars.mkdirs()
                val output=avatar.startWrite()
                try{output.write(image);avatar.finishWrite(output)}catch(error:Exception){avatar.failWrite(output);throw error}
            }
        }
        val safeName=name.trim().takeIf{it.length<=100&&it.none{c->c.code<32}}.orEmpty()
        check(prefs.edit().putString("subject_$id",subject).putString("name_$id",safeName).putLong("profile_at_$id",System.currentTimeMillis()).commit())
        publish()
    }
    @Synchronized fun save(account:String,value:String) {
        val email=normal(account);require(email.contains('@'))
        val id=slot(email)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key(alias(email),true));updateAAD(email.toByteArray())}
        check(prefs.edit().putString("iv_$id",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("data_$id",Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
            .putBoolean("expired_$id",false).putStringSet("accounts",prefs.getStringSet("accounts",emptySet()).orEmpty()+email).commit())
        publish()
    }
    @Synchronized fun load(account:String):String? {
        val email=normal(account);val id=slot(email)
        val data=prefs.getString("data_$id",null)?:return null
        val iv=prefs.getString("iv_$id",null)?:return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(alias(email)),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));updateAAD(email.toByteArray())
        }
        return cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    @Synchronized fun needsLogin(account:String,value:Boolean){prefs.edit().putBoolean("expired_${slot(account)}",value).apply();publish()}
    @Synchronized fun remove(account:String) {
        val email=normal(account);val id=slot(email)
        check(prefs.edit().remove("iv_$id").remove("data_$id").remove("expired_$id").remove("subject_$id").remove("name_$id").remove("profile_at_$id")
            .putStringSet("accounts",prefs.getStringSet("accounts",emptySet()).orEmpty()-email).commit())
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias(alias(email)))deleteEntry(alias(email))}
        android.util.AtomicFile(java.io.File(avatars,"$id.img")).delete()
        appContext.imageLoader.memoryCache?.let{cache->cache.keys.filter{it.key.startsWith("avatar:$email:")}.forEach{cache.remove(it)}}
        publish()
    }
    /** Upgrade 1.0.0/1.0.1 in place; delete the legacy copy only after verified storage. */
    @Synchronized fun migrateLegacy(account:String) {
        if(account.isBlank())return
        val data=legacy.getString("data",null)?:return
        if(load(account)==null) {
            val iv=legacy.getString("iv",null)?:return
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key("gmgc.credential.v1"),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))}
            val credential=cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)).toString(Charsets.UTF_8)
            save(account,credential);check(load(account)==credential)
        }
        check(legacy.edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias("gmgc.credential.v1"))deleteEntry("gmgc.credential.v1")}
    }
}
