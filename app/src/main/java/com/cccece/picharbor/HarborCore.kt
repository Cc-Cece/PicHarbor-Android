package com.cccece.picharbor

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Core task states, kept as plain strings identical to harbor_core. */
object HarborCoreState {
    const val QUEUED = "queued"
    const val PREPARING = "preparing"
    const val DEDUP_HIT = "dedup_hit"
    const val UPLOADING = "uploading"
    const val SUBMITTING = "submitting"
    const val CONFIRMING = "confirming"
    const val DONE = "done"
    const val PAUSED = "paused"
    const val FAILED = "failed"
    const val WAITING_NETWORK = "waiting_network"
    const val WAITING_CONFIRMATION = "waiting_confirmation"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
}

/**
 * Thin adapter over the shared core (gpmc `harbor_core.dispatch_json`).
 * Only JSON encoding/decoding, the Python call and the core-store cache live
 * here; no business decisions are made in Kotlin. The core is stateless:
 * callers pass the full task dict they got from the previous call, and this
 * adapter persists the latest task dicts to [storePath] (best-effort) so a
 * restart can recover them with store_load.
 *
 * When the core module is absent or a call fails, dispatch returns
 * {"ok": false, "error": "ENGINE_ERROR"} and the caller does NOT fall back
 * to a second business implementation: there is exactly one business core
 * (stage two removed the legacy direct-call fallback). Callers treat an
 * unavailable core as a retryable failure (item stays queued / job waits)
 * rather than deciding business rules locally.
 *
 * New stage-two actions (batch_pause, batch_resume, evaluate_scope,
 * validate_proxy, livelink_handshake, livelink_scope, migrate_legacy) may
 * not exist yet in the synced core snapshot: the central gpmc core is being
 * extended in parallel. Every wrapper below therefore treats
 * {"ok": false, "error": "UNKNOWN_OPERATION"} as "core does not know this
 * action yet" and applies the documented local fallback (never a crash,
 * never a silent full-scope grant, never invented evidence). See
 * [isUnknownOperation]. The core module is resolved as `gpmc.harbor_core`
 * (the sync target of tools/sync_gpmc.py) with a top-level `harbor_core` fallback.
 * Error text for the UI reuses Messages.error(code) from Engine.kt.
 */
class HarborCore(private val storePath: String = "", private val dao: HarborDao? = null) {
    private val decisionLock = Mutex()
    @Volatile var contractReady=false
        private set
    suspend fun verifyContract():Boolean {
        val result=dispatch("capabilities")
        val actions=result.optJSONArray("actions")?:org.json.JSONArray()
        val names=(0 until actions.length()).map{actions.getString(it)}.toSet()
        contractReady=result.optBoolean("ok")&&result.optInt("protocol_version")==2&&
            setOf("create_task","transition","evaluate_scope","batch_pause","batch_resume","select_proxy").all{it in names}
        return contractReady
    }
    @Volatile private var moduleName: String? = null
    private val storeLock = Mutex()
    private val tasks = JSONObject()

    private fun resolveModule(): String? {
        moduleName?.let { return it }
        return try {
            val python = Python.getInstance()
            val found = listOf("gpmc.harbor_core", "harbor_core").firstOrNull { name ->
                runCatching { python.getModule(name) }.isSuccess
            }
            if (found != null) moduleName = found
            found
        } catch (_: Exception) {
            null
        }
    }

    fun available(): Boolean = resolveModule() != null

    fun status(): JSONObject = JSONObject()
        .put("available", available())
        .put("module", moduleName ?: JSONObject.NULL)
        .put("error", if (available()) "" else "ENGINE_ERROR")

