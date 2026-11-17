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
 * - A photo and a video pair when they share a filename stem, live in the
 *   same folder, and were captured within [PAIR_CAPTURE_WINDOW_MS]. A
 *   single JPEG can also carry an embedded Motion Photo video (XMP
 *   `GCamera:MicroVideo` marker near the start of the file); that is
 *   detected by [hasEmbeddedMotion] and counts as a live photo with one
 *   local file and two resources.
 * - The core decides per resource (one `decide_prepare` call with the
 *   asset's `resources` list and `remote_keys` keyed by role); joint
 *   submission uploads the missing resources and then makes ONE
 *   submitting -> confirming -> done pass for the pair; stepwise
 *   (separate core tasks per resource) is the fallback when the core does
 *   not know live_photo yet. Either way a Live Photo is confirmed as one
 *   media item, never per half.
 * - Unverified Live Photos are never cleaned up by PicHarbor: if no pair
 *   and no embedded motion can be confirmed, the item is processed as an
 *   ordinary photo and its local original is always kept.
 */
object LivePhotoPairing {
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

    /**
     * Finds the video partner of [photo]: same stem, same folder, captured
     * within the pairing window; the closest capture time wins. Pure over
     * the media list, so it is unit-testable without a device.
     */
    fun findPair(photo: MediaEntry, all: List<MediaEntry>): MediaEntry? =
        all.filter { it.video && it.id != photo.id && it.folderKey == photo.folderKey &&
            stem(it.name) == stem(photo.name) &&
            kotlin.math.abs(it.captured - photo.captured) <= PAIR_CAPTURE_WINDOW_MS }
            .minByOrNull { kotlin.math.abs(it.captured - photo.captured) }

    /** Finds the photo partner of a video (the symmetric lookup). */
    fun findPhotoPartner(video: MediaEntry, all: List<MediaEntry>): MediaEntry? =
        all.filter { !it.video && it.id != video.id && it.folderKey == video.folderKey &&
            stem(it.name) == stem(video.name) &&
            kotlin.math.abs(it.captured - video.captured) <= PAIR_CAPTURE_WINDOW_MS }
            .minByOrNull { kotlin.math.abs(it.captured - video.captured) }

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
