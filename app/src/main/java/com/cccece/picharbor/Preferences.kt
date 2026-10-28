package com.cccece.picharbor

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.*
import org.json.JSONObject
import org.json.JSONArray

private val Context.harborPreferences by preferencesDataStore("picharbor_settings")
data class HarborSettings(
    val onboarded: Boolean = false, val auto: Boolean = false, val wifi: Boolean = true,
    val charging: Boolean = false, val videos: Boolean = true, val scheduled: Boolean = false,
    val time: String = "02:00", val restart: Boolean = true, val quality: String = "original",
    val concurrency: Int = 2, val timeout: Int = 60, val retries: Int = 3, val sameName: Boolean = false,
    val folders: Set<String> = setOf("@camera"), val trees: Set<String> = emptySet(),
    val theme: String = "system", val palette: String = "system", val albumMode: String = "folder",
    val albumValue: String = "", val mobile: String = "none", val account: String = "",val bootSession:Int=-1,val cleanupConcurrency:Int=2,
    val cleanupReaders:Int=2,val cleanupRetries:Int=1,val cleanupAdaptive:Boolean=true,
    val cleanupCloudState:Boolean=true,val cleanupOriginalAccess:Boolean=true,val cleanupConsent:String="full",
    val allFolders:Boolean=false
) {
    val cleanupPolicy get()=CleanupPolicy(cleanupCloudState,cleanupOriginalAccess).let{if(it.complete||cleanupConsent==it.id)it else CleanupPolicy()}
    fun includesFolder(media: MediaEntry) = allFolders || folders.contains(media.folderKey) ||
        (folders.contains("@camera") && media.source == "media" && media.folderName.equals("Camera", true))
    fun includes(media: MediaEntry) = includesFolder(media) && (videos || !media.video)
}

