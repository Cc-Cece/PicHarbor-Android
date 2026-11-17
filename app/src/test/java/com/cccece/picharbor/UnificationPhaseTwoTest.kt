package com.cccece.picharbor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Stage-two pure-logic tests (no device, no Python, no network):
 * Live Photo pairing, proxy validation, scope JSON, batch result parsing,
 * legacy state mapping and the LiveLink scope descriptor. Core dispatches
 * themselves are covered by the gpmc-side harbor_core tests; these tests
 * pin the Android-side contracts (fallbacks included) so a regression in
 * the Kotlin wrappers is caught before CI.
 */
class UnificationPhaseTwoTest {
    private fun photo(id:String,name:String,captured:Long,folder:String="camera")=
        MediaEntry(id,name,"image/jpeg",1000,captured,captured,folder,"Camera")
    private fun video(id:String,name:String,captured:Long,folder:String="camera")=
        MediaEntry(id,name,"video/mp4",5000,captured,captured,folder,"Camera")

    @Test fun livePhotoPairsByStemFolderAndCaptureTime() {
        val p=photo("p1","IMG_001.jpg",1_000_000)
        val v=video("v1","IMG_001.mp4",1_002_000)
        val all=listOf(p,v,video("v2","IMG_002.mp4",1_002_000),photo("p2","IMG_001.jpg",1_000_000,"other"))
        assertEquals("v1",LivePhotoPairing.findPair(p,all)?.id)
        assertEquals("p1",LivePhotoPairing.findPhotoPartner(v,all)?.id)
        assertNull(LivePhotoPairing.findPair(p,all.filter{it.id!="v1"}))
        // Too far apart in time: not a pair.
        assertNull(LivePhotoPairing.findPair(p,listOf(p,video("v9","IMG_001.mp4",1_100_000))))
    }

    @Test fun livePhotoGroupDefaultsToOriginalPairing() {
        val p=photo("p1","IMG_001.jpg",1_000_000)
        val v=video("v1","IMG_001.mp4",1_001_000)
        val group=LivePhotoPairing.groupFor(p,listOf(p,v),LivePhotoPairing.ORIGINAL)
        assertNotNull(group)
        assertEquals("p1",group!!.pairKey)
        assertTrue(group.isLivePhoto)
        // No partner and no embedded motion: an ordinary photo, never a
        // forced pair (unverified Live Photos are not cleaned up either).
        assertNull(LivePhotoPairing.groupFor(p,listOf(p),LivePhotoPairing.ORIGINAL))
        assertNull(LivePhotoPairing.groupFor(p,listOf(p),LivePhotoPairing.MOTION,false))
        assertNotNull(LivePhotoPairing.groupFor(p,listOf(p),LivePhotoPairing.MOTION,true))
    }

    @Test fun livePhotoStemIgnoresDirectoriesAndExtensions() {
        assertEquals("IMG_001",LivePhotoPairing.stem("Camera/IMG_001.jpg"))
        assertEquals("IMG_001",LivePhotoPairing.stem("IMG_001.mp4"))
        assertEquals("noext",LivePhotoPairing.stem("noext"))
    }

    @Test fun proxyLocalValidationRejectsBadShapes() {
        assertNull(ProxyPolicy.validateLocally(ProxyConfig(true,"http","proxy.example",8080,"")))
        assertNull(ProxyPolicy.validateLocally(ProxyConfig(false,"http","",0,"")))
        assertEquals("PROXY_INVALID_SCHEME",ProxyPolicy.validateLocally(ProxyConfig(true,"ftp","h",80,"")))
        // The core only accepts http/https; SOCKS5 is rejected locally too.
        assertEquals("PROXY_INVALID_SCHEME",ProxyPolicy.validateLocally(ProxyConfig(true,"socks5","h",80,"")))
        assertEquals("PROXY_INVALID_HOST",ProxyPolicy.validateLocally(ProxyConfig(true,"http","",80,"")))
        assertEquals("PROXY_INVALID_HOST",ProxyPolicy.validateLocally(ProxyConfig(true,"http","a b",80,"")))
        assertEquals("PROXY_INVALID_PORT",ProxyPolicy.validateLocally(ProxyConfig(true,"http","h",0,"")))
        assertEquals("PROXY_INVALID_PORT",ProxyPolicy.validateLocally(ProxyConfig(true,"http","h",70000,"")))
        assertEquals("PROXY_INVALID_USERNAME",ProxyPolicy.validateLocally(ProxyConfig(true,"http","h",80,"a:b")))
    }

    @Test fun proxyUrlKeepsCredentialsOnlyInMemory() {
        val config=ProxyConfig(true,"http","proxy.example",8080,"user")
        assertEquals("http://user:secret@proxy.example:8080",ProxyPolicy.proxyUrl(config,"secret"))
        assertEquals("http://proxy.example:8080",ProxyPolicy.proxyUrl(config.copy(username=""),null))
        assertEquals("",ProxyPolicy.proxyUrl(null,"secret"))
        assertEquals("",ProxyPolicy.proxyUrl(config.copy(enabled=false),"secret"))
    }

