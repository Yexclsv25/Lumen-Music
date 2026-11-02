package com.yexclsv.localfiles

import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yexclsv.localfiles.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

data class PlayerUi(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val durationMs: Long = 0L
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store.get(app)

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val scanning = MutableStateFlow(false)
    val ui = MutableStateFlow(PlayerUi())
    val listState = MutableStateFlow(ListState())
    val detailId = MutableStateFlow<String?>(null)
    val sleepUntil = MutableStateFlow(0L)

    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val index = HashMap<String, Track>()
    private var raw: List<Track> = emptyList()
    private var restored = false
    private var playJob: Job? = null
    private var trackedKey: String? = null
    private val artBytes = HashMap<String, ByteArray>()
    private var artRequested: String? = null
    private var controller: MediaController? = null
    private var sleepJob: Job? = null

    // ---------------- derived lists ----------------
    val rows: Flow<List<TrackRow>> =
        combine(tracks, listState, store.favorites, ui, store.plays) { t, ls, favs, u, plays -> libraryRows(t, ls, favs, u, plays) }
            .flowOn(Dispatchers.Default)

    val detailRows: Flow<List<TrackRow>> =
        combine(tracks, store.playlists, store.favorites, ui, detailId) { t, pls, favs, u, id ->
            if (id == null) emptyList<TrackRow>() else {
                val byKey = t.associateBy { it.key }
                val keys = if (id == "fav") favs.toList() else pls.firstOrNull { it.id == id }?.keys.orEmpty()
                keys.mapNotNull { byKey[it] }.map { row(it, favs, u) }
            }
        }.flowOn(Dispatchers.Default)

    val playlistRows: Flow<List<PlaylistRow>> =
        combine(tracks, store.playlists, store.favorites) { t, pls, favs ->
            val byKey = t.associateBy { it.key }
            val out = ArrayList<PlaylistRow>()
            val f = favs.mapNotNull { byKey[it] }
            out += PlaylistRow("fav", "Favorites", f.size, f.firstOrNull(), true)
            pls.forEach { p ->
                val ts = p.keys.mapNotNull { byKey[it] }
                out += PlaylistRow(p.id, p.name, ts.size, ts.firstOrNull(), false)
            }
            out as List<PlaylistRow>
        }.flowOn(Dispatchers.Default)

    private fun row(t: Track, favs: Set<String>, u: PlayerUi): TrackRow {
        val cur = t.key == u.current?.key
        return TrackRow(t, cur, cur && u.isPlaying, t.key in favs)
    }

    private fun libraryRows(t: List<Track>, ls: ListState, favs: Set<String>, u: PlayerUi, plays: Map<String, PlayStat>): List<TrackRow> {
        var list = t
        if (ls.query.isNotBlank()) {
            val q = ls.query.trim().lowercase()
            list = list.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
            }
        }
        list = when (ls.filter) {
            LibraryFilter.ALL -> list
            LibraryFilter.FAVORITES -> list.filter { it.key in favs }
            LibraryFilter.RECENT -> list.sortedByDescending { it.dateAdded }.take(100)
            LibraryFilter.MOST_PLAYED -> list.filter { (plays[it.key]?.count ?: 0) > 0 }
                .sortedByDescending { plays[it.key]?.count ?: 0 }.take(100)
            LibraryFilter.HISTORY -> list.filter { plays.containsKey(it.key) }
                .sortedByDescending { plays[it.key]?.last ?: 0L }.take(100)
        }
        if (ls.filter == LibraryFilter.ALL || ls.filter == LibraryFilter.FAVORITES) {
            list = when (ls.sort) {
                SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                SortMode.ARTIST -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
                SortMode.ALBUM -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
                SortMode.ADDED -> list.sortedByDescending { it.dateAdded }
                SortMode.DURATION -> list.sortedByDescending { it.durationMs }
            }
        }
        return list.map { row(it, favs, u) }
    }

    // ---------------- connection ----------------
    init {
        connect()
        viewModelScope.launch { store.edits.collect { rebuild() } }
        scan()
    }

    private fun connect() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        sync()
                        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) saveSession()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (sleepUntil.value == Long.MAX_VALUE &&
                            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                        ) {
                            c.pause()
                            sleepUntil.value = 0L
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val chain = generateSequence<Throwable>(error) { it.cause }
                            .mapNotNull { it.message }.distinct().joinToString(" <- ").take(240)
                        val name = c.currentMediaItem?.mediaMetadata?.title ?: "this song"
                        errors.tryEmit("Can't play \"$name\" (${error.errorCodeName}): $chain")
                        if (c.hasNextMediaItem()) {
                            c.seekToNextMediaItem()
                            c.prepare()
                            c.play()
                        }
                    }
                })
                sync()
                tryRestore()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val cur = item?.let { index[it.mediaId] ?: fromItem(it) }
        cur?.let { ensureArtwork(it) }
        if (cur?.key != trackedKey) {
            trackedKey = cur?.key
            playJob?.cancel()
            val k = cur?.key
            if (k != null) playJob = viewModelScope.launch {
                delay(20_000)
                val u = ui.value
                if (u.current?.key == k && u.isPlaying) store.recordPlay(k)
            }
        }
        val state = c.playbackState
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: cur?.durationMs ?: 0L
        ui.value = PlayerUi(
            current = cur,
            isPlaying = c.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING),
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            speed = c.playbackParameters.speed,
            queueIndex = c.currentMediaItemIndex,
            queueSize = c.mediaItemCount,
            durationMs = dur
        )
    }

    private fun fromItem(i: MediaItem) = Track(
        uri = Uri.parse(i.mediaId),
        title = i.mediaMetadata.title?.toString() ?: "Unknown title",
        artist = i.mediaMetadata.artist?.toString() ?: "Unknown artist",
        album = i.mediaMetadata.albumTitle?.toString() ?: "",
        durationMs = 0L, albumId = -1L, dateAdded = 0L, fromMediaStore = false
    )

    private fun artKey(t: Track) = t.key + "|" + t.coverPath

    /** Changes whenever anything shown in the notification changes. */
    private fun signature(t: Track) =
        "${t.title}|${t.artist}|${t.album}|${t.coverPath}|${artBytes.containsKey(artKey(t))}"

    private fun toItem(t: Track): MediaItem {
        val art = when {
            t.coverPath != null -> Uri.fromFile(File(t.coverPath))
            t.fromMediaStore && t.albumId > 0 ->
                ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId)
            else -> null
        }
        val md = MediaMetadata.Builder()
            .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setDisplayTitle(t.title).setSubtitle(t.artist)
            .setArtworkUri(art)
            .setIsPlayable(true).setIsBrowsable(false)
            .setExtras(android.os.Bundle().apply { putString("sig", signature(t)) })
        if (t.durationMs > 0) md.setDurationMs(t.durationMs)
        artBytes[artKey(t)]?.let { md.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(t.key).setUri(t.uri).setMediaMetadata(md.build()).build()
    }

    /** Embeds a real bitmap so the notification/lock screen always has art, even for SAF files and custom covers. */
    private fun ensureArtwork(t: Track) {
        val k = artKey(t)
        if (artBytes.containsKey(k) || artRequested == k) return
        artRequested = k
        viewModelScope.launch {
            val bmp = ArtLoader.load(getApplication(), t, 512) ?: return@launch
            val bytes = withContext(Dispatchers.Default) {
                val out = java.io.ByteArrayOutputStream()
                bmp.soft().compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
            artBytes[k] = bytes
            pushMetadata()
        }
    }

    /** Pushes renamed titles / new covers into the live queue so the notification updates without a restart. */
    private fun pushMetadata() {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            val t = index[item.mediaId] ?: continue
            if (item.mediaMetadata.extras?.getString("sig") == signature(t)) continue
            c.replaceMediaItem(i, toItem(t))
        }
    }

    // ---------------- library ----------------
    fun scan() {
        viewModelScope.launch {
            scanning.value = true
            val s = store.settings.value
            val list = MusicScanner.scan(getApplication(), store.extraFiles(), store.extraFolders(), s.minSeconds)
            raw = list
            rebuild()
            scanning.value = false
        }
    }

    private fun rebuild() {
        val ed = store.edits.value
        val list = raw.map { t ->
            val e = ed[t.key] ?: return@map t
            t.copy(
                title = e.title ?: t.title,
                artist = e.artist ?: t.artist,
                album = e.album ?: t.album,
                coverPath = e.cover?.takeIf { File(it).exists() }
            )
        }
        index.clear()
        list.forEach { index[it.key] = it }
        tracks.value = list
        pushMetadata()
        sync()
        tryRestore()
    }

    // ---------- rename / cover (stored in the app, files stay untouched) ----------
    fun editTrack(key: String, title: String, artist: String, album: String) {
        val orig = raw.firstOrNull { it.key == key } ?: return
        fun pick(v: String, o: String) = v.trim().takeIf { it.isNotEmpty() && it != o }
        val cur = store.edits.value[key] ?: TrackEdit()
        store.setEdit(key, cur.copy(title = pick(title, orig.title), artist = pick(artist, orig.artist), album = pick(album, orig.album)))
    }

    fun resetEdits(key: String) {
        store.edits.value[key]?.cover?.let { File(it).delete() }
        store.setEdit(key, TrackEdit())
    }

    fun removeCover(key: String) {
        val cur = store.edits.value[key] ?: return
        cur.cover?.let { File(it).delete() }
        store.setEdit(key, cur.copy(cover = null))
    }

    fun setCover(key: String, uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { saveCover(key, uri) } ?: return@launch
            val cur = store.edits.value[key] ?: TrackEdit()
            cur.cover?.let { File(it).delete() }
            store.setEdit(key, cur.copy(cover = path))
        }
    }

    private fun saveCover(key: String, uri: Uri): String? = try {
        val ctx = getApplication<Application>()
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        var s = 1
        while (opts.outWidth / (s * 2) >= 1024 && opts.outHeight / (s * 2) >= 1024) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        }
        if (bmp == null) null else {
            val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
            val f = File(dir, "${key.hashCode().toUInt()}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            f.absolutePath
        }
    } catch (e: Exception) {
        null
    }

    fun addFiles(uris: List<Uri>) {
        store.addExtraFiles(uris.map { it.toString() })
        scan()
    }

    fun addFolder(uri: Uri) {
        store.addExtraFolder(uri.toString())
        scan()
    }

    fun removeExtra(key: String) {
        store.removeExtraFile(key)
        scan()
    }

    fun clearExtras() {
        store.clearExtras()
        scan()
    }

    fun playExternal(uri: Uri) {
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { MusicScanner.readFile(getApplication(), uri) } ?: return@launch
            index[t.key] = t
            play(listOf(t), 0)
        }
    }

    // ---------------- transport ----------------
    fun play(list: List<Track>, start: Int) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.setMediaItems(list.map(::toItem), start.coerceIn(0, list.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun shuffleAll(list: List<Track>) {
        if (list.isEmpty()) return
        controller?.shuffleModeEnabled = true
        play(list, Random.nextInt(list.size))
    }

    fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun position(): Long = controller?.currentPosition ?: 0L

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(s: Float) { controller?.setPlaybackSpeed(s) }

    fun addNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(t), 0) else c.addMediaItem(c.currentMediaItemIndex + 1, toItem(t))
    }

    fun addToQueue(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(t), 0) else c.addMediaItem(toItem(t))
    }

    /** Next tracks in the queue, as (queue index, track). */
    fun upNext(limit: Int = 40): List<Pair<Int, Track>> {
        val c = controller ?: return emptyList()
        val out = ArrayList<Pair<Int, Track>>()
        var i = c.currentMediaItemIndex + 1
        while (i < c.mediaItemCount && out.size < limit) {
            val mi = c.getMediaItemAt(i)
            out.add(i to (index[mi.mediaId] ?: fromItem(mi)))
            i++
        }
        return out
    }

    fun jumpTo(queueIndex: Int) {
        val c = controller ?: return
        c.seekTo(queueIndex, 0L)
        c.play()
    }

    fun toggleFavorite(key: String) = store.toggleFavorite(key)

    // ---------------- resume last session ----------------
    private fun saveSession() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val cur = c.currentMediaItemIndex
        val from = maxOf(0, cur - 20)
        val to = minOf(c.mediaItemCount, from + 300)
        val keys = (from until to).map { c.getMediaItemAt(it).mediaId }
        store.saveSession(keys, cur - from, c.currentPosition)
    }

    private fun tryRestore() {
        if (restored) return
        val c = controller ?: return
        if (raw.isEmpty()) return
        restored = true
        if (c.mediaItemCount > 0) return
        val (keys, idx, pos) = store.loadSession() ?: return
        val items = keys.mapNotNull { index[it] }.map(::toItem)
        if (items.isEmpty()) return
        val startKey = keys.getOrNull(idx)
        val start = items.indexOfFirst { it.mediaId == startKey }.coerceAtLeast(0)
        c.setMediaItems(items, start, pos)
        c.prepare()
    }

    // ---------------- sleep timer ----------------
    fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        sleepUntil.value = Long.MAX_VALUE
    }

    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            sleepUntil.value = 0L
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        sleepUntil.value = end
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            sleepUntil.value = 0L
        }
    }

    override fun onCleared() {
        saveSession()
        controller?.release()
        controller = null
        super.onCleared()
    }
}
