/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ytscrobble

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.metrolist.music.constants.YtMusicScrobbleEnabledKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Watches the YouTube Music app's media session and hands every track that was actually
 * listened to (not skipped) to [YtMusicScrobbler].
 */
@AndroidEntryPoint
class YtMusicListenerService : NotificationListenerService() {
    @Inject lateinit var scrobbler: YtMusicScrobbler

    private val scope = CoroutineScope(SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val tracker = PlayTracker()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(controllers?.firstOrNull { it.packageName == YT_MUSIC_PACKAGE })
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMetadata(metadata)

        override fun onPlaybackStateChanged(state: PlaybackState?) = onPlaybackState(state)

        override fun onSessionDestroyed() = attach(null)
    }

    private val thresholdCheck = Runnable { checkThreshold() }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, YtMusicListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            sessionManager = manager
            attach(manager.getActiveSessions(component).firstOrNull { it.packageName == YT_MUSIC_PACKAGE })
        } catch (e: SecurityException) {
            Timber.w(e, "Notification access missing for YT Music listener")
        }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        attach(null)
    }

    override fun onDestroy() {
        onListenerDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    private fun attach(newController: MediaController?) {
        if (newController?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        pause()
        controller = newController
        newController?.let {
            it.registerCallback(controllerCallback, handler)
            onMetadata(it.metadata)
            onPlaybackState(it.playbackState)
        }
    }

    private fun onMetadata(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        if (title == null || artist == null) return
        if (tracker.title == title && tracker.artist == artist) return

        val wasPlaying = tracker.playingSince != null
        pause()
        tracker.reset(title, artist, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        if (wasPlaying) resume()
    }

    private fun onPlaybackState(state: PlaybackState?) {
        if (state?.state == PlaybackState.STATE_PLAYING) resume() else pause()
    }

    private fun resume() {
        if (tracker.title == null || tracker.scrobbled || tracker.playingSince != null) return
        tracker.playingSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(thresholdCheck)
        handler.postDelayed(thresholdCheck, (tracker.thresholdMs - tracker.playedMs).coerceAtLeast(0))
    }

    private fun pause() {
        handler.removeCallbacks(thresholdCheck)
        tracker.playingSince?.let { tracker.playedMs += SystemClock.elapsedRealtime() - it }
        tracker.playingSince = null
    }

    private fun checkThreshold() {
        pause()
        if (tracker.playedMs < tracker.thresholdMs) {
            resume()
            return
        }
        tracker.scrobbled = true
        val title = tracker.title ?: return
        val artist = tracker.artist ?: return
        val durationMs = tracker.durationMs
        scope.launch {
            if (dataStore.data.first()[YtMusicScrobbleEnabledKey] != true) return@launch
            scrobbler.scrobble(title, artist, durationMs)
        }
    }

    private class PlayTracker {
        var title: String? = null
        var artist: String? = null
        var durationMs = 0L
        var playedMs = 0L
        var playingSince: Long? = null
        var scrobbled = false

        // Half the track, capped at 30 seconds, so skipped tracks never count.
        val thresholdMs: Long
            get() = if (durationMs > 0) minOf(durationMs / 2, MAX_THRESHOLD_MS) else MAX_THRESHOLD_MS

        fun reset(title: String, artist: String, durationMs: Long) {
            this.title = title
            this.artist = artist
            this.durationMs = durationMs
            playedMs = 0
            playingSince = null
            scrobbled = false
        }
    }

    companion object {
        const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val MAX_THRESHOLD_MS = 30_000L

        fun hasNotificationAccess(context: Context) =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
