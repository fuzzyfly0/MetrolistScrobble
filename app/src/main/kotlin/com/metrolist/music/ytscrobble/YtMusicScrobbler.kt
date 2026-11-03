/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ytscrobble

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.constants.YtMusicScrobblePlaylistIdKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Resolves a track heard in the YouTube Music app to a YouTube Music song and appends it
 * to a dedicated local playlist, skipping anything that is already in it.
 */
@Singleton
class YtMusicScrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val mutex = Mutex()

    suspend fun scrobble(title: String, artist: String, durationMs: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { scrobbleLocked(title, artist, durationMs) }
                .onFailure { Timber.e(it, "YT Music scrobble failed for $title - $artist") }
        }
    }

    private suspend fun scrobbleLocked(title: String, artist: String, durationMs: Long) {
        val playlistId = ensurePlaylist()
        val existing = database.playlistSongsBlocking(playlistId)
        val titleKey = normalize(title)
        val artistKey = normalize(artist)

        val alreadyByName = existing.any { entry ->
            normalize(entry.song.song.title) == titleKey &&
                entry.song.artists.any { artistMatches(artistKey, it.name) }
        }
        if (alreadyByName) return

        val match = findSong(title, artist, durationMs) ?: run {
            Timber.i("No confident YT Music match for $title - $artist")
            return
        }
        if (existing.any { it.map.songId == match.id }) return

        database.insert(match.toMediaMetadata())
        val playlist = database.playlist(playlistId).first() ?: return
        database.addSongsToPlaylist(playlist, listOf(match.id to null))
    }

    private suspend fun ensurePlaylist(): String {
        val storedId = context.dataStore.data.first()[YtMusicScrobblePlaylistIdKey]
        if (storedId != null && database.playlist(storedId).first() != null) return storedId

        val playlist = PlaylistEntity(
            name = context.getString(R.string.yt_music_scrobble_playlist_name),
            bookmarkedAt = LocalDateTime.now(),
            isEditable = true,
        )
        database.insert(playlist)
        context.safeDataStoreEdit { it[YtMusicScrobblePlaylistIdKey] = playlist.id }
        return playlist.id
    }

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

    companion object {
        private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
        private val NON_WORD = Regex("""[^\p{L}\p{N} ]""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
