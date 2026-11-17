package com.cccece.picharbor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Legacy data / settings / credential / unfinished-task compatibility.
 *
 * Code-ready only, reversible by construction:
 * - Reads the old stores (the core store file's pre-unification tasks, and
 *   Room backup records) and asks the core `migrate_legacy` to map them.
 *   When the synced core predates that action (UNKNOWN_OPERATION) the
 *   identical mapping is applied locally by [migrateLocally].
 * - Writes the migrated tasks to a SEPARATE file
 *   (harbor-store-migrated.json next to the live store). The original store
 *   file and every Room row are left untouched, nothing is deleted, and no
 *   irreversible conversion is executed. Applying the migrated copy (if
 *   ever) is a future, explicit, user-approved step — not this code.
 * - Credential migration already exists separately
 *   ([CredentialStore.migrateLegacy]); settings profiles already migrate in
 *   [Preferences.activateAccount]. This object covers task records only.
 *
 * Package coexistence (stage-four decision, implemented in code):
 * applicationId and signing are NOT changed, so a new build installs over
 * the old one as an upgrade and the old data stays readable in place;
 * there is no forced package switch and no old-version exit step executed
 * here. Release switching and the old version's exit remain design-only
 * (see this file's header): they are a release decision for Yuto, not a
 * code action, and nothing in this file performs them.
 */
data class LegacyMigrationReport(
    val sourceTasks: Int,
    val sourceRecords: Int,
    val migratedTasks: Int,
    val migratedPath: String,
    val usedCoreAction: Boolean
)

object LegacyMigration {
    /**
     * Pre-unification state -> current state. Identical to
     * harbor_core.migrate_legacy: confirmed -> done,
     * confirmation_pending -> waiting_confirmation,
     * uploading / interrupted -> interrupted; a state that is already a
     * current state is kept; anything else -> failed + UNKNOWN_LEGACY_STATE.
     * Pure (String in, String out) so it is unit-testable.
     */
    fun mapLegacyState(state: String?): Pair<String, String?> = when (state) {
        "confirmed" -> "done" to null
        "confirmation_pending" -> "waiting_confirmation" to null
        "uploading", "interrupted" -> "interrupted" to null
        "queued", "preparing", "dedup_hit", "submitting", "confirming", "done",
        "paused", "failed", "waiting_network", "waiting_confirmation",
        "cancelled" -> state to null
        else -> "failed" to "UNKNOWN_LEGACY_STATE"
    }

    /** Local equivalent of harbor_core.migrate_legacy (same mapping). */
    fun migrateLocally(records: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val taskId = record.optString("task_id")
            if (taskId.isEmpty()) continue
            val (state, errorCode) = mapLegacyState(record.optString("state", ""))
            val task = JSONObject()
            record.keys().forEach { key -> task.put(key, record.get(key)) }
            task.put("state", state)
            task.put("error_code", errorCode ?: record.opt("error_code"))
            if (!task.has("asset") || task.isNull("asset")) task.put("asset", JSONObject())
            if (!task.has("attempts")) task.put("attempts", 0)
            if (!task.has("remote_key")) task.put("remote_key", JSONObject.NULL)
            if (!task.has("cloud_confirmed")) task.put("cloud_confirmed", state == "done")
            out.put(task)
        }
        return out
    }
}

class LegacyMigrationRunner(private val graph: AppGraph) {
    /**
     * Prepares the migration. Reads only; writes only the new migrated
     * copy. Returns the report; never throws (callers run it best-effort).
     */
    suspend fun prepare(): LegacyMigrationReport {
        val records = JSONArray()
        var sourceTasks = 0
        var sourceRecords = 0
        // Source 1: pre-unification tasks in the core store file.
        runCatching {
            val loaded = graph.harborCore.storeLoad()
            val tasks = loaded.optJSONObject("tasks")
            if (loaded.optBoolean("ok") && tasks != null) {
                val keys = tasks.keys()
                while (keys.hasNext()) {
                    val task = tasks.optJSONObject(keys.next()) ?: continue
                    records.put(task)
                    sourceTasks++
                }
            }
        }
        // Source 2: Room backup records (cloud-confirmed contents) that
        // predate core tasks. task_id is synthetic ("legacy:<mediaId>") and
        // only identifies the migrated copy, never a live task.
        runCatching {
            val account = graph.preferences.get().account
            if (account.isNotEmpty()) {
                for (record in graph.dao.backups(account)) {
                    val media = graph.dao.media(record.mediaId)
                    val asset = JSONObject()
                        .put("local_id", record.mediaId)
                        .put("filename", media?.name ?: record.mediaId)
                        .put("sha1", record.sha1).put("sha256", record.sha256)
                        .put("size", record.size)
                        .put("media_kind", if (media?.video == true) "video" else "photo")
                    records.put(JSONObject()
                        .put("task_id", "legacy:${record.mediaId}")
                        .put("state", "confirmed")
                        .put("asset", asset)
                        .put("remote_key", record.remoteKey))
                    sourceRecords++
                }
            }
        }
        val coreResult = runCatching { graph.harborCore.migrateLegacy(records) }.getOrNull()
        val migrated: JSONArray
        val usedCore: Boolean
        if (coreResult != null && coreResult.optBoolean("ok") && coreResult.has("tasks")) {
            migrated = coreResult.optJSONArray("tasks") ?: JSONArray()
            usedCore = true
        } else {
            // Core predates migrate_legacy (UNKNOWN_OPERATION) or failed:
            // apply the identical mapping locally. The source is untouched.
            migrated = LegacyMigration.migrateLocally(records)
            usedCore = false
        }
        val storePath = File(graph.app.filesDir, "runtime/harbor-store.json").absolutePath
        val migratedPath = File(File(storePath).parentFile, "harbor-store-migrated.json").absolutePath
        val tasksById = JSONObject()
        for (i in 0 until migrated.length()) {
            val task = migrated.optJSONObject(i) ?: continue
            tasksById.put(task.optString("task_id"), task)
        }
        runCatching { graph.harborCore.storeSaveTo(migratedPath, tasksById) }
        return LegacyMigrationReport(sourceTasks, sourceRecords, migrated.length(), migratedPath, usedCore)
    }
}
