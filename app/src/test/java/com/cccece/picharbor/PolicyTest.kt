package com.cccece.picharbor

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PolicyTest {
    private val media=MediaEntry("uri","test.jpg","image/jpeg",123,456,456,"camera","Camera")
    private val record=BackupRecord("a","uri","sha1","sha256","remote",123,456,"original","uploaded",1000)
    @Test fun changedMediaInvalidatesEvidence() {
        assertTrue(BackupPolicy.matches(media,record))
        assertFalse(BackupPolicy.matches(media.copy(size=124),record))
        assertFalse(BackupPolicy.matches(media.copy(modified=457),record))
        assertFalse(BackupPolicy.matches(media,record.copy(remoteKey="")))
        assertFalse(BackupPolicy.matches(media,null))
    }
    @Test fun cleanupIsAccountBoundAndExpires() {
        val proof=CleanupRecord("proof","a","uri",123,"sha256","remote",1000,"ready")
        assertTrue(BackupPolicy.cleanupFresh(proof,"a",2000))
        assertFalse(BackupPolicy.cleanupFresh(proof,"b",2000))
        assertFalse(BackupPolicy.cleanupFresh(proof,"a",999))
        assertFalse(BackupPolicy.cleanupFresh(proof,"a",301000))
        assertFalse(BackupPolicy.cleanupFresh(proof.copy(state="blocked"),"a",2000))
    }
    @Test fun cameraDefaultDoesNotIncludeScreenshotsOrSafLookalikes() {
        val settings=HarborSettings()
        assertTrue(settings.includes(media))
        assertFalse(settings.includes(media.copy(folderName="Screenshots")))
        assertFalse(settings.includes(media.copy(source="tree")))
        assertFalse(settings.copy(videos=false).includes(media.copy(mime="video/mp4")))
        assertTrue(settings.copy(folders=setOf("camera")).includes(media.copy(source="tree")))
    }
    @Test fun allFoldersIncludesFutureFoldersWithoutExplicitSelection() {
        val settings=HarborSettings(folders=emptySet(),allFolders=true)
        assertTrue(settings.includes(media.copy(folderKey="new-folder",folderName="New folder")))
        assertTrue(settings.includes(media.copy(folderKey="authorized-tree",folderName="SD",source="tree")))
        assertTrue(settings.folders.isEmpty())
        assertFalse(HarborSettings(folders=emptySet()).includes(media))
    }
    @Test fun allFoldersStillRespectsVideoPreference() {
        val settings=HarborSettings(allFolders=true,videos=false)
        val video=media.copy(mime="video/mp4",folderKey="new-folder")
        assertTrue(settings.includesFolder(video))
        assertFalse(settings.includes(video))
        assertTrue(settings.copy(videos=true).includes(video))
    }
    @Test fun disablingAllFoldersRestoresPreviousChoices() {
        val original=HarborSettings(folders=setOf("@camera","selected"))
        val restored=original.copy(allFolders=true).copy(allFolders=false)
        assertEquals(original,restored)
        assertTrue(restored.includes(media))
        assertTrue(restored.includes(media.copy(folderKey="selected",folderName="Selected")))
        assertFalse(restored.includes(media.copy(folderKey="new",folderName="New")))
    }
    @Test fun nextDailyCheckRollsToTomorrowAfterChosenTime() {
        val now=ZonedDateTime.of(2026,10,18,3,0,0,0,ZoneId.of("Asia/Shanghai"))
        assertEquals(23*3600000L,SchedulePolicy.nextDelay("02:00",now))
        assertEquals(3600000L,SchedulePolicy.nextDelay("04:00",now))
    }
    @Test fun daylightSavingGapDoesNotCrashOrScheduleInPast() {
        val now=ZonedDateTime.of(2026,3,8,1,0,0,0,ZoneId.of("America/New_York"))
        assertEquals(90*60000L,SchedulePolicy.nextDelay("02:30",now))
        assertTrue(SchedulePolicy.nextDelay("invalid",now)>0)
    }
}
