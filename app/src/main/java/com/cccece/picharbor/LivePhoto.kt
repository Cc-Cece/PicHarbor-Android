package com.cccece.picharbor

import android.content.ContentResolver
import android.net.Uri

/**
 * Live Photo / multi-resource pairing on Android.
 *
 * One media item, one core task (`media_kind=live_photo`):
 * - Default pairing is the original photo paired with its video
 *   ("original", joint submission), per the 2026-10-19 / C2 decision.
 *   The older Motion Photo single-file default ("motion") stays available
 *   as an explicit choice. An existing setting is never overwritten by an
 *   upgrade: [HarborSettings.livePhotoPairing] is only ever written when
 *   the user changes it; a missing key simply reads as "original".
 * - Photo/video pairs require the same explicit Apple content identifier.
 *   Filename, folder and capture time do not establish a pairing.
 * - Embedded JPEG motion is indexed by the shared parser, which validates
 *   declared XMP length against an appended video header. The file remains
 *   byte-for-byte intact during upload. [hasEmbeddedMotion] is only a legacy
 *   marker helper and is not the production evidence check.
 * - Joint and stepwise submission are explicit task strategies. Both retain
 *   one task and require the cloud to confirm the pair as one media item.
 *   A missing partner is reported, rather than silently backing up a still.
 * - PicHarbor never deletes local originals.
 */
object LivePhotoPairing {
    const val EMBEDDED_MOTION="@embedded-motion"
    const val ORIGINAL = "original"
    const val MOTION = "motion"
    const val PAIR_CAPTURE_WINDOW_MS = 5_000L
    private const val EMBEDDED_SCAN_BYTES = 512 * 1024

    data class LivePhotoGroup(
        val photo: MediaEntry,
        val video: MediaEntry?,
        val embeddedMotion: Boolean,
        val pairing: String
    ) {
        /** The photo side owns the pair: one core task, one confirmation. */
        val pairKey: String get() = photo.id
        val isLivePhoto: Boolean get() = video != null || embeddedMotion
    }

    /** Filename stem without its extension ("IMG_001.jpg" -> "IMG_001"). */
    fun stem(name: String): String {
        val base = name.substringAfterLast('/')
        return base.substringBeforeLast('.', base)
    }

    private val indexes=mutableListOf<Pair<java.lang.ref.WeakReference<List<MediaEntry>>,Map<String,List<MediaEntry>>>>()
    @Synchronized
    private fun index(all:List<MediaEntry>):Map<String,List<MediaEntry>> {
        indexes.removeAll{it.first.get()==null}
        indexes.firstOrNull{it.first.get()===all}?.let{return it.second}
        val value=all.filter{it.pairingID.isNotEmpty()&&it.pairingID!=EMBEDDED_MOTION}.groupBy{it.pairingID}
        if(indexes.size>=2)indexes.removeAt(0)
        indexes.add(java.lang.ref.WeakReference(all) to value)
        return value
    }
    /** Only explicit identifiers establish a pair; time breaks ties between copies. */
    fun findPair(photo: MediaEntry, all: List<MediaEntry>): MediaEntry? =
        (if(photo.pairingID.isEmpty()||photo.pairingID==EMBEDDED_MOTION)emptyList() else index(all)[photo.pairingID].orEmpty()).filter { it.video && it.id != photo.id }
            .minByOrNull { kotlin.math.abs(it.captured - photo.captured) }

    /** Finds the photo partner of a video (the symmetric lookup). */
    fun findPhotoPartner(video: MediaEntry, all: List<MediaEntry>): MediaEntry? =
        (if(video.pairingID.isEmpty())emptyList() else index(all)[video.pairingID].orEmpty()).filter { !it.video && it.id != video.id }
            .minByOrNull { kotlin.math.abs(it.captured - video.captured) }

    fun logical(all:List<MediaEntry>)=all.filter{!it.video||findPhotoPartner(it,all)==null}
    fun folders(media:MediaEntry,all:List<MediaEntry>):Set<String> = setOfNotNull(media.folderKey,
        (if(media.video)findPhotoPartner(media,all) else findPair(media,all))?.folderKey)
    fun backed(media:MediaEntry,all:List<MediaEntry>,records:Map<String,BackupRecord>):Boolean {
        val own=records[media.id]
        if(!BackupPolicy.matches(media,own))return false
        val partner=if(media.video)findPhotoPartner(media,all) else findPair(media,all)
        return partner==null||BackupPolicy.matches(partner,records[partner.id])&&own?.remoteKey==records[partner.id]?.remoteKey
    }

    /**
     * Groups [media] as a Live Photo when it qualifies, or null when it is
     * an ordinary single-resource item. [embeddedMotion] is the result of
     * [hasEmbeddedMotion] for this file (false when not checked / not a
     * Motion Photo JPEG). A video whose photo partner exists groups under
     * that photo, so either side can be the entry point and the pair is
     * still processed once.
     */
    fun groupFor(media: MediaEntry, all: List<MediaEntry>, pairing: String, embeddedMotion: Boolean = false): LivePhotoGroup? {
        if (!media.video) {
            val partner = findPair(media, all)
            if (partner != null) return LivePhotoGroup(media, partner, false, pairing)
            if (pairing == MOTION && embeddedMotion) return LivePhotoGroup(media, null, true, pairing)
            // "original" pairing without a video partner is an ordinary photo;
            // an unverified Live Photo is never treated as paired.
            if (pairing == ORIGINAL && embeddedMotion) return LivePhotoGroup(media, null, true, pairing)
            return null
        }
        val partner = findPhotoPartner(media, all) ?: return null
        return LivePhotoGroup(partner, media, false, pairing)
    }

    /**
     * Cheap Motion Photo check: scans the start of the JPEG for the XMP
     * `MicroVideo` marker. Reads at most [EMBEDDED_SCAN_BYTES]; any I/O
     * failure means "not detected", never an error. The full file is never
     * loaded into memory.
     */
    fun hasEmbeddedMotion(resolver: ContentResolver, uri: String): Boolean {
        return runCatching {
            resolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val buffer = ByteArray(8192)
                val marker = "MicroVideo".toByteArray(Charsets.US_ASCII)
                var scanned = 0
                var tail = ByteArray(0)
                while (scanned < EMBEDDED_SCAN_BYTES) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    scanned += read
                    val chunk = tail + buffer.copyOf(read)
                    if (chunk.containsMarker(marker)) return true
                    tail = chunk.takeLast(marker.size - 1).toByteArray()
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    private fun ByteArray.containsMarker(marker: ByteArray): Boolean {
        if (size < marker.size) return false
        outer@ for (i in 0..size - marker.size) {
            for (j in marker.indices) if (this[i + j] != marker[j]) continue@outer
            return true
        }
        return false
    }
}
