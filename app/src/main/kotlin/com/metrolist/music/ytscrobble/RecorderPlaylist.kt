/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ytscrobble

import com.metrolist.music.db.MusicDatabase

/**
 * Drops the oldest songs until at most [maxSongs] remain, then closes the gaps in the positions.
 * The oldest songs sit at the bottom when [newestFirst] is set and at the top otherwise.
 * A [maxSongs] of 0 means unlimited.
 */
suspend fun MusicDatabase.trimRecorderPlaylist(playlistId: String, maxSongs: Int, newestFirst: Boolean = false) {
    if (maxSongs <= 0) return
    withTransaction {
        val songs = playlistSongsBlocking(playlistId).let { if (newestFirst) it.reversed() else it }
        val excess = songs.size - maxSongs
        if (excess <= 0) return@withTransaction
        songs.take(excess).forEach { delete(it.map) }
        songs.drop(excess).let { if (newestFirst) it.reversed() else it }.forEachIndexed { index, song ->
            if (song.map.position != index) update(song.map.copy(position = index))
        }
    }
}

/** Flips the playlist so that switching the insert order keeps the newest songs together. */
suspend fun MusicDatabase.reverseRecorderPlaylist(playlistId: String) {
    withTransaction {
        playlistSongsBlocking(playlistId).asReversed().forEachIndexed { index, song ->
            if (song.map.position != index) update(song.map.copy(position = index))
        }
    }
}

/**
 * Moves a song that was heard again to where a newly recorded song would go, so the playlist
 * order always reflects when each song was last played.
 */
suspend fun MusicDatabase.moveRecorderSongToNewest(playlistId: String, songId: String, newestFirst: Boolean) {
    withTransaction {
        val songs = playlistSongsBlocking(playlistId)
        val target = songs.firstOrNull { it.map.songId == songId } ?: return@withTransaction
        val others = songs - target
        val reordered = if (newestFirst) listOf(target) + others else others + target
        reordered.forEachIndexed { index, song ->
            if (song.map.position != index) update(song.map.copy(position = index))
        }
    }
}

suspend fun MusicDatabase.clearRecorderPlaylist(playlistId: String) {
    withTransaction { clearPlaylist(playlistId) }
}
