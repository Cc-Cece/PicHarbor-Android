package com.cccece.picharbor

import android.content.Context
import androidx.annotation.Keep
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class EngineFailure(val code:String,val metrics:JSONObject?=null):Exception(code)
@Keep
class EngineCallback(private val cancelled:()->Boolean={false},private val readGate:java.util.concurrent.Semaphore?=null,private val event:(JSONObject)->Unit={}) {
    @Keep fun isCancelled()=cancelled()
    @Keep fun onEvent(value:String){event(JSONObject(value))}
    @Keep fun acquireRead():Boolean {
        val gate=readGate?:return !cancelled()
        while(!cancelled())if(gate.tryAcquire(250,java.util.concurrent.TimeUnit.MILLISECONDS))return true
        return false
    }
    @Keep fun releaseRead(){readGate?.release()}
}
class Engine(private val context:Context) {
    private val source=SourceBridge(context)
    suspend fun call(action:String, value:JSONObject=JSONObject(), callback:EngineCallback?=null):JSONObject=withContext(Dispatchers.IO) {
        if(action !in setOf("connect","logout")&&!value.has("_account"))value.put("_account",Preferences(context).get().account)
        val result=try {
            JSONObject(Python.getInstance().getModule("picharbor_engine").callAttr("dispatch",action,value.toString(),source,callback).toString())
        } catch (_:Exception){throw EngineFailure("ENGINE_ERROR")}
        if(!result.optBoolean("ok"))throw EngineFailure(result.optString("error","ENGINE_ERROR"),result.optJSONObject("_metrics"))
        result
    }
    suspend fun connect(credential:String,timeout:Int)=call("connect",JSONObject().put("credential",credential).put("timeout",timeout))
}

object Messages {
    fun error(code:String)=when(code) {
        "LOGIN_REQUIRED"->"账号需要重新登录"
        "ENCRYPTED_TOKEN"->"Google 返回了暂不支持的登录凭据，请重新登录"
        "DEVICE_VERIFICATION"->"Google 需要额外验证，请重新登录"
        "NETWORK_ERROR","RATE_LIMITED"->"网络暂时不可用，请稍后重试"
        "FILE_ACCESS"->"无法读取文件，请检查访问权限"
        "SOURCE_CHANGED"->"文件内容已变化，请重新备份"
        "REMOTE_MISSING"->"暂未在当前账号找到对应备份"
        "REMOTE_TRASHED"->"云端内容位于回收站，已保留手机原件"
        "ACCOUNT_CHANGED"->"账号已切换，请在当前账号重新操作"
        "CLEANUP_RUNNING"->"检查尚未完成，请稍后清理"
        "CLOUD_TRASH_UNKNOWN"->"暂时无法核实云端回收站状态，手机原件已保留"
        "CLOUD_QUALITY_UNKNOWN"->"云端未返回原始画质信息，手机原件已保留"
        "CLOUD_PROCESSING"->"云端仍在处理文件，请稍后重新检查"
        "CLOUD_ITEM_MISSING"->"最新云端列表尚未找到对应内容，请稍后重试"
        "ORIGINAL_NETWORK_ERROR"->"检查云端原件时连接失败，请检查网络后重试"
        "CLOUD_STATE_UNKNOWN"->"云端信息暂不完整，已保留手机原件"
        "ORIGINAL_UNCONFIRMED","ORIGINAL_UNAVAILABLE"->"暂时无法确认云端原件，已保留手机文件"
        "FILENAME_ONLY"->"发现同名文件，尚未确认内容相同"
        "CANCELLED"->"操作已暂停"
        "STORAGE_FULL"->"手机可用空间不足"
        "ACCESS_LOST"->"文件夹访问权限已失效"
        else->"暂时未能完成，请稍后重试"
    }
}
