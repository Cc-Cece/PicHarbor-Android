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
    private var pipeline:BackupPipeline.Lease?=null
    fun attachPipeline(value:BackupPipeline.Lease?){pipeline=value}
    private var diagnostic:DiagnosticContext?=null
    private var lastPhase=""
    private val cancellationSeen=java.util.concurrent.atomic.AtomicBoolean(false)
    fun attach(context:DiagnosticContext){diagnostic=context}
    @Keep fun isCancelled():Boolean {
        val result=cancelled()
        if(result&&cancellationSeen.compareAndSet(false,true))diagnostic?.let{Diagnostics.event("cancel.observed",it)}
        return result
    }
    @Keep fun onEvent(value:String){
        val parsed=JSONObject(value)
        val context=diagnostic
        val phase=parsed.optString("phase")
        when(phase) {
            "hashing"->pipeline?.enter("preparation")
            "checking","cloud_check"->pipeline?.enter("lookup")
            "uploading"->pipeline?.enter("upload")
            "finalizing","finalizing_album","uploaded"->pipeline?.enter("commit")
            "confirming"->pipeline?.enter("confirmation")
            "retrying","waiting_content"->pipeline?.close()
        }
        if(context!=null&&phase!=lastPhase){lastPhase=phase;Diagnostics.event("item.phase_changed",context,JSONObject().put("phase",phase))}
        val span=if(phase=="uploaded")context?.child() else null
        if(span!=null)Diagnostics.event("operation.started",span,JSONObject().put("operation","persist_uploaded_callback"))
        try {event(parsed)} finally {if(span!=null)Diagnostics.event("operation.finished",span,JSONObject().put("operation","persist_uploaded_callback"))}
    }
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
        val proxy=(context.applicationContext as PicHarborApp).graph.proxy
        val proxyToken=proxy.outcomeToken()
        if(action !in setOf("connect","logout")&&!value.has("_account"))value.put("_account",Preferences(context).get().account)
        val diagnostic=value.optJSONObject("_diagnostics")?.let{DiagnosticContext.parse(it)}?:DiagnosticContext()
        value.put("_diagnostics",diagnostic.json())
        val managed=diagnostic.item.isNotBlank()&&action in setOf("fingerprint","lookup","process","process_pair","sync_album","motion_status")
        val graph=(context.applicationContext as PicHarborApp).graph
        if(action !in setOf("logout","media_identity")) {
            val settings=graph.preferences.get()
            val overrides=runCatching{JSONObject(settings.executionPolicy)}.getOrDefault(JSONObject())
            val policy=graph.harborCore.dispatch("resolve_execution",JSONObject().put("overrides",if(overrides.length()==0)JSONObject.NULL else overrides).put("size",value.optLong("size",0)))
            if(!policy.optBoolean("ok"))throw EngineFailure(policy.optString("error","ENGINE_ERROR"))
            value.put("_execution",policy.getJSONObject("policy")).put("_file_seconds",policy.getDouble("file_seconds"))
            if(action in setOf("process","process_pair"))value.put("retries",0) // The native wave owns the single retry allowance.
        }
        val activeCallback=callback?:EngineCallback({managed&&(graph.switching.value||graph.backup.shouldStop)})
        activeCallback.attach(diagnostic)
        val lease=if(managed)graph.pipeline.lease{activeCallback.isCancelled()} else null
        activeCallback.attachPipeline(lease)
        val started=android.os.SystemClock.elapsedRealtime()
        Diagnostics.event("engine.call_started",diagnostic,JSONObject().put("action",action))
        var outcome="error"
        try {
            lease?.enter(when(action){"fingerprint"->"preparation";"lookup","process_pair"->"lookup";"sync_album"->"commit";"motion_status"->"confirmation";else->"upload"})
            val result=try {
                JSONObject(Python.getInstance().getModule("picharbor_engine").callAttr("dispatch",action,value.toString(),source,activeCallback).toString())
            } catch (error:Exception){Diagnostics.event("error.captured",diagnostic,DiagnosticFields.error(error),"ERROR");throw EngineFailure(if(activeCallback.isCancelled())"CANCELLED" else "ENGINE_ERROR")}
            if(!result.optBoolean("ok")) {
                val code=if(activeCallback.isCancelled())"CANCELLED" else result.optString("error","ENGINE_ERROR")
                proxy.recordOutcome(code,proxyToken)
                throw EngineFailure(code,result.optJSONObject("_metrics"))
            }
            if(action in setOf("process","process_pair","connect","albums","sync_album","reconcile","synchronize","account_profile"))
                proxy.recordOutcome(result.optString("error").takeIf{it.isNotBlank()},proxyToken)
            outcome="success"
            Diagnostics.event("engine.result",diagnostic,JSONObject().put("state",result.optString("state")).put("code",result.optString("error")))
            result
        } finally {
            lease?.close();activeCallback.attachPipeline(null)
            Diagnostics.event("engine.call_finished",diagnostic,JSONObject().put("action",action).put("outcome",outcome).put("duration_ms",android.os.SystemClock.elapsedRealtime()-started))
        }
    }

    suspend fun connect(credential:String,timeout:Int,proxy:String="")=call("connect",JSONObject().put("credential",credential).put("timeout",timeout).put("proxy",proxy))
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
        "CLEANUP_RUNNING"->"该功能已取消，请使用 Google 相册官方释放空间"
        "CLOUD_TRASH_UNKNOWN"->"暂时无法核实云端回收站状态，手机原件已保留"
        "CLOUD_QUALITY_UNKNOWN"->"云端未返回原始画质信息，手机原件已保留"
        "CLOUD_PROCESSING"->"云端仍在处理文件，请稍后重新检查"
        "CLOUD_ITEM_MISSING"->"最新云端列表尚未找到对应内容，请稍后重试"
        "ORIGINAL_NETWORK_ERROR"->"检查云端原件时连接失败，请检查网络后重试"
        "CLOUD_STATE_UNKNOWN"->"云端信息暂不完整，已保留手机原件"
        "POLICY_CHANGED"->"检查流程已变更，请重新核验"
        "RISK_CONFIRMATION_REQUIRED"->"请输入完整的风险确认语"
        "ORIGINAL_UNCONFIRMED","ORIGINAL_UNAVAILABLE"->"暂时无法确认云端原件，已保留手机文件"
        "FILENAME_ONLY"->"发现同名文件，尚未确认内容相同"
        "CANCELLED"->"操作已暂停"
        "PROXY_INVALID","PROXY_INVALID_SCHEME","PROXY_INVALID_HOST","PROXY_INVALID_PORT","PROXY_INVALID_USERNAME"->"代理设置无效，请检查代理地址、端口和账号"
        "PROXY_TLS_VERIFICATION_DISABLED"->"代理要求保留 TLS 校验，请勿关闭"
        "PROXY_UNAVAILABLE"->"当前代理节点不可用，请等待恢复或选择其他节点"
        "LIVE_PAIR_METADATA_MISMATCH"->"未找到一致的实况照片标识，请选择保留原始元数据的照片和视频"
        "LIVE_PAIR_RESOURCE_MISSING"->"实况照片的原始照片或视频不可用，请检查文件及访问权限"
        "LIVE_KEYFRAME_MISSING"->"无法读取实况照片的关键帧时间，请改用原始照片配对"
        "MOTION_FORMAT_UNSUPPORTED"->"此照片格式暂不支持动态照片封装，请改用原始照片配对"
        "MOTION_METADATA_UNSUPPORTED"->"此照片的元数据结构暂不支持动态封装，请改用原始照片配对"
        "ORIGINAL_VERSION_UNAVAILABLE"->"图库未提供可验证的编辑前版本，请选择保留可用版本"
        "CURRENT_VERSION_UNAVAILABLE","VERSION_RESOURCE_MISSING"->"所选照片版本暂不可用，请检查原始资源或调整版本设置"
        "PAIR_CONFIRMATION_PENDING"->"等待云端确认实况照片，请稍后重试核对"
        "REMOTE_MOTION_PENDING","REMOTE_MOTION_NOT_INDEXED","REMOTE_MOTION_PROCESSING","REMOTE_MOTION_NOT_DYNAMIC"->"内容已保存，正在等待云端完成动态处理"
        "LIVELINK_CAPABILITY_MISSING","INVALID_SCOPE"->"电脑与手机支持的传输范围不一致，请更新两端后重试"
        "STORAGE_FULL"->"手机可用空间不足"
        "STAGING_BUFFER_WAIT"->"等待设备资源恢复"
        "STAGE_TIMEOUT","NO_PROGRESS_TIMEOUT"->"连接等待过久，请检查网络后重试"
        "CLOUD_CONFIRMATION_TIMEOUT"->"云端动态处理等待已达上限，内容已保存，可稍后手动重新核对"
        "INVALID_PIPELINE_LIMITS","INVALID_EXECUTION_POLICY"->"请输入范围内的整数。"
        "REMOTE_NOT_VISIBLE"->"云端暂未返回提交结果，稍后核对"
        "ACCESS_LOST"->"文件夹访问权限已失效"
        else->"暂时未能完成，请稍后重试"
    }
}
