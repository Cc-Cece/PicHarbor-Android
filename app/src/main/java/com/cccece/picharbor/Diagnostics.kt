package com.cccece.picharbor

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LogRange(val start:Long=Long.MIN_VALUE,val end:Long=Long.MAX_VALUE,val zone:String=ZoneId.systemDefault().id) {
    fun includes(time:Long)=time>=start&&time<end
    companion object {
        fun dates(from:LocalDate,to:LocalDate,zone:ZoneId):LogRange {
            require(!to.isBefore(from))
            return LogRange(from.atStartOfDay(zone).toInstant().toEpochMilli(),to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),zone.id)
        }
    }
}

/** No request objects, account identifiers, exception messages or arbitrary dictionaries enter the sink. */
object DiagnosticFields {
    private val email=Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val secrets=Regex("(?i)(bearer\\s+\\S+|(?:password|passwd|token|oauth_token|authorization|cookie|secret|pin|signature)\\s*[:=]\\s*[^\\s,;]+)")
    private val address=Regex("(?i)(?:https?|socks5?)://\\S+")
    private val safe=Regex("^[A-Za-z0-9_.:-]{1,128}$")
    val keys=setOf("action","operation","outcome","duration_ms","phase","kind","state","from_state","reason","code","error_type","cause_types","stack","endpoint","method","status","timeout_connect_ms","timeout_read_ms","retry_attempt","retry_limit","delay_ms","expected_bytes","source_read_bytes","transport_submitted_bytes","remote_confirmed_bytes","response_read_bytes","measurement","filename","item_count","total_bytes","concurrency","runnable","active","permit_limit","wait_ms","snapshot_id","last_progress_age_ms","started_age_ms","cancelled","available","engine_ready","previous_session","clean_exit","exit_reason","exit_status","exit_timestamp","trace_available","stop_reason","scheduler","accepted","worker_attempt","rule","certainty","fd_count","heap_bytes","thread_count","disk_available_bytes","network","network_validated","vpn","foreground","observer_gap_ms","in_flight","span","owner","waiters","request","attempt","source_kind","source_size","sdk","os","model","abi","version","python_stack","native_stack","socket_send_progress","dropped_events","write_failures","queue_depth","corrupt_lines","policy_version","setting_timeout_ms","effective_timeout_ms","sequence_gap","selected_start","selected_end","zone","provider","checks","approved","removed","result_count","actual_start","actual_end","evicted_files","errno","category")
    fun id(value:String)=value.takeIf{safe.matches(it)}?:"invalid"
    fun text(value:String):String=secrets.replace(email.replace(address.replace(value,"[REDACTED_URL]"),"[REDACTED_ACCOUNT]"),"[REDACTED]").replace(Regex("[\\p{Cntrl}]")," ").take(384)
    fun clean(value:JSONObject,depth:Int=0):JSONObject {
        val result=JSONObject()
        if(depth>3)return result
        value.keys().asSequence().take(48).filter{it in keys}.forEach { key ->
            val item=value.opt(key)
            val clean:Any=when(item) {
                null,JSONObject.NULL->JSONObject.NULL
                is Number,is Boolean->item
                is JSONObject->clean(item,depth+1)
                is JSONArray->JSONArray((0 until minOf(item.length(),32)).map { i ->
                    when(val child=item.opt(i)) {is JSONObject->clean(child,depth+1);is Number,is Boolean->child;else->text(child.toString())}
                })
                else->if(key=="filename")text(item.toString().substringAfterLast('/').substringAfterLast('\\')) else text(item.toString())
            }
            result.put(key,clean)
        }
        return result
    }
    fun error(error:Throwable):JSONObject {
        val causes=generateSequence(error){it.cause}.take(8).map{it.javaClass.name}.toList()
        // Source symbols only. Throwable.message/toString and stack locals are never serialized.
        val frames=error.stackTrace.take(24).map{"${it.className}.${it.methodName}:${it.lineNumber}"}
        return JSONObject().put("error_type",error.javaClass.name).put("cause_types",JSONArray(causes)).put("stack",JSONArray(frames))
    }
}

