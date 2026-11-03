/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ytscrobble

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.BundleCompat
import com.metrolist.music.constants.YtMusicScrobbleEnabledKey
import com.metrolist.music.constants.YtMusicScrobbleMinListenSecondsKey
import com.metrolist.music.constants.YtMusicScrobbleSourcesKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Watches the media session of the enabled music apps (YouTube Music by default) and hands
 * every track that was actually listened to (not skipped) to [YtMusicScrobbler].
 */
@AndroidEntryPoint
class YtMusicListenerService : NotificationListenerService() {
    @Inject lateinit var scrobbler: YtMusicScrobbler

    private val scope = CoroutineScope(SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val tracker = PlayTracker()
    private var sources = DEFAULT_SOURCES

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(pickController(controllers.orEmpty()))
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMetadata(metadata)

        override fun onPlaybackStateChanged(state: PlaybackState?) = onPlaybackState(state)

        override fun onSessionDestroyed() = attach(null)
    }

    private val thresholdCheck = Runnable { checkThreshold() }

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.Main) {
            dataStore.data
                .map { it[YtMusicScrobbleMinListenSecondsKey] ?: DEFAULT_MIN_LISTEN_SECONDS }
                .distinctUntilChanged()
                .collect { tracker.minListenMs = it * 1000L }
        }
        scope.launch(Dispatchers.Main) {
            dataStore.data
                .map { it[YtMusicScrobbleSourcesKey] ?: DEFAULT_SOURCES }
                .distinctUntilChanged()
                .collect {
                    sources = it
                    if (controller != null && controller?.packageName !in it) attach(null)
                    refreshSessions()
                }
        }
    }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, YtMusicListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            sessionManager = manager
            refreshSessions()
        } catch (e: SecurityException) {
            Timber.w(e, "Notification access missing for YT Music listener")
        }
    }

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        val component = ComponentName(this, YtMusicListenerService::class.java)
        runCatching { manager.getActiveSessions(component) }
            .onSuccess { attach(pickController(it)) }
    }

    // Prefers the enabled app that is playing right now, then the one already followed.
    private fun pickController(controllers: List<MediaController>): MediaController? {
        val enabled = controllers.filter { it.packageName in sources }
        return enabled.firstOrNull { it.isPlaying() }
            ?: enabled.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: enabled.firstOrNull()
    }

    private fun MediaController.isPlaying() = playbackState?.state == PlaybackState.STATE_PLAYING

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        attach(null)
    }

    // Media sessions of apps in another profile (e.g. a work profile) are not returned by
    // getActiveSessions, but their media notifications still reach this listener.
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in sources) return
        val token = BundleCompat.getParcelable(
            sbn.notification.extras,
            Notification.EXTRA_MEDIA_SESSION,
            MediaSession.Token::class.java,
        )
        if (token != null && token == controller?.sessionToken) return
        val fromToken = token?.let { runCatching { MediaController(this, it) }.getOrNull() }
        if (controller?.isPlaying() == true && fromToken?.isPlaying() != true) return
        if (fromToken?.metadata != null) {
            attach(fromToken)
        } else if (controller == null) {
            onNotificationFallback(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName in sources && controller == null) pause()
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
        onTrack(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }

    // Without a usable session, the notification text is the only source: title and artist,
    // no duration, and "ongoing" as the playing state (players make it dismissible on pause).
    private fun onNotificationFallback(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        onTrack(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.substringBefore(" • "),
            durationMs = 0L,
        )
        if (sbn.isOngoing) resume() else pause()
    }

    private fun onTrack(title: String?, artist: String?, durationMs: Long) {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return
        if (tracker.title == title && tracker.artist == artist) return

        val wasPlaying = tracker.playingSince != null
        pause()
        tracker.reset(title, artist, durationMs)
        scope.launch { scrobbler.report(YtMusicScrobbler.Status.DETECTED, title, artist) }
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
        var minListenMs = DEFAULT_MIN_LISTEN_SECONDS * 1000L

        // Capped at 90 % of the track so that tracks shorter than the setting can still count.
        val thresholdMs: Long
            get() = if (durationMs > 0) minOf(minListenMs, durationMs * 9 / 10) else minListenMs

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

        // Music apps that can be recorded, with their display names. The regular YouTube app
        // is left out on purpose: most of what plays there is not music.
        val SOURCES = linkedMapOf(
            YT_MUSIC_PACKAGE to "YouTube Music",
            "com.spotify.music" to "Spotify",
            "com.apple.android.music" to "Apple Music",
            "com.amazon.mp3" to "Amazon Music",
            "deezer.android.app" to "Deezer",
            "com.aspiro.tidal" to "TIDAL",
            "com.soundcloud.android" to "SoundCloud",
        )
        val DEFAULT_SOURCES = setOf(YT_MUSIC_PACKAGE)
        const val DEFAULT_MIN_LISTEN_SECONDS = 30

        fun hasNotificationAccess(context: Context) =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
