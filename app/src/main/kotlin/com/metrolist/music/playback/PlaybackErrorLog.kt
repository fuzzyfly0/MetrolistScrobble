/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the last [MAX_ENTRIES] playback errors in a small file inside the app, because the
 * system log on most phones only reaches back a few minutes and errors from a car trip are gone
 * long before the phone can be checked.
 */
object PlaybackErrorLog {
    private const val FILE_NAME = "playback_errors.jsonl"
    private const val MAX_ENTRIES = 200
    private const val MAX_TEXT = 300

    data class Entry(
        val time: String,
        val song: String,
        val mediaId: String,
        val errorCode: String,
        val message: String,
        val causes: List<String>,
        val streamClient: String?,
        val networkConnected: Boolean,
        val recovery: String,
    ) {
        fun toText() = buildString {
            appendLine("$time  $song ($mediaId)")
            appendLine("  $errorCode: $message")
            causes.forEach { appendLine("  caused by $it") }
            appendLine("  stream client: ${streamClient ?: "-"}, network: ${if (networkConnected) "connected" else "offline"}")
            append("  recovery: $recovery")
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _entries.value = runCatching {
            file(context).takeIf { it.exists() }?.readLines()?.mapNotNull(::parse).orEmpty()
        }.onFailure { Timber.e(it, "Failed to read playback error log") }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(
        context: Context,
        error: PlaybackException,
        mediaId: String?,
        song: String?,
        streamClient: String?,
        networkConnected: Boolean,
        recovery: String,
    ) {
        load(context)
        val causes = generateSequence(error.cause) { it.cause }
            .take(4)
            .map { "${it::class.java.simpleName}: ${it.message.orEmpty().take(MAX_TEXT)}" }
            .toList()
        val entry = Entry(
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            song = song ?: "-",
            mediaId = mediaId ?: "-",
            errorCode = error.errorCodeName,
            message = error.message.orEmpty().take(MAX_TEXT),
            causes = causes,
            streamClient = streamClient,
            networkConnected = networkConnected,
            recovery = recovery,
        )
        val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = updated
        runCatching { file(context).writeText(updated.joinToString("\n") { serialize(it) } + "\n") }
            .onFailure { Timber.e(it, "Failed to write playback error log") }
    }

    @Synchronized
    fun clear(context: Context) {
        _entries.value = emptyList()
        runCatching { file(context).delete() }
    }

    private fun serialize(entry: Entry) = JSONObject()
        .put("time", entry.time)
        .put("song", entry.song)
        .put("mediaId", entry.mediaId)
        .put("errorCode", entry.errorCode)
        .put("message", entry.message)
        .put("causes", entry.causes.joinToString("\u001f"))
        .put("streamClient", entry.streamClient ?: "")
        .put("network", entry.networkConnected)
        .put("recovery", entry.recovery)
        .toString()

    private fun parse(line: String): Entry? = runCatching {
        val json = JSONObject(line)
        Entry(
            time = json.getString("time"),
            song = json.getString("song"),
            mediaId = json.getString("mediaId"),
            errorCode = json.getString("errorCode"),
            message = json.getString("message"),
            causes = json.getString("causes").split("\u001f").filter { it.isNotEmpty() },
            streamClient = json.getString("streamClient").ifEmpty { null },
            networkConnected = json.getBoolean("network"),
            recovery = json.getString("recovery"),
        )
    }.getOrNull()
}
