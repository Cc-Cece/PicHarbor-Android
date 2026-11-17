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
    val theme: String = "system", val palette: String = "system", val albumMode: String = "none",
    val albumValue: String = "", val mobile: String = "none", val account: String = "",val bootSession:Int=-1,
    val allFolders:Boolean=true,
    /** Live Photo pairing: "original" (photo+video, joint submit) is the
     * default; "motion" is the older single-file Motion Photo default. An
     * upgrade never overwrites an existing choice (a missing key just reads
     * as "original"); the per-account profile only restores a value saved
     * for that account. */
    val livePhotoPairing:String="original",
    val editPolicy:String="both", val strictConfirmation:Boolean=false,
    val pipelineMode:String="automatic",val pipelineLimits:String="",val executionPolicy:String="",
    val archiveEnabled:Boolean=true, val archiveDays:Int=14, val language:String="system",
    val activitySource:String="all",val activityStatus:String="all",val activityGrouping:String="batch",val archiveSource:String="all",
    /** Explicit proxy, off by default and device-level (not per account, so
     * it is deliberately NOT part of the per-account profile maps below).
     * The password is NOT stored here; see CredentialStore proxy methods. */
    val proxyEnabled:Boolean=false, val proxyScheme:String="http", val proxyHost:String="",
    val proxyPort:Int=0, val proxyUser:String=""
) {
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
        palette=p[stringPreferencesKey("palette")]?:"system", albumMode=p[stringPreferencesKey("albumMode")]?:"none",
        albumValue=p[stringPreferencesKey("albumValue")]?:"", mobile=p[stringPreferencesKey("mobile")]?:"none",
        account=p[stringPreferencesKey("account")]?:"",bootSession=p[intPreferencesKey("bootSession")]?:-1,
        allFolders=p[booleanPreferencesKey("allFolders")]?:true,
        livePhotoPairing=p[stringPreferencesKey("livePhotoPairing")]?:"original",
        editPolicy=p[stringPreferencesKey("editPolicy")]?:"both",
        strictConfirmation=p[booleanPreferencesKey("strictConfirmation")]?:false,
        pipelineMode=p[stringPreferencesKey("pipelineMode")]?:"automatic",
        pipelineLimits=p[stringPreferencesKey("pipelineLimits")]?:"",
        executionPolicy=p[stringPreferencesKey("executionPolicy")]?:"",
        archiveEnabled=p[booleanPreferencesKey("archiveEnabled")]?:true,
        archiveDays=(p[intPreferencesKey("archiveDays")]?:14).coerceIn(1,90),
        language=p[stringPreferencesKey("language")]?:"system",
        activitySource=p[stringPreferencesKey("activitySource")]?:"all",activityStatus=p[stringPreferencesKey("activityStatus")]?:"all",
        activityGrouping=p[stringPreferencesKey("activityGrouping")]?:"batch",archiveSource=p[stringPreferencesKey("archiveSource")]?:"all",
        proxyEnabled=p[booleanPreferencesKey("proxyEnabled")]?:false,
        proxyScheme=p[stringPreferencesKey("proxyScheme")]?:"http",
        proxyHost=p[stringPreferencesKey("proxyHost")]?:"",
        proxyPort=p[intPreferencesKey("proxyPort")]?:0,
        proxyUser=p[stringPreferencesKey("proxyUser")]?:""
    )
    val flow = context.harborPreferences.data.map(::from)
    suspend fun get() = flow.first()
    suspend fun boolean(key: String, value: Boolean) { context.harborPreferences.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun string(key: String, value: String) { context.harborPreferences.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun integer(key: String, value: Int) { context.harborPreferences.edit { it[intPreferencesKey(key)] = value } }
    suspend fun folders(value: Set<String>) { context.harborPreferences.edit { it[stringSetPreferencesKey("folders")] = value } }
    suspend fun trees(value: Set<String>) { context.harborPreferences.edit { it[stringSetPreferencesKey("trees")] = value } }
    suspend fun mobile(value: String) { context.harborPreferences.edit { it[stringPreferencesKey("mobile")]=value; it[booleanPreferencesKey("wifi")]=value=="none" } }
    suspend fun activateAccount(account:String) {
        context.harborPreferences.edit { p ->
            val booleans=mapOf("onboarded" to false,"auto" to false,"wifi" to true,"charging" to false,"videos" to true,"scheduled" to false,"restart" to true,"sameName" to false,"allFolders" to true,"strictConfirmation" to false)
            val strings=mapOf("time" to "02:00","quality" to "original","albumMode" to "none","albumValue" to "","mobile" to "none","livePhotoPairing" to "original","editPolicy" to "both","pipelineMode" to "automatic","pipelineLimits" to "","executionPolicy" to "")
            val integers=mapOf("concurrency" to 2,"timeout" to 60,"retries" to 3)
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
    suspend fun restoreBackupDefaults() {
        context.harborPreferences.edit { p ->
            listOf("auto", "onboarded", "charging", "scheduled", "sameName", "strictConfirmation").forEach { p[booleanPreferencesKey(it)]=false }
            listOf("wifi", "videos", "restart", "allFolders").forEach { p[booleanPreferencesKey(it)]=true }
            p[stringPreferencesKey("quality")]="original"
            p[stringPreferencesKey("livePhotoPairing")]="original"
            p[stringPreferencesKey("editPolicy")]="both"
            p[stringPreferencesKey("pipelineMode")]="automatic"
            p[stringPreferencesKey("pipelineLimits")]=""
            p[stringPreferencesKey("executionPolicy")]=""
            p[stringPreferencesKey("albumMode")]="none"
            p[stringPreferencesKey("albumValue")]=""
            p[stringPreferencesKey("mobile")]="none"
            p[stringPreferencesKey("time")]="02:00"
            p[intPreferencesKey("concurrency")]=2
            p[intPreferencesKey("timeout")]=60
            p[intPreferencesKey("retries")]=3
            p[stringSetPreferencesKey("folders")]=emptySet()
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
