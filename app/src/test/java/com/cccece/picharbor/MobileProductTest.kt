package com.cccece.picharbor

import org.junit.Assert.*
import org.junit.Test

class MobileProductTest {
    private fun job(id:String,automatic:Boolean=false,state:String="queued",archivedAt:Long=0,hidden:Boolean=false)=
        BackupJob(id,"account",1000,automatic,state=state,archivedAt=archivedAt,hidden=hidden)
    @Test fun archiveAndFiltersNeverExposeHiddenOrWrongSourceTasks() {
        val jobs=listOf(job("manual"),job("auto",true),job("done",state="completed"),job("archived",state="completed",archivedAt=10),job("hidden",hidden=true))
        assertEquals(listOf("manual"),ActivityPresentation.visible(jobs,"manual","unfinished",false).map{it.id})
        assertEquals(listOf("archived"),ActivityPresentation.visible(jobs,"all","completed",true).map{it.id})
    }
    @Test fun suspensionAndConfirmationRemainDistinctInProjection() {
        assertEquals("paused",HarborCore.localState("paused"))
        assertEquals("waiting_confirmation",HarborCore.localState("waiting_confirmation"))
        assertEquals("waiting_network",HarborCore.localState("waiting_network"))
    }
    @Test fun blueprintsHaveSafeNewInstallDefaults() {
        val settings=HarborSettings()
        assertTrue(settings.allFolders);assertFalse(settings.auto);assertTrue(settings.wifi)
        assertEquals("none",settings.albumMode);assertEquals("original",settings.quality)
        assertEquals(14,settings.archiveDays)
    }
    @Test fun matchingFilenameCannotInventALivePair() {
        val photo=MediaEntry("p","same.jpg","image/jpeg",1,1,1,"folder","folder")
        val video=photo.copy(id="v",name="same.mov",mime="video/quicktime")
        assertNull(LivePhotoPairing.findPair(photo,listOf(photo,video)))
        assertEquals(video.copy(pairingID="verified"),LivePhotoPairing.findPair(photo.copy(pairingID="verified"),listOf(video.copy(pairingID="verified"))))
    }
    @Test fun pairingMacMatchesDesktopProtocol() {
        val mac=LinkWire.mac("device","nonce","a".repeat(64),"123456")
        val expected=javax.crypto.Mac.getInstance("HmacSHA256").apply{init(javax.crypto.spec.SecretKeySpec("123456".toByteArray(),"HmacSHA256"))}
            .doFinal("picharbor-link-v1\npair\ndevice\nnonce\n${"a".repeat(64)}".toByteArray()).let(LinkWire::hex)
        assertEquals(expected,mac)
        assertFalse(LinkWire.ipv4("127.0.0.1@evil"));assertFalse(LinkWire.ipv4("192.168.001.1"))
        assertTrue(LinkWire.ipv4("192.168.1.20"))
    }
}
