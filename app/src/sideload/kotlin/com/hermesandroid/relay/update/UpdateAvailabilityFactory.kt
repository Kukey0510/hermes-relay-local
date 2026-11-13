package com.hermesandroid.relay.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri

/**
 * === update (sideload flavor): factory ===
 *
 * Backs [UpdateAvailabilitySource] onto the existing GitHub-releases
 * [UpdateChecker]. There is no in-app download/install on the sideload track:
 * [startUpdate] opens the APK asset (or release page) in the browser and the
 * status never advances past [UpdateStatus.Available]. Mirrors
 * `voice/VoiceBridgeIntentFactory`'s flavor-split factory pattern — same
 * function signature + package as the googlePlay flavor.
 */
fun createUpdateAvailabilitySource(context: Context): UpdateAvailabilitySource =
    GitHubUpdateAvailabilitySource(context.applicationContext)

private const val TAG = "SideloadUpdate"

private class GitHubUpdateAvailabilitySource(
    private val appContext: Context,
) : UpdateAvailabilitySource {

    // Sideload reports updates synchronously from [check]; there is no async
    // listener, so this is never invoked. Present for interface parity.
    override var onStatusChanged: ((UpdateStatus) -> Unit)? = null

    /** Resolved on [check] so [startUpdate] can route to the right URL. */
    @Volatile private var pending: UpdateStatus.Available? = null

    override suspend fun check(): UpdateStatus {
        // Self-hosted build: the bundled UpdateChecker probes the UPSTREAM
        // Codename-11/hermes-relay releases. Their version (1.19.x) is always
        // higher than this fork's (0.1.0), so the banner claimed "update
        // available" on every launch — and following it would install the
        // official package over this build. Updates ship through our own CI
        // releases, so this track reports UpToDate unconditionally.
        pending = null
        return UpdateStatus.UpToDate
    }

    override fun startUpdate(activity: Activity?): Boolean {
        val target = pending?.openUrl ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, target.toUri())
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            (activity ?: appContext).startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startUpdate (browser) failed", t)
            false
        }
    }

    /** No staged install on sideload — the system installer handles the APK. */
    override fun completeUpdate() = Unit

    override fun dispose() {
        onStatusChanged = null
        pending = null
    }
}

// === END update (sideload) ===