data class DiagnosticContext(val trace:String=UUID.randomUUID().toString().replace("-",""),val span:String=UUID.randomUUID().toString().replace("-","").take(16),val job:String="",val item:String="",val attempt:String="",val parent:String="") {
    fun child()=copy(span=UUID.randomUUID().toString().replace("-","").take(16),parent=span)
    fun json()=JSONObject().put("trace_id",trace).put("span_id",span).put("parent_span_id",parent).put("job_id",job).put("item_id",item).put("attempt_id",attempt)
    companion object {
        fun parse(value:JSONObject)=DiagnosticContext(
            value.optString("trace_id").takeIf{it.matches(Regex("[0-9a-f]{32}"))&&it.any{c->c!='0'}}?:UUID.randomUUID().toString().replace("-",""),
            value.optString("span_id").takeIf{it.matches(Regex("[0-9a-f]{16}"))&&it.any{c->c!='0'}}?:UUID.randomUUID().toString().replace("-","").take(16),
            value.optString("job_id").let{if(it.isEmpty())"" else DiagnosticFields.id(it)},value.optString("item_id").let{if(it.isEmpty())"" else DiagnosticFields.id(it)},
            value.optString("attempt_id").let{if(it.isEmpty())"" else DiagnosticFields.id(it)},value.optString("parent_span_id").takeIf{it.matches(Regex("[0-9a-f]{16}"))}.orEmpty())
    }
}

@Keep
class DiagnosticBridge {
    @Keep fun record(name:String,context:String,attrs:String) {
        runCatching { Diagnostics.event(name,DiagnosticContext.parse(JSONObject(context)),JSONObject(attrs),level=if(name=="error.captured")"ERROR" else "INFO",producer="python-engine") }
    }
    @Keep fun monotonicMs()=SystemClock.elapsedRealtime()
}

