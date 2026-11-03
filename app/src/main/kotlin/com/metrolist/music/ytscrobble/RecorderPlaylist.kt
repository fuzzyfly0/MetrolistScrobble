/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ytscrobble

import com.metrolist.music.db.MusicDatabase

/**
 * Drops the oldest songs (lowest positions, since new songs are appended) until at most
 * [maxSongs] remain, then closes the gaps in the positions. A [maxSongs] of 0 means unlimited.
 */
suspend fun MusicDatabase.trimRecorderPlaylist(playlistId: String, maxSongs: Int) {
    if (maxSongs <= 0) return
    withTransaction {
        val songs = playlistSongsBlocking(playlistId)
        val excess = songs.size - maxSongs
        if (excess <= 0) return@withTransaction
        songs.take(excess).forEach { delete(it.map) }
        songs.drop(excess).forEachIndexed { index, song ->
            if (song.map.position != index) update(song.map.copy(position = index))
        }
    }
}

suspend fun MusicDatabase.clearRecorderPlaylist(playlistId: String) {
    withTransaction { clearPlaylist(playlistId) }
}
