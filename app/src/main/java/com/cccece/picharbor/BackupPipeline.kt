package com.cccece.picharbor

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Gates native work using shared limits. Reconfiguration occurs between job waves. */
class BackupPipeline(private val graph:AppGraph) {
    private var gates=mapOf<String,Semaphore>()
    private var stagingLimit=256L*1024*1024
    private val staged=mutableMapOf<String,Long>()
    val activity=MutableStateFlow<Map<String,Int>>(emptyMap())
    val effective=MutableStateFlow(JSONObject())
    private var pressureAt=0L
    private var constrained=false
    private var severe=false
    @Synchronized private fun pressure(stage:String):Int {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-pressureAt>1000) {
            pressureAt=now
            val memory=ActivityManager.MemoryInfo().also{graph.app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
            val power=graph.app.getSystemService(PowerManager::class.java)
            val thermal=if(android.os.Build.VERSION.SDK_INT>=29)power.currentThermalStatus else 0
            constrained=power.isPowerSaveMode||thermal>=2||memory.availMem<256L*1024*1024
            severe=memory.lowMemory||thermal>=3||memory.availMem<128L*1024*1024||graph.app.cacheDir.usableSpace<128L*1024*1024
        }
        if(severe&&stage=="preparation")throw EngineFailure("STAGING_BUFFER_WAIT")
        return if(severe)1 else if(constrained)if(stage=="upload")2 else 1 else Int.MAX_VALUE
    }
    suspend fun configure(settings:HarborSettings) {
        val memory=ActivityManager.MemoryInfo().also{graph.app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
        val power=graph.app.getSystemService(PowerManager::class.java)
        val memoryGB=kotlin.math.ceil(memory.totalMem.toDouble()/(1024*1024*1024)).toInt()
        val processors=Runtime.getRuntime().availableProcessors()
        val device=JSONObject().put("memory_gb",memoryGB).put("available_mb",memory.availMem/(1024*1024))
            .put("processors",processors).put("low_power",power.isPowerSaveMode).put("memory_warning",memory.lowMemory)
            .put("thermal",if(android.os.Build.VERSION.SDK_INT>=29)when(power.currentThermalStatus){in 0..1->0;2->2;else->3} else 0)
            .put("free_disk_mb",graph.app.cacheDir.usableSpace/(1024*1024))
            .put("profile",if(memoryGB>=8&&processors>=8)"performance" else if(memoryGB>=6)"balanced" else "conservative")
        val overrides=runCatching{JSONObject(settings.pipelineLimits)}.getOrDefault(JSONObject())
        val decision=graph.harborCore.dispatch("resolve_pipeline",JSONObject().put("mode",settings.pipelineMode).put("limits",overrides).put("device",device))
        if(!decision.optBoolean("ok"))throw EngineFailure(decision.optString("error","ENGINE_ERROR"))
        val limits=decision.getJSONObject("limits")
        gates=listOf("preparation","lookup","upload","commit","confirmation").associateWith{Semaphore(limits.getInt(it),true)}
        synchronized(staged){stagingLimit=limits.getLong("buffer_mb")*1024*1024}
        effective.value=limits
    }
    fun lease(cancelled:()->Boolean)=Lease(cancelled,gates)
    inner class Lease(private val cancelled:()->Boolean,private val limits:Map<String,Semaphore>):AutoCloseable {
        private var stage=""
        fun enter(next:String) {
            if(next==stage)return
            close()
            val gate=limits[next]?:return
            if(next=="preparation"&&gate.availablePermits()==0&&effective.value.optInt("preparation")==0)throw EngineFailure("STAGING_BUFFER_WAIT")
            while(true) {
                if(cancelled())throw EngineFailure("CANCELLED")
                synchronized(this@BackupPipeline) {
                    if((activity.value[next]?:0)<pressure(next)&&gate.tryAcquire()) {
                        stage=next;activity.update{it+(next to ((it[next]?:0)+1))};return
                    }
                }
                Thread.sleep(100)
            }
        }
        override fun close() = synchronized(this@BackupPipeline) {
            val old=stage
            if(old.isNotEmpty()){stage="";limits[old]?.release();activity.update{it+(old to ((it[old]?:1)-1).coerceAtLeast(0))}}
        }
    }
    fun beginStaging(id:String)=synchronized(staged){staged[id]=0L}
    fun reserveStaging(id:String,bytes:Int)=synchronized(staged) {
        if(graph.app.cacheDir.usableSpace<bytes+128L*1024*1024)throw EngineFailure("STORAGE_FULL")
        if(staged.values.sum()+bytes>stagingLimit&&staged.size>1)throw EngineFailure("STAGING_BUFFER_WAIT")
        staged[id]=(staged[id]?:0)+bytes
    }
    fun endStaging(id:String)=synchronized(staged){staged.remove(id);Unit}
}
