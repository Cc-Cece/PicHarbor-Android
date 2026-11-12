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
 * {"ok": false, "error": "ENGINE_ERROR"} and the caller falls back to the
 * legacy direct-call path (marked deprecated-by-unification at the call
 * site). The core module is resolved as `gpmc.harbor_core` (the sync target
 * of tools/sync_gpmc.py) with a top-level `harbor_core` fallback.
 * Error text for the UI reuses Messages.error(code) from Engine.kt.
 */
class HarborCore(private val storePath: String = "") {
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

    suspend fun dispatch(action: String, payload: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val module = resolveModule()
            ?: return@withContext JSONObject().put("ok", false).put("error", "ENGINE_ERROR")
        try {
            val raw = Python.getInstance().getModule(module)
                .callAttr("dispatch_json", action, payload.toString()).toString()
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "ENGINE_ERROR")
        }
    }

    /** Persists one task dict into the cached store file. Never throws. */
    private suspend fun persist(task: JSONObject) {
        if (storePath.isEmpty()) return
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
     * decide_cleanup judges only whether the cloud-confirmation evidence is
     * sufficient to guide the user to Google Photos' official free-up-space.
     * It is NOT a permit to delete local originals: PicHarbor no longer
     * deletes local assets itself (REQUIREMENTS.md 2026-10-19; Yuto decision
     * 2026-11-13). The core must never treat its result as delete approval.
     * Evidence needs cloud_state and checked_at (epoch seconds, fresh within
     * 3600 s) plus a bool policy; only cloud_state == "confirmed_original"
     * with policy allowed returns {"eligible": true}.
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
            HarborCoreState.PAUSED, HarborCoreState.WAITING_NETWORK,
            HarborCoreState.INTERRUPTED, HarborCoreState.WAITING_CONFIRMATION -> "queued"
            else -> coreState
        }

        /** decide_prepare reports a hit as {"exists": true, ...}. */
        fun isDedupHit(result: JSONObject): Boolean = result.optBoolean("exists")
    }
}