    @Test fun scopeJsonCarriesFoldersVideosAndNoDateFilter() {
        val scope=ScopePolicy.scopeJson(HarborSettings(folders=setOf("@camera"),trees=setOf("tree://x"),videos=false))
        // scope2 modes are only default/all/date/advanced; a folder
        // selection is "advanced" with the folder keys as albums.
        assertEquals(2,scope.getInt("version"))
        assertEquals("advanced",scope.getString("mode"))
        assertEquals(false,scope.getBoolean("videos"))
        assertEquals(true,scope.getBoolean("photos"))
        assertEquals(false,scope.getBoolean("dateEnabled"))
        // minImageKb must be 10..1024 or the core rejects the whole scope.
        assertEquals(100,scope.getInt("minImageKb"))
        assertEquals(false,scope.getBoolean("filterCache"))
        assertTrue(scope.getJSONArray("albums").toString().contains("@camera"))
        assertEquals("all",ScopePolicy.scopeJson(HarborSettings(allFolders=true)).getString("mode"))
    }

    @Test fun unknownScopeVersionIsClassifiedButUnknownOperationIsNot() {
        assertTrue(HarborCore.isUnknownScopeVersion(JSONObject().put("ok",false).put("error","UNKNOWN_SCOPE_VERSION")))
        assertTrue(HarborCore.isUnknownScopeVersion(JSONObject().put("ok",false).put("error","INVALID_SCOPE")))
        assertFalse(HarborCore.isUnknownScopeVersion(JSONObject().put("ok",false).put("error","UNKNOWN_OPERATION")))
        assertFalse(HarborCore.isUnknownScopeVersion(JSONObject().put("ok",false).put("error","ENGINE_ERROR")))
        assertFalse(HarborCore.isUnknownScopeVersion(JSONObject().put("ok",true)))
    }

    @Test fun batchResultsParseBothShapes() {
        val fromArray=JSONObject().put("ok",true).put("results",JSONArray()
            .put(JSONObject().put("task_id","t1").put("ok",true).put("state","paused"))
            .put(JSONObject().put("task_id","t2").put("ok",false).put("error","INVALID_TRANSITION")))
        val parsed=HarborCore.parseBatchResults(fromArray)
        assertEquals("paused",parsed["t1"]?.optString("state"))
        assertFalse(parsed["t2"]!!.optBoolean("ok"))
        val fromTasks=JSONObject().put("ok",true).put("tasks",JSONObject().put("t3",JSONObject().put("ok",true).put("state","queued")))
        assertEquals("queued",HarborCore.parseBatchResults(fromTasks)["t3"]?.optString("state"))
        assertTrue(HarborCore.parseBatchResults(JSONObject().put("ok",false).put("error","ENGINE_ERROR")).isEmpty())
        assertTrue(HarborCore.parseBatchResults(JSONObject().put("ok",true)).isEmpty())
    }

    @Test fun legacyStateMappingMatchesCore() {
        assertEquals("done" to null,LegacyMigration.mapLegacyState("confirmed"))
        assertEquals("waiting_confirmation" to null,LegacyMigration.mapLegacyState("confirmation_pending"))
        assertEquals("interrupted" to null,LegacyMigration.mapLegacyState("uploading"))
        assertEquals("interrupted" to null,LegacyMigration.mapLegacyState("interrupted"))
        assertEquals("queued" to null,LegacyMigration.mapLegacyState("queued"))
        assertEquals("failed" to "UNKNOWN_LEGACY_STATE",LegacyMigration.mapLegacyState("bogus"))
        assertEquals("failed" to "UNKNOWN_LEGACY_STATE",LegacyMigration.mapLegacyState(null))
        val migrated=LegacyMigration.migrateLocally(JSONArray()
            .put(JSONObject().put("task_id","t1").put("state","confirmed"))
            .put(JSONObject().put("task_id","t2").put("state","bogus")))
        assertEquals("done",migrated.getJSONObject(0).getString("state"))
        assertEquals("failed",migrated.getJSONObject(1).getString("state"))
        assertEquals("UNKNOWN_LEGACY_STATE",migrated.getJSONObject(1).getString("error_code"))
        assertTrue(migrated.getJSONObject(0).getBoolean("cloud_confirmed"))
    }

    @Test fun liveLinkScopeAlwaysRequiresConfirmation() {
        val scope=LiveLink.scopeJson(2)
        assertEquals(2,scope.getInt("version"))
        assertEquals("all",scope.getString("mode"))
        assertEquals(2,scope.getInt("resource_count"))
        val asset=LiveLink.assetJson("id1","IMG_001.jpg",1000,"live_photo",1_700_000_000_000,"camera")
        assertEquals("live_photo",asset.getString("media_kind"))
        assertEquals(1_700_000_000L,asset.getLong("created_at"))
        assertEquals("camera",asset.getJSONArray("albums").getString(0))
    }
}
