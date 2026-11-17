package com.cccece.picharbor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Handoff to Google Photos' official free-up-space. PicHarbor no longer
 * deletes local originals itself (iOS REQUIREMENTS.md 2026-10-19; Yuto
 * decision 2026-11-13): it only confirms backups and guides the user to
 * the official cleanup. There is deliberately no delete path here.
 *
 * Deep-link verification status:
 * - First step is the Universal Link https://photos.google.com/link/freeupspace,
 *   verified on iOS 0.2.12 (Google production iOS AASA lists
 *   EQHXZ8M8AV.com.google.photos for /link/freeupspace; user iPhone test
 *   reached the target page directly; 6 guessed googlephotos:// paths failed
 *   and were removed). No other googlephotos:// deep link is invented here.
 * - Android-side direct-to-page behaviour is NOT device-verified yet: the
 *   ACTION_VIEW intent is handed to the system, which should route it to
 *   Google Photos when installed. If the link does not resolve, the chain
 *   falls back to an explicit Photos launch, then the Play Store page, then
 *   manual steps. Opening the link does not mean cleanup is done, and
 *   PicHarbor does not change any task state because the link was opened.
 */
object FreeSpaceGuide {
    const val PHOTOS_PACKAGE = "com.google.android.apps.photos"
    const val FREE_UP_SPACE_URL = "https://photos.google.com/link/freeupspace"
    const val MANUAL_STEPS = "请手动打开 Google 相册 → 图库 → 释放空间（仅清理已确认备份的内容）"

    enum class Outcome { OPENED_LINK, OPENED_PHOTOS, OPENED_STORE, MANUAL_GUIDANCE }

    /**
     * One step of the fallback chain, as pure data. Local JVM unit tests run
     * against android.jar stubs, so constructing an Intent or calling
     * Uri.parse() there throws RuntimeException("Stub!") before any assertion
     * runs — that is why the chain is defined here as data and only
     * materialised into real Intents on device (see attempts()/toIntent()).
     * Intent.ACTION_* / CATEGORY_* are const Strings, so referencing them is
     * safe on the JVM. Every step is a view/launch action; none carries a
     * delete action.
     */
    data class AttemptSpec(
        val outcome: Outcome,
        val action: String,
        val data: String?,
        val pkg: String?,
        val categories: List<String> = emptyList(),
    )

    /** The verified official free-up-space link (iOS-verified; see header). */
    fun attemptSpecs(photosInstalled: Boolean): List<AttemptSpec> {
        val chain = mutableListOf(
            AttemptSpec(Outcome.OPENED_LINK, Intent.ACTION_VIEW, FREE_UP_SPACE_URL, null),
        )
        if (photosInstalled) chain += AttemptSpec(Outcome.OPENED_PHOTOS,
            Intent.ACTION_MAIN, null, PHOTOS_PACKAGE, listOf(Intent.CATEGORY_LAUNCHER))
        chain += AttemptSpec(Outcome.OPENED_STORE, Intent.ACTION_VIEW,
            "market://details?id=$PHOTOS_PACKAGE", null)
        chain += AttemptSpec(Outcome.OPENED_STORE, Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$PHOTOS_PACKAGE", null)
        return chain
    }

    /**
     * Ordered fallback chain, unit-testable without a Context. Built from
     * attemptSpecs() so the tested data and the launched Intents can never
     * drift apart.
     */
    fun attempts(photosInstalled: Boolean): List<Pair<Outcome, Intent>> =
        attemptSpecs(photosInstalled).map { it.outcome to it.toIntent() }

    private fun AttemptSpec.toIntent(): Intent {
        val intent = Intent(action)
        if (data != null) intent.data = Uri.parse(data)
        if (pkg != null) intent.setPackage(pkg)
        categories.forEach { intent.addCategory(it) }
        return intent
    }

    /** Tries the chain in order; returns what actually happened so the UI can say so. */
    fun launch(context: Context): Outcome {
        val installed = runCatching {
            context.packageManager.getLaunchIntentForPackage(PHOTOS_PACKAGE) != null
        }.getOrDefault(false)
        for ((outcome, intent) in attempts(installed)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return outcome
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: Exception) {
                continue
            }
        }
        return Outcome.MANUAL_GUIDANCE
    }
}