class Preferences(private val context: Context) {
    private fun from(p: androidx.datastore.preferences.core.Preferences) = HarborSettings(
        onboarded=p[booleanPreferencesKey("onboarded")]?:false, auto=p[booleanPreferencesKey("auto")]?:false,
        wifi=p[booleanPreferencesKey("wifi")]?:true, charging=p[booleanPreferencesKey("charging")]?:false,
        videos=p[booleanPreferencesKey("videos")]?:true, scheduled=p[booleanPreferencesKey("scheduled")]?:false,
        time=p[stringPreferencesKey("time")]?:"02:00", restart=p[booleanPreferencesKey("restart")]?:true,
        quality=p[stringPreferencesKey("quality")]?:"original", concurrency=(p[intPreferencesKey("concurrency")]?:2).coerceIn(1,3),
        timeout=(p[intPreferencesKey("timeout")]?:60).coerceIn(30,120), retries=(p[intPreferencesKey("retries")]?:3).coerceIn(0,3),
        sameName=p[booleanPreferencesKey("sameName")]?:false, folders=p[stringSetPreferencesKey("folders")]?:setOf("@camera"),
        trees=p[stringSetPreferencesKey("trees")]?:emptySet(), theme=p[stringPreferencesKey("theme")]?:"system",
        palette=p[stringPreferencesKey("palette")]?:"system", albumMode=p[stringPreferencesKey("albumMode")]?:"folder",
        albumValue=p[stringPreferencesKey("albumValue")]?:"", mobile=p[stringPreferencesKey("mobile")]?:"none",
        account=p[stringPreferencesKey("account")]?:"",bootSession=p[intPreferencesKey("bootSession")]?:-1,
        cleanupConcurrency=(p[intPreferencesKey("cleanupConcurrency")]?:2).coerceIn(1,20),
        cleanupReaders=(p[intPreferencesKey("cleanupReaders")]?:2).coerceIn(1,4),
        cleanupRetries=(p[intPreferencesKey("cleanupRetries")]?:1).coerceIn(0,3),
        cleanupAdaptive=p[booleanPreferencesKey("cleanupAdaptive")]?:true,
        cleanupCloudState=p[booleanPreferencesKey("cleanupCloudState")]?:true,
        cleanupOriginalAccess=p[booleanPreferencesKey("cleanupOriginalAccess")]?:true,
        cleanupConsent=p[stringPreferencesKey("cleanupConsent")]?:"full",
        allFolders=p[booleanPreferencesKey("allFolders")]?:false
    )
    val flow = context.harborPreferences.data.map(::from)
    suspend fun get() = flow.first()
    suspend fun boolean(key: String, value: Boolean) { context.harborPreferences.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun string(key: String, value: String) { context.harborPreferences.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun integer(key: String, value: Int) { context.harborPreferences.edit { it[intPreferencesKey(key)] = value } }
    suspend fun folders(value: Set<String>) { context.harborPreferences.edit { it[stringSetPreferencesKey("folders")] = value } }
    suspend fun trees(value: Set<String>) { context.harborPreferences.edit { it[stringSetPreferencesKey("trees")] = value } }
    suspend fun mobile(value: String) { context.harborPreferences.edit { it[stringPreferencesKey("mobile")]=value; it[booleanPreferencesKey("wifi")]=value=="none" } }
    internal suspend fun cleanupPolicy(policy:CleanupPolicy) {context.harborPreferences.edit {
        it[booleanPreferencesKey("cleanupCloudState")]=policy.cloudState
        it[booleanPreferencesKey("cleanupOriginalAccess")]=policy.originalAccess
        it[stringPreferencesKey("cleanupConsent")]=policy.id
    }}
    suspend fun activateAccount(account:String) {
        context.harborPreferences.edit { p ->
            val booleans=mapOf("onboarded" to false,"auto" to false,"wifi" to true,"charging" to false,"videos" to true,"scheduled" to false,"restart" to true,"sameName" to false,"allFolders" to false,"cleanupAdaptive" to true,"cleanupCloudState" to true,"cleanupOriginalAccess" to true)
            val strings=mapOf("time" to "02:00","quality" to "original","albumMode" to "folder","albumValue" to "","mobile" to "none","cleanupConsent" to "full")
            val integers=mapOf("concurrency" to 2,"timeout" to 60,"retries" to 3,"cleanupConcurrency" to 2,"cleanupReaders" to 2,"cleanupRetries" to 1)
            val old=p[stringPreferencesKey("account")].orEmpty()
            if(old==account)return@edit
            if(old.isNotBlank()) {
                val data=JSONObject()
                booleans.forEach{(k,d)->data.put(k,p[booleanPreferencesKey(k)]?:d)}
                strings.forEach{(k,d)->data.put(k,p[stringPreferencesKey(k)]?:d)}
                integers.forEach{(k,d)->data.put(k,p[intPreferencesKey(k)]?:d)}
                data.put("folders",JSONArray((p[stringSetPreferencesKey("folders")]?:setOf("@camera")).toList()))
                p[stringPreferencesKey("profile:$old")]=data.toString()
            }
            val data=runCatching{JSONObject(p[stringPreferencesKey("profile:$account")]?:"{}")}.getOrDefault(JSONObject())
            booleans.forEach{(k,d)->p[booleanPreferencesKey(k)]=data.optBoolean(k,d)}
            strings.forEach{(k,d)->p[stringPreferencesKey(k)]=data.optString(k,d)}
            integers.forEach{(k,d)->p[intPreferencesKey(k)]=data.optInt(k,d)}
            val folders=data.optJSONArray("folders")
            p[stringSetPreferencesKey("folders")]=if(folders==null)setOf("@camera") else (0 until folders.length()).map{folders.getString(it)}.toSet()
            p[stringPreferencesKey("account")]=account
        }
    }
    suspend fun forgetAccount(account:String){context.harborPreferences.edit{it.remove(stringPreferencesKey("profile:$account"))}}
}

object SchedulePolicy {
    fun nextDelay(time: String, now: ZonedDateTime = ZonedDateTime.now()): Long {
        val local = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(2,0))
        var target = now.toLocalDate().atTime(local).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now,target).toMillis().coerceAtLeast(1000)
    }
}
