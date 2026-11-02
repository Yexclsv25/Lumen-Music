package com.yexclsv.localfiles.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore.Audio.Media as AM
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds music on the device: MediaStore + any files/folders the user added through the system picker. */
object MusicScanner {

    suspend fun scan(
        ctx: Context,
        extraFiles: List<String>,
        extraFolders: List<String>,
        minSeconds: Int
    ): List<Track> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Track>()
        try {
            queryMediaStore(ctx, minSeconds).forEach { out[it.key] = it }
        } catch (_: Exception) {
            // permission missing or provider unavailable
        }

        val uris = ArrayList<Uri>()
        extraFiles.forEach { uris.add(Uri.parse(it)) }
        extraFolders.forEach { f ->
            try {
                DocumentFile.fromTreeUri(ctx, Uri.parse(f))?.let { walk(it, uris, 0) }
            } catch (_: Exception) {
            }
        }
        for (u in uris) {
            if (out.containsKey(u.toString())) continue
            readFile(ctx, u)?.let { if (it.durationMs >= minSeconds * 1000L) out[it.key] = it }
        }
        out.values.toList()
    }

    private fun queryMediaStore(ctx: Context, minSeconds: Int): List<Track> {
        val list = ArrayList<Track>()
        val proj = arrayOf(AM._ID, AM.TITLE, AM.ARTIST, AM.ALBUM, AM.ALBUM_ID, AM.DURATION, AM.DATE_ADDED)
        val sel = "${AM.IS_MUSIC} != 0 AND ${AM.DURATION} >= ${minSeconds * 1000L}"
        ctx.contentResolver.query(AM.EXTERNAL_CONTENT_URI, proj, sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(AM._ID)
            val iTitle = c.getColumnIndexOrThrow(AM.TITLE)
            val iArtist = c.getColumnIndexOrThrow(AM.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(AM.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(AM.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(AM.DURATION)
            val iAdded = c.getColumnIndexOrThrow(AM.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                list.add(
                    Track(
                        uri = ContentUris.withAppendedId(AM.EXTERNAL_CONTENT_URI, id),
                        title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                        artist = clean(c.getString(iArtist), "Unknown artist"),
                        album = clean(c.getString(iAlbum), "Unknown album"),
                        durationMs = c.getLong(iDur),
                        albumId = c.getLong(iAlbumId),
                        dateAdded = c.getLong(iAdded),
                        fromMediaStore = true
                    )
                )
            }
        }
        return list
    }

    private fun clean(v: String?, fallback: String): String =
        if (v.isNullOrBlank() || v == "<unknown>") fallback else v

    private fun walk(dir: DocumentFile, out: MutableList<Uri>, depth: Int) {
        if (depth > 8) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) walk(f, out, depth + 1)
            else if (f.type?.startsWith("audio/") == true) out.add(f.uri)
        }
    }

    /** Reads tags straight from a file URI (used for picked files, folders and "Open with"). */
    fun readFile(ctx: Context, uri: Uri): Track? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val display = try {
                DocumentFile.fromSingleUri(ctx, uri)?.name?.substringBeforeLast('.')
            } catch (_: Exception) {
                null
            }
            Track(
                uri = uri,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: display ?: "Unknown title",
                artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown artist"),
                album = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown album"),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                albumId = -1L,
                dateAdded = System.currentTimeMillis() / 1000,
                fromMediaStore = false
            )
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }
}