    suspend fun dispatch(action: String, payload: JSONObject = JSONObject()): JSONObject = decisionLock.withLock {
        withContext(Dispatchers.IO) {
        if(action=="create_task" && dao!=null) {
            dao.coreTask(payload.optString("task_id"))?.let {
                return@withContext JSONObject().put("ok",true).put("task",JSONObject(it.payload))
            }
        }
        // Compare against the persisted revision before accepting any callback.
        val source = payload.optJSONObject("task")
        if (source != null && dao != null) {
            val saved = dao.coreTask(source.optString("task_id"))
            if (saved != null && source.optLong("revision") != saved.revision)
                return@withContext JSONObject().put("ok", false).put("error", "STALE_TASK")
        }
        if(dao!=null)payload.optJSONObject("tasks")?.let { batch ->
            val ids=batch.keys()
            while(ids.hasNext()) {
                val id=ids.next();val saved=dao.coreTask(id)
                if(saved!=null&&saved.revision!=batch.getJSONObject(id).optLong("revision"))
                    return@withContext JSONObject().put("ok",false).put("error","STALE_TASK")
            }
        }
        val module = resolveModule()
            ?: return@withContext JSONObject().put("ok", false).put("error", "ENGINE_ERROR")
        try {
            val raw = Python.getInstance().getModule(module)
                .callAttr("dispatch_json", action, payload.toString()).toString()
            val result = JSONObject(raw)
            if (result.optBoolean("ok") && dao != null) {
                suspend fun save(task: JSONObject) {
                    val id = task.getString("task_id")
                    dao.putCoreTask(CoreTaskSnapshot(id, task.toString(), task.optLong("revision")))
                }
                result.optJSONObject("task")?.let { save(it) }
                result.optJSONArray("results")?.let { rows ->
                    for (i in 0 until rows.length()) rows.getJSONObject(i).optJSONObject("task")?.let { save(it) }
                }
            }
            result
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "ENGINE_ERROR")
        }
        }
    }

    /** Persists one task dict into the cached store file. Never throws. */
    private suspend fun persist(task: JSONObject) {
        if (dao != null || storePath.isEmpty()) return
        val id = task.optString("task_id")
        if (id.isEmpty()) return
        runCatching {
            storeLock.withLock {
                File(storePath).parentFile?.mkdirs()
                tasks.put(id, task)
                dispatch("store_save", JSONObject().put("path", storePath).put("tasks", tasks))
            }
        }
    }

    /** The updated task dict from a create/transition result, or null. */
    fun updatedTask(result: JSONObject): JSONObject? =
        if (result.optBoolean("ok")) result.optJSONObject("task") else null

    suspend fun createTask(taskId: String, asset: JSONObject, idempotencyKey: String): JSONObject {
        dao?.coreTask(taskId)?.let { saved ->
            val task = JSONObject(saved.payload)
            if (task.optString("state") in setOf("interrupted", "failed", "waiting_network"))
                return transition(task, "queued")
            return JSONObject().put("ok", true).put("task", task)
        }
        val result = dispatch("create_task", JSONObject()
            .put("task_id", taskId).put("asset", asset).put("idempotency_key", idempotencyKey))
        updatedTask(result)?.let { persist(it) }
        return result
    }

    /**
     * Moves the given full task dict to [toState]. The core enforces legal
     * edges and replays idempotently; an illegal edge returns
     * {"ok": false, "error": "INVALID_TRANSITION"} and the task is unchanged.
     * On success the returned task dict replaces the caller's copy.
     */
    suspend fun transition(task: JSONObject, toState: String, errorCode: String? = null, remoteKey: String? = null, cloudConfirmed: Boolean? = null): JSONObject {
        val payload = JSONObject().put("task", task).put("to", toState)
        if (errorCode != null) payload.put("error_code", errorCode)
        if (remoteKey != null) payload.put("remote_key", remoteKey)
        if (cloudConfirmed != null) payload.put("cloud_confirmed", cloudConfirmed)
        val result = dispatch("transition", payload)
        updatedTask(result)?.let { persist(it) }
        return result
    }

    /**
     * Dedup decision for an asset. The caller supplies [remoteKey] from a
     * prior hash lookup (engine "lookup"); without it the core can only
     * answer {"exists": false}. Returns {"exists": true, "remote_key": ...}
     * on a hit, {"exists": false} otherwise.
     */
    suspend fun decidePrepare(asset: JSONObject, remoteKey: String? = null): JSONObject {
        val payload = JSONObject().put("asset", asset)
        if (remoteKey != null) payload.put("remote_key", remoteKey)
        return dispatch("decide_prepare", payload)
    }

    /**
     * Per-resource dedup decision for a multi-resource asset (Live Photo /
     * Motion Photo): one call with the asset (including "resources") and
     * [remoteKeys] keyed by role ({"photo": key, "video": key}; absent keys
     * mean "not found"). The core answers {"exists": all, "resources":
     * [{role, exists, remote_key?}]}. A single remote_key must NOT be sent
     * together with resources (the core rejects it as INVALID_ARGUMENT).
     */
    suspend fun decidePrepare(asset: JSONObject, remoteKeys: JSONObject): JSONObject =
        dispatch("decide_prepare", JSONObject().put("asset", asset).put("remote_keys", remoteKeys))

    /**
     * decide_cleanup judges only whether the cloud-confirmation evidence is
     * sufficient to guide the user to Google Photos' official free-up-space.
     * It is NOT a permit to delete local originals: PicHarbor no longer
     * deletes local assets itself (REQUIREMENTS.md 2026-10-19; Yuto decision
     * 2026-11-13). The core must never treat its result as delete approval.
     * Evidence needs cloud_state and checked_at (epoch seconds, fresh within
     * 3600 s) plus a bool policy; only cloud_state == "confirmed_original"
     * with policy allowed returns {"eligible": true}. Stage two adds an
     * optional "items" array to the evidence
     * ([{task_id, cloud_confirmed, confirmed_at}] taken from reconcile /
     * cloud-confirmation results); the Kotlin caller additionally requires
     * the queried task to appear in that array with cloud_confirmed=true —
     * without such an item there is no per-item release, only page-level
     * guidance. Evidence is never invented: a missing item means "not
     * eligible", not "assume confirmed".
     */
    suspend fun decideCleanup(task: JSONObject, evidence: JSONObject): JSONObject =
        dispatch("decide_cleanup", JSONObject().put("task", task).put("evidence", evidence))

    /**
     * Core backoff advice: attempt is the 0-based count of failed attempts
     * (0 -> 5 s, doubling, capped at 300 s). Returns the delay in
     * milliseconds, or -1 when unavailable.
     */
    suspend fun retryDelayMs(attempt: Int): Long {
        val result = dispatch("retry_delay", JSONObject().put("attempt", attempt))
        return if (result.optBoolean("ok")) result.optLong("delay_seconds", -1) * 1000 else -1
    }

    /** Loads the persisted store into the cache; returns the core result. */
    suspend fun storeLoad(): JSONObject {
        if (dao != null) {
            val stored = JSONObject()
            dao.coreTasks().forEach { stored.put(it.id, JSONObject(it.payload)) }
            return JSONObject().put("ok", true).put("tasks", stored)
        }
        if (storePath.isEmpty()) return JSONObject().put("ok", false).put("error", "ENGINE_ERROR")
        val result = dispatch("store_load", JSONObject().put("path", storePath))
        if (result.optBoolean("ok")) {
            val loaded = result.optJSONObject("tasks") ?: JSONObject()
            storeLock.withLock {
                val old = mutableListOf<String>()
                val oldKeys = tasks.keys()
                while (oldKeys.hasNext()) old.add(oldKeys.next())
                old.forEach { tasks.remove(it) }
                val fresh = loaded.keys()
                while (fresh.hasNext()) {
                    val key = fresh.next()
                    tasks.put(key, loaded.get(key))
                }
            }
        }
        return result
    }

    suspend fun capabilities(): JSONObject = dispatch("capabilities")

    /** True when the core answered that it does not know this action yet. */
    fun isUnknownOperation(result: JSONObject): Boolean =
        !result.optBoolean("ok") && result.optString("error") == "UNKNOWN_OPERATION"

    /**
     * Batch pause / resume. Input is {task_ids, tasks} per the stage-two
     * core contract; the result carries per-task outcomes (a "results"
     * array or a "tasks" object, each entry with ok/state/error). Returns
     * the raw core result; callers parse it with [parseBatchResults] and
     * must keep going when individual tasks fail (a partial mirror never
     * blocks the local pause/resume). UNKNOWN_OPERATION means the synced
     * core predates the batch actions: callers then mirror per task with
     * [transition] instead.
     */
    suspend fun batchPause(taskIds: List<String>, tasks: JSONObject): JSONObject =
        dispatch("batch_pause", JSONObject().put("task_ids", org.json.JSONArray(taskIds)).put("tasks", tasks))

    suspend fun batchResume(taskIds: List<String>, tasks: JSONObject): JSONObject =
        dispatch("batch_resume", JSONObject().put("task_ids", org.json.JSONArray(taskIds)).put("tasks", tasks))

    /**
     * Scope evaluation for one asset against the backup scope. The scope
     * dict is built by ScopePolicy (mode/albums/dateEnabled/from/to/
     * photos/videos/ignoreSmallImages/minImageKb/filterCache) and means the
     * same on both clients. Returns the raw core result, expected to carry
     * {"included": true/false}. Callers must NOT treat an unknown-version
     * scope error as "include everything": see ScopePolicy.
     */
    suspend fun evaluateScope(scope: JSONObject, asset: JSONObject): JSONObject =
        dispatch("evaluate_scope", JSONObject().put("scope", scope).put("asset", asset))

    /**
     * Proxy validation. Only explicit, non-secret proxy parameters are sent
     * (enabled/scheme/host/port/username); credentials never enter the core
     * and never enter logs — they live in CredentialStore and are only
     * handed to the engine session in memory. UNKNOWN_OPERATION falls back
     * to the local shape check in ProxyPolicy.
     */
    suspend fun validateProxy(proxy: JSONObject): JSONObject =
        dispatch("validate_proxy", proxy)

    /**
     * LiveLink (paired backup) handshake: checks the peer supports the
     * scope2/catalog2 capabilities. UNKNOWN_OPERATION means the core (or
     * the peer path) does not know LiveLink yet: callers fail closed and
     * refuse transfers rather than assuming support.
     */
    suspend fun livelinkHandshake(capabilities: List<String> = listOf("scope2", "catalog2")): JSONObject =
        dispatch("livelink_handshake", JSONObject().put("capabilities", org.json.JSONArray(capabilities)))

    /**
     * LiveLink scope check for one transfer. The core answers whether the
     * scoped transfer requires receiver confirmation; per the paired-backup
     * contract every transfer requires confirmation (requires_confirmation
     * is true), and a Live Photo counts as a single media item.
     */
    suspend fun livelinkScope(scope: JSONObject, asset: JSONObject): JSONObject =
        dispatch("livelink_scope", JSONObject().put("scope", scope).put("asset", asset))

    /**
     * Legacy migration. The core action exists as a function in harbor_core
     * but may not be routed by dispatch yet; UNKNOWN_OPERATION is expected
     * until the central core wires it, and LegacyMigration then applies the
     * identical mapping locally. Never deletes or mutates source records.
     */
    suspend fun migrateLegacy(records: org.json.JSONArray): JSONObject =
        dispatch("migrate_legacy", JSONObject().put("records", records))

    /** Persists [tasks] to an explicit store file (used for the migrated copy). */
    suspend fun storeSaveTo(path: String, tasks: JSONObject): JSONObject =
        dispatch("store_save", JSONObject().put("path", path).put("tasks", tasks))

    /**
     * Best-effort mirror of a task event into the core log. The core only
     * redacts the event dict and returns the redacted copy (it writes
     * nothing); local persistence stays with Diagnostics. Redaction floor
     * (core log_event): filenames basename only, no full paths, no content
     * hashes, no plaintext accounts/passwords/tokens, no media bodies.
     * Never throws; never blocks the business flow.
     */
    suspend fun logEvent(name: String, taskId: String = "", attrs: JSONObject = JSONObject()): Boolean {
        return runCatching {
            val event = JSONObject().put("event", name).put("attrs", DiagnosticFields.clean(attrs))
            if (taskId.isNotEmpty()) event.put("task_id", taskId)
            val result = dispatch("log_event", JSONObject().put("event", event))
            result.optBoolean("ok")
        }.getOrDefault(false)
    }

    companion object {
        /**
         * Single mapping point from core states to the local Room projection.
         * The local vocabulary stays unchanged so UI, statistics and recovery
         * keep working while the core is the decision authority.
         */
        fun localState(coreState: String): String = when (coreState) {
            HarborCoreState.DEDUP_HIT -> "exists"
            HarborCoreState.DONE -> "done"
            HarborCoreState.FAILED -> "failed"
            HarborCoreState.CANCELLED -> "cancelled"
            HarborCoreState.PAUSED -> "paused"
            HarborCoreState.WAITING_NETWORK -> "waiting_network"
            HarborCoreState.WAITING_CONFIRMATION -> "waiting_confirmation"
            HarborCoreState.INTERRUPTED -> "interrupted"
            else -> coreState
        }

        /** decide_prepare reports a hit as {"exists": true, ...}. */
        fun isDedupHit(result: JSONObject): Boolean = result.optBoolean("exists")

        /**
         * Parses a batch_pause / batch_resume result into per-task outcomes.
         * Accepts a "results" array ([{task_id, ok, state, error}]) or a
         * "tasks" object ({task_id: {ok, state, error} / task dict}). Unknown
         * shapes yield an empty map; callers treat a missing entry as
         * "not mirrored, keep local state and log it", never as success.
         */
        fun parseBatchResults(result: JSONObject): Map<String, JSONObject> {
            val out = mutableMapOf<String, JSONObject>()
            if (!result.optBoolean("ok")) return out
            val array = result.optJSONArray("results")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val id = entry.optString("task_id")
                    if (id.isNotEmpty()) out[id] = entry
                }
                return out
            }
            val tasks = result.optJSONObject("tasks") ?: return out
            val keys = tasks.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                tasks.optJSONObject(id)?.let { out[id] = it }
            }
            return out
        }

        /** Scope errors that must never degrade into "include everything". */
        fun isUnknownScopeVersion(result: JSONObject): Boolean =
            !result.optBoolean("ok") && result.optString("error") in setOf(
                "UNKNOWN_SCOPE_VERSION", "INVALID_SCOPE", "UNSUPPORTED_SCOPE_VERSION",
                "SCOPE_VERSION_UNSUPPORTED"
            )
    }
}
