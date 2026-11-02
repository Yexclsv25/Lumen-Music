package com.yexclsv.localfiles.data

import android.net.Uri

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long,
    val dateAdded: Long,
    val fromMediaStore: Boolean,
    val coverPath: String? = null,
    val trackNo: Int = 0
) {
    val key: String get() = uri.toString()
}

data class Playlist(val id: String, val name: String, val keys: List<String>)

data class TrackRow(
    val track: Track,
    val isCurrent: Boolean,
    val isPlaying: Boolean,
    val isFav: Boolean
)

data class PlaylistRow(
    val id: String,
    val name: String,
    val count: Int,
    val cover: Track?,
    val isFav: Boolean
)

enum class LibraryFilter { ALL, FAVORITES, RECENT, MOST_PLAYED, HISTORY }

data class PlayStat(val count: Int, val last: Long)

enum class SortMode(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    ADDED("Date added"),
    DURATION("Duration")
}

data class ListState(
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: SortMode = SortMode.TITLE
)

data class Settings(
    val accentIndex: Int = 0,
    val blurDp: Float = 12f,
    val refraction: Boolean = true,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val minSeconds: Int = 30,
    val bgIndex: Int = 0,
    val customHue: Float = 265f,
    val tintPct: Int = 50,
    val autoUpdate: Boolean = true,
    val skipSilence: Boolean = false,
    val bassBoost: Int = 0,
    val loudness: Int = 0,
    val eqOn: Boolean = false,
    val eqLevels: String = ""
)

/** Non-destructive edits stored in the app; the audio file itself is never touched. */
data class TrackEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: String? = null
) {
    val isEmpty get() = title == null && artist == null && album == null && cover == null
}

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)
