package com.cccece.picharbor
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
class LinkScopeTest {
    private fun media(id:String,name:String="IMG_$id.jpg",folder:String="external|DCIM/Camera",bytes:Long=200000,time:Long=stamp("2026-11-01"))=
        MediaEntry(id,name,if(name.endsWith(".mov"))"video/quicktime" else "image/jpeg",bytes,time,time,folder,"Camera")
    private fun stamp(day:String)=LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Test fun dateRangeIsInclusiveAndSupportsEitherOpenEnd() {
        val entries=listOf(media("before",time=stamp("2026-10-31")),media("during",time=stamp("2026-11-01")+86399000),media("after",time=stamp("2026-11-02")))
        assertEquals(listOf("during","after"),LinkScope.parse("range:2026-11-01:").select(entries).map{it.id})
        assertEquals(listOf("before","during"),LinkScope.parse("range::2026-11-01").select(entries).map{it.id})
    }
    @Test fun collectionsDatesTypesAndSizeAreIntersected() {
        val scope=LinkScope(mode="advanced",albums=setOf("external|DCIM/Camera"),dateEnabled=true,from="2026-11-01",photos=true,videos=false,ignoreSmallImages=true)
        val entries=listOf(media("keep"),media("small",bytes=20),media("other",folder="external|Download"),media("old",time=stamp("2025-01-01")),media("vid",name="clip.mov"))
        assertEquals(listOf("keep"),scope.select(entries).map{it.id})
    }
    @Test fun livePairsAreKeptWholeWhenVideosAreDisabledAndSizeIsSmall() {
        val entries=listOf(media("photo",name="IMG_42.heic",bytes=20),media("video",name="IMG_42.mov",bytes=10))
        val scope=LinkScope(videos=false,ignoreSmallImages=true)
        assertEquals(2,scope.select(entries).size)
        assertEquals(1,LinkScope.logicalCount(scope.select(entries)))
        assertTrue(LinkScope(photos=false).select(entries).isEmpty())
    }
    @Test fun unknownSizeIsKeptUnknownDateIsExcludedAndCacheIsFiltered() {
        val entries=listOf(media("unknown",bytes=0),media("date",time=0),media("cache",folder="external|Pictures/.thumbnails"))
        val scope=LinkScope(dateEnabled=true,from="2026-01-01",ignoreSmallImages=true,filterCache=true)
        assertEquals(listOf("unknown"),scope.select(entries).map{it.id})
        assertEquals(1,scope.report(1,entries).getInt("unknownSize"))
        assertEquals(1,scope.report(1,entries).getInt("unknownDate"))
    }
    @Test fun renamedCollectionsKeepStableIdsAndDeletedCollectionsDoNotBroadenScope() {
        val scope=LinkScope(mode="advanced",albums=setOf("external|Pictures/One"))
        val sameNames=listOf(media("one",folder="external|Pictures/One"),media("two",folder="external|Pictures/Two"))
        assertEquals(listOf("one"),scope.select(sameNames).map{it.id})
        assertTrue(scope.select(sameNames.drop(1)).isEmpty())
        assertTrue(scope.select(emptyList()).isEmpty())
    }
    @Test fun manuallySelectedMediaDoesNotUseComputerScope() {
        val chosen=listOf(media("download",folder="external|Download",bytes=20))
        assertTrue(LinkScope(mode="default").select(chosen).isEmpty())
        assertEquals(chosen,LinkScope.parse("selected").select(chosen))
    }
    @Test fun unsupportedOrInvalidScopesNeverFallBackToEverything() {
        listOf("scope3:{}","range:2026-99-99:","scope2:{\"mode\":\"advanced\"}").forEach{wire->
            assertTrue(runCatching{LinkScope.parse(wire)}.isFailure)
        }
    }
}
