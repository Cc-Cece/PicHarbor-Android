package com.cccece.picharbor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Computer direct link (LiveLink / paired backup) on Android.
 *
 * The gate, not the transport: Android holds no peer-transfer
 * implementation for paired backup (that lives with the desktop paired
 * scope), so this object only wires the core state machine around a
 * transfer request and refuses to proceed unless every gate passes.
 *
 * Contract (paired-backup-scope, both clients):
 * - `livelink_handshake` validates the scope2/catalog2 capabilities.
 * - `livelink_scope` validates the transfer scope and answers
 *   requires_confirmation. EVERY transfer requires receiver confirmation;
 *   a Live Photo counts as ONE media item for that confirmation.
 * - No transfer happens without an explicit receiver confirmation, and a
 *   failed/unknown handshake or scope check fails closed (no transfer).
 * - UNKNOWN_OPERATION (core predates the LiveLink actions) also fails
 *   closed: the transfer is refused with reason CORE_ACTION_UNAVAILABLE,
 *   never assumed to be supported.
 */
object LiveLink {
    data class Handshake(val ok: Boolean, val scope2: Boolean, val catalog2: Boolean, val reason: String = "")
    data class ScopeCheck(val ok: Boolean, val inScope: Boolean, val requiresConfirmation: Boolean, val reason: String = "")
    sealed interface TransferGate {
        /** All gates passed and the receiver confirmed: the caller may hand the items to the paired transport. */
        data object Allowed : TransferGate
        /** Refused: [reason] explains which gate failed. Nothing was sent. */
        data class Refused(val reason: String) : TransferGate
    }

    /**
     * Scope descriptor for one transfer: a scope2 dict (mode "all" — the
     * item was already selected, the scope only needs to be valid) plus
     * the pair size for the record. The core normalises it and always
     * answers requires_confirmation=true on success.
     */
    fun scopeJson(resourceCount: Int, paired: Boolean = true): JSONObject =
        JSONObject()
            .put("version", 2)
            .put("mode", "all")
            .put("albums", JSONArray())
            .put("dateEnabled", false)
            .put("photos", true)
            .put("videos", true)
            .put("ignoreSmallImages", false)
            .put("minImageKb", 100)
            .put("filterCache", false)
            .put("resource_count", resourceCount)
            .put("paired", paired)

    /** Asset descriptor for a LiveLink scope check (same fields as scope evaluation). */
    fun assetJson(localId: String, filename: String, size: Long, mediaKind: String, capturedMs: Long, folderKey: String): JSONObject =
        JSONObject()
            .put("local_id", localId).put("filename", filename)
            .put("size", size).put("created_at", capturedMs / 1000)
            .put("media_kind", mediaKind)
            .put("albums", JSONArray().put(folderKey))

    suspend fun handshake(core: HarborCore): Handshake {
        if (!core.available()) return Handshake(false, false, false, "CORE_UNAVAILABLE")
        val result = core.livelinkHandshake()
        if (!result.optBoolean("ok")) {
            val missing = result.optJSONArray("missing")
            val reason = result.optString("error", "ENGINE_ERROR") +
                if (missing != null && missing.length() > 0) ": ${missing}" else ""
            return Handshake(false, false, false, reason)
        }
        // Success shape: {"ok": true, "missing": [], "capabilities": [...]}.
        val announced = result.optJSONArray("capabilities")
        val names = (0 until (announced?.length() ?: 0)).map { announced!!.getString(it) }.toSet()
        return Handshake(
            ok = true,
            scope2 = "scope2" in names,
            catalog2 = "catalog2" in names
        )
    }

    suspend fun checkScope(core: HarborCore, scope: JSONObject, asset: JSONObject): ScopeCheck {
        if (!core.available()) return ScopeCheck(false, false, true, "CORE_UNAVAILABLE")
        val result = core.livelinkScope(scope, asset)
        if (!result.optBoolean("ok")) {
            // Fail closed: an unchecked scope still requires confirmation,
            // and the transfer itself is refused by the caller.
            return ScopeCheck(false, false, true, result.optString("error", "ENGINE_ERROR"))
        }
        return ScopeCheck(
            ok = true,
            inScope = result.optBoolean("in_scope", false),
            // The paired-backup contract requires confirmation for every
            // transfer; the core's answer is recorded but never trusted to
            // skip confirmation, so this is always true.
            requiresConfirmation = true,
            reason = result.optString("reason", "")
        )
    }

    /**
     * Runs the full gate for one transfer. [onReceiverConfirmed] is invoked
     * only after handshake + scope pass, and must return true only when the
     * receiving side explicitly confirmed this transfer. Returns Allowed
     * only when every gate passed; the actual peer send is the caller's job
     * (no peer transport exists on Android yet).
     */
    suspend fun requestTransfer(
        core: HarborCore,
        scope: JSONObject,
        asset: JSONObject,
        onReceiverConfirmed: suspend () -> Boolean
    ): TransferGate {
        val shake = handshake(core)
        if (!shake.ok) return TransferGate.Refused(shake.reason.ifBlank { "HANDSHAKE_FAILED" })
        if (!shake.scope2 || !shake.catalog2) {
            return TransferGate.Refused("LIVELINK_CAPABILITY_MISSING")
        }
        val scopeCheck = checkScope(core, scope, asset)
        if (!scopeCheck.ok) return TransferGate.Refused(scopeCheck.reason.ifBlank { "SCOPE_CHECK_FAILED" })
        if (!scopeCheck.inScope) return TransferGate.Refused("LIVELINK_SCOPE_EXCLUDED")
        if (scopeCheck.requiresConfirmation) {
            val confirmed = runCatching { onReceiverConfirmed() }.getOrDefault(false)
            if (!confirmed) return TransferGate.Refused("RECEIVER_NOT_CONFIRMED")
        }
        return TransferGate.Allowed
    }
}
