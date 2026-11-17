package com.cccece.picharbor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup scope, decided by the shared core.
 *
 * The scope dict means the same on Android and iOS (stage-two contract):
 * mode/albums carry the folder selection, dateEnabled/from/to the optional
 * date window, photos/videos the type switches, ignoreSmallImages/minImageKb
 * the small-image filter, filterCache whether the locally cached media index
 * may be used as the evaluation input (the core still decides per asset).
 *
 * Android field mapping (no new settings are invented here):
 * - mode: "all" when [HarborSettings.allFolders], else "advanced" with
 *   albums = the selected folder keys (scope2 modes are only
 *   default/all/date/advanced; "folders" is not a mode).
 * - albums: the selected folder keys plus SAF tree URIs.
 * - dateEnabled/from/to: Android has no backup-scope date setting (the
 *   ViewModel fromDate/toDate only filter the photo list UI), so the scope
 *   carries dateEnabled=false and null bounds.
 * - photos: always true; videos: [HarborSettings.videos].
 * - ignoreSmallImages/minImageKb: no such setting exists on Android, so
 *   the scope carries false and the core default 100 — note minImageKb
 *   must be 10..1024 or the core rejects the whole scope as INVALID_SCOPE.
 * - filterCache: false — Android never flags assets as cached, so the
 *   core's cache filter has nothing to exclude.
 * - version: 2 (scope2). An asset evaluated against the scope carries
 *   media_kind, created_at (epoch seconds), size and albums ([folderKey]).
 */
object ScopePolicy {
    fun scopeJson(settings: HarborSettings): JSONObject {
        val albums = JSONArray()
        settings.folders.forEach { albums.put(it) }
        settings.trees.forEach { albums.put(it) }
        return JSONObject()
            .put("version", 2)
            .put("mode", if (settings.allFolders) "all" else "advanced")
            .put("albums", albums)
            .put("dateEnabled", false)
            .put("from", JSONObject.NULL)
            .put("to", JSONObject.NULL)
            .put("photos", true)
            .put("videos", settings.videos)
            .put("ignoreSmallImages", false)
            .put("minImageKb", 100)
            .put("filterCache", false)
    }

    /** Local scope check, used ONLY as the fallback when the core predates evaluate_scope. */
    fun localIncludes(settings: HarborSettings, media: MediaEntry): Boolean =
        settings.includes(media)

    sealed interface ScopeDecision {
        /** Core included the asset. */
        data object Included : ScopeDecision
        /** Core excluded the asset. */
        data object Excluded : ScopeDecision
        /**
         * The core rejected the scope as an unknown version. This must NEVER
         * degrade into "include everything": the caller must exclude the
         * asset (and surface that the scope is unsupported) instead.
         */
        data class DeniedUnknownScopeVersion(val error: String) : ScopeDecision
        /** Core action unavailable; local [HarborSettings.includes] decided. */
        data class LocalFallback(val included: Boolean) : ScopeDecision
    }

    /**
     * Asks the core whether [media] is in scope. The core is the authority
     * whenever it knows evaluate_scope; only UNKNOWN_OPERATION (or a core
     * that is entirely absent) falls back to the local check, and only an
     * unknown-version scope error denies outright.
     */
    suspend fun evaluate(core: HarborCore, settings: HarborSettings, media: MediaEntry): ScopeDecision {
        if (!core.available()) return ScopeDecision.LocalFallback(localIncludes(settings, media))
        val scope = scopeJson(settings)
        // "advanced" with no albums selected means nothing is selected:
        // exclude without asking the core (the core would reject the empty
        // scope as INVALID_SCOPE, which is a different signal).
        if (scope.optString("mode") == "advanced" && scope.optJSONArray("albums")?.length() == 0) {
            return ScopeDecision.Excluded
        }
        val asset = JSONObject()
            .put("local_id", media.id).put("filename", media.name)
            .put("size", media.size).put("created_at", media.captured / 1000)
            .put("media_kind", if (media.video) "video" else "photo")
            .put("albums", JSONArray().put(media.folderKey))
        val result = core.evaluateScope(scope, asset)
        if (result.optBoolean("ok")) {
            // The core answers {"in_scope": bool, "reason": str}.
            return if (result.optBoolean("in_scope")) ScopeDecision.Included else ScopeDecision.Excluded
        }
        if (HarborCore.isUnknownScopeVersion(result)) {
            return ScopeDecision.DeniedUnknownScopeVersion(result.optString("error"))
        }
        // UNKNOWN_OPERATION (core predates evaluate_scope) or any other core
        // failure: local check preserves the pre-unification behaviour.
        return ScopeDecision.LocalFallback(localIncludes(settings, media))
    }
}