/** Always on, bounded and local. Business threads never wait for storage or the monitor. */
object Diagnostics {
    const val MAX_BYTES=64L*1024*1024
    const val KEEP_MS=14L*24*60*60*1000
    private const val SEGMENT_BYTES=2L*1024*1024
    private val session=UUID.randomUUID().toString()
    private val root=DiagnosticContext()
    private val boot=UUID.randomUUID().toString()
    private val sequences=ConcurrentHashMap<String,AtomicLong>()
    private val queue=ArrayBlockingQueue<String>(192)
    private val dropped=AtomicLong()
    private val failures=AtomicLong()
    private val evicted=AtomicLong()
    private val writer=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"harbor-log-writer").apply{isDaemon=true}}
    private val monitor=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"harbor-log-monitor").apply{isDaemon=true}}
    private val exporting=AtomicBoolean()
    private var app:Context?=null
    private var directory:File?=null
    private var segment:File?=null
    private var segmentDay=""
    private val active=ConcurrentHashMap<String,Flight>()
    private val lastEmission=ConcurrentHashMap<String,Long>()
    @Volatile var foreground=false
    @Volatile private var mainBeat=SystemClock.elapsedRealtime()
    private var lastMonitor=SystemClock.elapsedRealtime()
    private var lastHealth=0L
    private var lastNetwork=""
    private var lastUiIncident=0L
    data class Flight(val context:DiagnosticContext,val operation:String,val started:Long,@Volatile var progress:Long,@Volatile var warned:Long=0,@Volatile var bytes:Long=-1)
    fun start(context:Context) {
        if(app!=null)return
        app=context.applicationContext
        directory=File(context.noBackupFilesDir,"diagnostics")
        writer.execute {
            runCatching {
                directory!!.mkdirs();prune()
                val marker=File(directory,"session.json")
                val old=runCatching{JSONObject(marker.readText())}.getOrNull()
                marker.writeText(JSONObject().put("session",session).toString())
                event("app.started",attrs=JSONObject().put("version",BuildConfig.VERSION_NAME).put("previous_session",old?.optString("session").orEmpty()).put("clean_exit",if(old==null)JSONObject.NULL else false))
                if(Build.VERSION.SDK_INT>=30) {
                    val exits=context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null,0,3)
                    exits.forEach { exit -> event("app.exit_observed",attrs=JSONObject().put("exit_reason",exit.reason).put("exit_status",exit.status).put("exit_timestamp",exit.timestamp).put("trace_available",false)) }
                }
            }.onFailure{failures.incrementAndGet()}
        }
        writer.scheduleWithFixedDelay({runCatching{drain()}.onFailure{failures.incrementAndGet()}},0,1,TimeUnit.SECONDS)
        monitor.scheduleWithFixedDelay({runCatching{inspect()}.onFailure{failures.incrementAndGet()}},5,5,TimeUnit.SECONDS)
        val handler=Handler(Looper.getMainLooper())
        val heartbeat=object:Runnable {override fun run(){mainBeat=SystemClock.elapsedRealtime();handler.postDelayed(this,1000)}}
        handler.post(heartbeat)
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler {thread,error ->
            runCatching {
                val row=makeEvent("app.crashed",root,DiagnosticFields.error(error),"FATAL","kotlin-main")
                // Independent small emergency write, even if normal writer is blocked.
                val dir=directory
                if(dir!=null&&dir.exists()) {
                    val file=File(dir,"crash-$session.jsonl")
                    val bytes=(row.toString()+"\n").toByteArray()
                    if(dir.listFiles().orEmpty().sumOf{it.length()}+bytes.size<=MAX_BYTES)file.outputStream().use{it.write(bytes);it.fd.sync()}
                }
            }
            previous?.uncaughtException(thread,error)
        }
    }
    fun event(name:String,context:DiagnosticContext=root,attrs:JSONObject=JSONObject(),level:String="INFO",producer:String="kotlin-main") {
        if(app==null||!name.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")))return
        runCatching {
            val now=SystemClock.elapsedRealtime()
            val key=context.span
            if(name in setOf("operation.started","http.started","wait.started","engine.call_started")) {
                if(active.size<256)active[key]=Flight(context,attrs.optString("operation",attrs.optString("endpoint",attrs.optString("kind",attrs.optString("action",name)))),now,now)
            }
            if(name in setOf("operation.finished","http.finished","wait.finished","engine.call_finished")){
                active.remove(key);lastEmission.remove(key)
                if(name!="wait.finished")active.values.filter{it.context.trace==context.trace&&it.context.attempt==context.attempt}.forEach{it.progress=now}
            }
            if(name=="transfer.progress") {
                val bytes=attrs.optLong("source_read_bytes",-1)
                active.values.filter{it.context.trace==context.trace&&it.context.attempt==context.attempt}.forEach{if(bytes!=it.bytes){it.progress=now;it.bytes=bytes}}
                val before=lastEmission.putIfAbsent(key,now)
                if(before!=null&&now-before<10000)return
                lastEmission[key]=now
            }
            val row=makeEvent(name,context,attrs,level,producer).toString()
            if(row.toByteArray().size>16384||!queue.offer(row))dropped.incrementAndGet()
        }.onFailure{dropped.incrementAndGet()}
    }
    private fun makeEvent(name:String,c:DiagnosticContext,attrs:JSONObject,level:String,producer:String):JSONObject {
        val seq=sequences.computeIfAbsent(producer){AtomicLong()}.incrementAndGet()
        val row=JSONObject().put("schema_version",1).put("catalog_version",1).put("event_id","$session:$producer:$seq").put("event",name)
            .put("level",level).put("platform","android").put("component",if(producer=="python-engine")"engine" else "android")
            .put("build_id",BuildConfig.VERSION_NAME+"-"+BuildConfig.VERSION_CODE).put("session_id",session).put("producer_id",producer).put("seq",seq).put("boot_id",boot)
            .put("ts_utc",Instant.now().toString()).put("mono_ms",SystemClock.elapsedRealtime()).put("clock_source","android_elapsed_realtime")
            .put("trace_id",c.trace).put("span_id",c.span).put("attrs",DiagnosticFields.clean(attrs))
        if(name.startsWith("http."))row.put("request_id",c.span)
        if(name=="error.captured")row.put("error_id",UUID.randomUUID().toString())
        if(name=="incident.opened")row.put("incident_id",UUID.randomUUID().toString())
        if(c.parent.isNotEmpty())row.put("parent_span_id",c.parent)
        if(c.job.isNotEmpty())row.put("job_id",c.job)
        if(c.item.isNotEmpty())row.put("item_id",c.item)
        if(c.attempt.isNotEmpty())row.put("attempt_id",c.attempt)
        return row
    }
    private fun drain() {
        val dir=directory?:return
        dir.mkdirs();prune()
        var count=0
        while(count++<192) {
            val line=queue.poll()?:break
            val bytes=(line+"\n").toByteArray(Charsets.UTF_8)
            val day=LocalDate.now(java.time.ZoneOffset.UTC).toString()
            var file=segment
            if(file==null||!file.exists()||file.length()+bytes.size>SEGMENT_BYTES||segmentDay!=day) {
                file=File(dir,"events-$day-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jsonl")
                segment=file;segmentDay=day
            }
            // Prune before allocation; reserve metadata/emergency space.
            prune(bytes.size.toLong()+65536)
            if(dir.listFiles().orEmpty().sumOf{it.length()}+bytes.size>MAX_BYTES){dropped.incrementAndGet();continue}
            file.appendBytes(bytes)
        }
    }
    private fun prune(reserve:Long=0) {
        val dir=directory?:return
        val cutoff=System.currentTimeMillis()-KEEP_MS
        val files=dir.listFiles().orEmpty().filter{it.extension=="jsonl"}.sortedBy{it.lastModified()}
        files.filter{file->
            val created=Regex("events-\\d{4}-\\d{2}-\\d{2}-(\\d+)-").find(file.name)?.groupValues?.get(1)?.toLongOrNull()?:file.lastModified()
            created<cutoff
        }.forEach{if(it.delete())evicted.incrementAndGet()}
        var size=dir.listFiles().orEmpty().sumOf{it.length()}
        for(file in files) {
            if(size+reserve<=MAX_BYTES)break
            val length=file.length()
            if(file.delete()){size-=length;evicted.incrementAndGet()}
        }
    }
    private fun inspect() {
        val now=SystemClock.elapsedRealtime();val gap=now-lastMonitor;lastMonitor=now
        if(gap>15000){event("diagnostics.observer_gap",attrs=JSONObject().put("observer_gap_ms",gap));return}
        val overdue=active.values.filter{it.operation!="permit"&&now-it.progress>=60000&&(it.warned==0L||now-it.warned>=300000)}
        overdue.take(3).forEach { flight ->
            flight.warned=now
            val snapshot=snapshot().put("rule","NO_PROGRESS").put("operation",flight.operation).put("last_progress_age_ms",now-flight.progress).put("certainty","observed_scope_only")
            event("incident.opened",flight.context,snapshot,"WARN")
            // Bound stack collection; it never asks Python to acquire the GIL.
            event("runtime.stacks",flight.context,JSONObject().put("stack",JSONArray(Thread.getAllStackTraces().entries.filter{it.key.name=="main"||it.key.name.startsWith("DefaultDispatcher")}.take(6).map { entry ->
                entry.key.name+": "+entry.value.take(12).joinToString(" | "){"${it.className}.${it.methodName}:${it.lineNumber}"}
            })),"WARN")
        }
        if(foreground&&now-mainBeat>5000&&(lastUiIncident==0L||now-lastUiIncident>300000)){lastUiIncident=now;event("incident.opened",attrs=JSONObject().put("rule","UI_UNRESPONSIVE").put("last_progress_age_ms",now-mainBeat),level="WARN")}
        if(now-lastHealth>=30000) {
            lastHealth=now
            event("runtime.snapshot",attrs=snapshot())
            event("diagnostics.health",attrs=JSONObject().put("dropped_events",dropped.get()).put("write_failures",failures.get()).put("evicted_files",evicted.get()).put("queue_depth",queue.size))
            val net=network().toString()
            if(net!=lastNetwork){lastNetwork=net;event("environment.changed",attrs=JSONObject(net))}
        }
    }
    fun snapshot():JSONObject=JSONObject().put("in_flight",JSONArray(active.values.take(32).map { f -> JSONObject().put("span",f.context.span).put("attempt",f.context.attempt).put("operation",f.operation).put("started_age_ms",SystemClock.elapsedRealtime()-f.started).put("last_progress_age_ms",SystemClock.elapsedRealtime()-f.progress) }))
        .put("active",active.size).put("heap_bytes",Debug.getPss()*1024L).put("fd_count",File("/proc/self/fd").list()?.size?:-1).put("thread_count",Thread.activeCount()).put("disk_available_bytes",directory?.usableSpace?:0)
        .put("foreground",foreground).put("python_stack","unavailable").put("native_stack","unavailable").put("socket_send_progress","unavailable")
    private fun network():JSONObject=runCatching {
        val cm=app!!.getSystemService(ConnectivityManager::class.java);val n=cm.getNetworkCapabilities(cm.activeNetwork)
        JSONObject().put("network",when {n==null->"none";n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"wifi";n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"cellular";else->"other"})
            .put("vpn",n?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?:false).put("network_validated",n?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?:false)
    }.getOrElse{JSONObject().put("network","unknown")}

    /** Export on Dispatchers.IO, never the log writer. No business DB, engine or account access. */
    fun export(output:OutputStream,range:LogRange):String {
        check(exporting.compareAndSet(false,true)){"EXPORT_BUSY"}
        try {
            val barrier=writer.submit{drain()}
            val flushed=runCatching{barrier.get(2,TimeUnit.SECONDS)}.isSuccess
            val dir=directory?:error("LOGS_UNAVAILABLE")
            val cutoff=System.currentTimeMillis();val since=cutoff-KEEP_MS
            var corrupt=0;var missing=0;var count=0;var first=Long.MAX_VALUE;var last=Long.MIN_VALUE
            data class Source(val length:Long,val json:java.io.FileInputStream,val text:java.io.FileInputStream)
            val files=dir.listFiles().orEmpty().filter{it.extension=="jsonl"&&it.lastModified()>=since}.sortedBy{it.name}.mapNotNull{file->
                var firstStream:java.io.FileInputStream?=null
                try {val length=file.length();firstStream=file.inputStream();Source(length,firstStream,file.inputStream())}
                catch(_:Exception){runCatching{firstStream?.close()};missing++;null}
            }
            val bundle=UUID.randomUUID().toString()
            val members=JSONArray()
            var earliest=Long.MAX_VALUE
            fun rows(textView:Boolean=false,consume:(JSONObject)->Unit) {
                for(source in files) {
                        val length=source.length
                        // Both handles were opened before export. Unlink/append cannot change this byte range.
                        (if(textView)source.text else source.json).use { raw ->
                            val limited=object:java.io.FilterInputStream(raw) {
                                var remaining=length
                                override fun read():Int {if(remaining<=0)return -1;val b=super.read();if(b>=0)remaining--;return b}
                                override fun read(b:ByteArray,off:Int,len:Int):Int {if(remaining<=0)return -1;val n=`in`.read(b,off,minOf(len.toLong(),remaining).toInt());if(n>0)remaining-=n;return n}
                            }
                            limited.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                                if(line.length>16384){if(!textView)corrupt++;return@forEach}
                                val event=runCatching{JSONObject(line)}.getOrNull()
                                val time=runCatching{Instant.parse(event?.optString("ts_utc")).toEpochMilli()}.getOrNull()
                                if(event==null||time==null){if(!textView)corrupt++;return@forEach}
                                event.put("attrs",DiagnosticFields.clean(event.optJSONObject("attrs")?:JSONObject()))
                                if(time>=since&&time<=cutoff){earliest=minOf(earliest,time);if(range.includes(time))consume(event)}
                            } }
                        }
                }
            }
            // Persist the same selected stream once; both views are generated from these safe objects.
            // Bounded by 64 MiB retained storage. Avoid keeping all events in RAM or a second disk copy.
            try { ZipOutputStream(output).use { zip ->
                fun entry(name:String,write:(OutputStream)->Unit) {
                    zip.putNextEntry(ZipEntry(name));val digest=MessageDigest.getInstance("SHA-256");var size=0L
                    val wrapped=object:OutputStream(){override fun write(b:Int){zip.write(b);digest.update(b.toByte());size++};override fun write(b:ByteArray,off:Int,len:Int){zip.write(b,off,len);digest.update(b,off,len);size+=len}}
                    write(wrapped);zip.closeEntry()
                    members.put(JSONObject().put("path",name).put("bytes",size).put("sha256",digest.digest().joinToString(""){"%02x".format(it)}))
                }
                entry("events.jsonl") { out -> rows { row ->
                    count++;val time=Instant.parse(row.getString("ts_utc")).toEpochMilli();first=minOf(first,time);last=maxOf(last,time)
                    out.write((row.toString()+"\n").toByteArray(Charsets.UTF_8))
                } }
                if(count==0)error("NO_LOGS_IN_RANGE")
                val initialMissing=missing;val initialCorrupt=corrupt
                entry("readable.log") { out -> rows(textView=true) { row ->
                    val refs=listOf("job_id","item_id","attempt_id").filter{row.has(it)}.joinToString(" "){"$it=${row.optString(it)}"}
                    val text="${row.optString("ts_utc")} ${row.optString("level").padEnd(5)} [${row.optString("component")}] [$refs] ${row.optString("event")} ${row.optJSONObject("attrs")} [event=${row.optString("event_id")}]\n"
                    out.write(text.toByteArray(Charsets.UTF_8))
                } }
                entry("event.schema.json"){out->app!!.assets.open("diagnostic-event.schema.json").use{it.copyTo(out)}}
                entry("format.md"){out->app!!.assets.open("diagnostic-format.md").use{it.copyTo(out)}}
                val environment=JSONObject().put("captured_at_export",Instant.now().toString()).put("app_version",BuildConfig.VERSION_NAME).put("build",BuildConfig.VERSION_CODE).put("sdk",Build.VERSION.SDK_INT).put("model",DiagnosticFields.text(Build.MODEL)).put("abi",Build.SUPPORTED_ABIS.firstOrNull()).put("runtime",snapshot()).put("network",network())
                entry("environment.json"){it.write(environment.toString(2).toByteArray())}
                val partial=missing>0||corrupt>0||!flushed||dropped.get()>0||failures.get()>0
                val coverage=JSONObject().put("writer_flushed",flushed).put("missing_files",missing).put("corrupt_lines",corrupt).put("dropped_events",dropped.get()).put("write_failures",failures.get()).put("python_stack","unavailable").put("socket_send_progress","unavailable").put("outside_range_context","excluded_by_date_range").put("evicted_files_this_session",evicted.get()).put("two_view_read_changes",missing!=initialMissing||corrupt!=initialCorrupt)
                entry("coverage.json"){it.write(coverage.toString(2).toByteArray())}
                val summary="""# PicHarbor Android 日志
                    |包 ID：$bundle
                    |版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                    |事件数：$count；实际范围：${Instant.ofEpochMilli(first)} 至 ${Instant.ofEpochMilli(last)}
                    |完整性：${if(partial)"部分证据缺失，详见 coverage.json" else "所选范围内已读取的保留记录"}
                    |文件名可能保留；不包含照片/视频本体、账号或认证凭据。
                    |先读 readable.log，再按 event_id/trace_id 关联 events.jsonl。缺少结束事件可能由日期筛选、轮转或进程退出造成，不能直接判定死锁。
                    |source_read_bytes 仅指本地读取量，不等于已发送或云端已接收。请求超时不证明云端提交失败。
                    |未采集 Python/native 栈及逐字节网络发送量；不要仅凭通用错误码断言根因。
                    |用户症状与复现步骤：未提供，请结合反馈补充。
                """.trimMargin()
                entry("summary.md"){it.write(summary.toByteArray())}
                val manifest=JSONObject().put("bundle_version",1).put("schema_version",1).put("catalog_version",1).put("policy_version",1).put("redactor_version",1).put("bundle_id",bundle).put("created_at",Instant.now().toString())
                    .put("retention_days",14).put("max_bytes",MAX_BYTES).put("range_start",if(range.start==Long.MIN_VALUE)JSONObject.NULL else Instant.ofEpochMilli(range.start).toString()).put("range_end_exclusive",if(range.end==Long.MAX_VALUE)JSONObject.NULL else Instant.ofEpochMilli(range.end).toString()).put("timezone",range.zone)
                    .put("actual_start",Instant.ofEpochMilli(first).toString()).put("actual_end",Instant.ofEpochMilli(last).toString()).put("cutoff",Instant.ofEpochMilli(cutoff).toString()).put("partial",partial).put("range_start_unavailable",range.start!=Long.MIN_VALUE&&earliest>range.start).put("members",members)
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString(2).toByteArray());zip.closeEntry()
            }
            } finally {files.forEach{runCatching{it.json.close()};runCatching{it.text.close()}}}
            return if(missing>0||corrupt>0||!flushed)"日志已导出，部分记录不可用，详见包内说明" else if(range.start!=Long.MIN_VALUE&&earliest>range.start)"日志已导出，部分日期暂无记录或已被覆盖" else "日志已导出"
        } finally {exporting.set(false)}
    }
}
