/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ytscrobble

import android.content.Context
import android.content.res.Configuration
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.constants.YtMusicScrobbleAutoDownloadKey
import com.metrolist.music.constants.YtMusicScrobbleLastStatusKey
import com.metrolist.music.constants.YtMusicScrobbleMaxSongsKey
import com.metrolist.music.constants.YtMusicScrobbleNewestFirstKey
import com.metrolist.music.constants.YtMusicScrobblePlaylistIdKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Resolves a track heard in one of the recorded music apps to a YouTube Music song and adds it
 * to a dedicated local playlist, skipping anything that is already in it.
 */
@Singleton
class YtMusicScrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
) {
    private val mutex = Mutex()

    suspend fun scrobble(title: String, artist: String, durationMs: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val status = runCatching { scrobbleLocked(title, artist, durationMs) }
                .onFailure { Timber.e(it, "YT Music scrobble failed for $title - $artist") }
                .getOrDefault(Status.FAILED)
            report(status, title, artist)
        }
    }

    suspend fun report(status: Status, title: String, artist: String) {
        context.safeDataStoreEdit { it[YtMusicScrobbleLastStatusKey] = "${status.name}	$title – $artist" }
    }

    private suspend fun scrobbleLocked(title: String, artist: String, durationMs: Long): Status {
        val playlistId = ensurePlaylist()
        val existing = database.playlistSongsBlocking(playlistId)
        val titleKey = normalize(title)
        val artistKey = normalize(artist)

        val prefs = context.dataStore.data.first()
        val newestFirst = prefs[YtMusicScrobbleNewestFirstKey] ?: false

        val byName = existing.firstOrNull { entry ->
            normalize(entry.song.song.title) == titleKey &&
                entry.song.artists.any { artistMatches(artistKey, it.name) }
        }
        if (byName != null) {
            database.moveRecorderSongToNewest(playlistId, byName.map.songId, newestFirst)
            return Status.MOVED
        }

        val match = findSong(title, artist, durationMs) ?: run {
            Timber.i("No confident YT Music match for $title - $artist")
            return Status.NO_MATCH
        }
        if (existing.any { it.map.songId == match.id }) {
            database.moveRecorderSongToNewest(playlistId, match.id, newestFirst)
            return Status.MOVED
        }

        database.insert(match.toMediaMetadata())
        val playlist = database.playlist(playlistId).first() ?: return Status.FAILED
        database.addSongsToPlaylist(playlist, listOf(match.id to null), prepend = newestFirst)
        database.trimRecorderPlaylist(playlistId, prefs[YtMusicScrobbleMaxSongsKey] ?: 0, newestFirst)
        if (prefs[YtMusicScrobbleAutoDownloadKey] == true) downloadUtil.download(match)
        return Status.ADDED
    }

    private suspend fun ensurePlaylist(): String {
        val storedId = context.dataStore.data.first()[YtMusicScrobblePlaylistIdKey]
        val stored = storedId?.let { database.playlist(it).first() }
        if (stored != null) {
            // Keep a default name in the app's current language; a name the user chose stays.
            val currentName = context.getString(R.string.yt_music_scrobble_playlist_name)
            if (stored.playlist.name != currentName && stored.playlist.name in playlistNames()) {
                database.update(stored.playlist.copy(name = currentName))
            }
            return stored.id
        }

        // After a backup restore or a playlist import the stored ID is gone, so pick up an
        // existing playlist with the recorder's name (in any shipped language) instead.
        val names = playlistNames()
        database.playlistsByNameAsc().first()
            .firstOrNull { it.playlist.isEditable && it.playlist.name in names }
            ?.let { existing ->
                context.safeDataStoreEdit { it[YtMusicScrobblePlaylistIdKey] = existing.id }
                return existing.id
            }

        val playlist = PlaylistEntity(
            name = context.getString(R.string.yt_music_scrobble_playlist_name),
            bookmarkedAt = LocalDateTime.now(),
            isEditable = true,
        )
        database.insert(playlist)
        context.safeDataStoreEdit { it[YtMusicScrobblePlaylistIdKey] = playlist.id }
        return playlist.id
    }

    private fun playlistNames(): Set<String> =
        (listOf(Locale.ENGLISH, Locale.GERMAN).map { locale ->
            val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
            context.createConfigurationContext(config).getString(R.string.yt_music_scrobble_playlist_name)
        } + context.getString(R.string.yt_music_scrobble_playlist_name)).toSet() + LEGACY_PLAYLIST_NAMES

    private suspend fun findSong(title: String, artist: String, durationMs: Long): SongItem? {
        val candidates = YouTube.search("$title $artist", YouTube.SearchFilter.FILTER_SONG)
            .getOrNull()
            ?.items
            ?.filterIsInstance<SongItem>()
            .orEmpty()

        val titleKey = normalize(title)
        val artistKey = normalize(artist)
        val durationSec = (durationMs / 1000).toInt()

        return candidates
            .mapNotNull { song ->
                val songTitle = normalize(song.title)
                val titleScore = when {
                    songTitle == titleKey -> 2
                    songTitle.contains(titleKey) || titleKey.contains(songTitle) -> 1
                    else -> return@mapNotNull null
                }
                if (song.artists.none { artistMatches(artistKey, it.name) }) return@mapNotNull null
                val durationScore = when {
                    durationSec <= 0 || song.duration == null -> 0
                    abs(song.duration!! - durationSec) <= 3 -> 1
                    abs(song.duration!! - durationSec) > 10 -> return@mapNotNull null
                    else -> 0
                }
                song to titleScore + durationScore
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun artistMatches(artistKey: String, candidate: String): Boolean {
        val name = normalize(candidate)
        return name.isNotEmpty() && (artistKey.contains(name) || name.contains(artistKey))
    }

    private fun normalize(value: String) =
        value.lowercase()
            .replace(BRACKETS, " ")
            .replace(" - topic", " ")
            .replace(NON_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim()

    enum class Status { DETECTED, ADDED, MOVED, DUPLICATE, NO_MATCH, FAILED }

    companion object {
        // Default names the recorder playlist had in earlier versions.
        private val LEGACY_PLAYLIST_NAMES = setOf(
            "Played in YouTube Music",
            "Gehört in YouTube Music",
            "Listening history",
            "Hörverlauf",
        )
        private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
        private val NON_WORD = Regex("""[^\p{L}\p{N} ]""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
