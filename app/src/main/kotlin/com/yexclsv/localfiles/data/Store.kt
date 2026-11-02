package com.yexclsv.localfiles.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Tiny SharedPreferences-backed store: favorites, playlists, added files/folders, settings. */
class Store private constructor(ctx: Context) {
    companion object {
        @Volatile
        private var inst: Store? = null

        /** One shared instance for the UI and the playback service (same process). */
        fun get(ctx: Context): Store =
            inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }

    private val sp = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    val favorites = MutableStateFlow<Set<String>>(readList("favs").toSet())
    val playlists = MutableStateFlow(readPlaylists())
    val settings = MutableStateFlow(readSettings())
    val edits = MutableStateFlow(readEdits())

    // ---------- favorites ----------
    fun toggleFavorite(key: String): Boolean {
        val cur = favorites.value
        val next = if (key in cur) cur - key else cur + key
        favorites.value = next
        writeList("favs", next)
        return key in next
    }

    // ---------- playlists ----------
    fun createPlaylist(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Untitled" }, emptyList())
        savePlaylists(playlists.value + p)
        return p
    }

    fun renamePlaylist(id: String, name: String) =
        savePlaylists(playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })

    fun deletePlaylist(id: String) = savePlaylists(playlists.value.filter { it.id != id })

    fun addToPlaylist(id: String, key: String) =
        savePlaylists(playlists.value.map { if (it.id == id && key !in it.keys) it.copy(keys = it.keys + key) else it })

    fun removeFromPlaylist(id: String, key: String) =
        savePlaylists(playlists.value.map { if (it.id == id) it.copy(keys = it.keys - key) else it })

    private fun savePlaylists(list: List<Playlist>) {
        playlists.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("tracks", JSONArray(it.keys)))
        }
        sp.edit().putString("playlists", arr.toString()).apply()
    }

    private fun readPlaylists(): List<Playlist> {
        val raw = sp.getString("playlists", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val t = o.optJSONArray("tracks") ?: JSONArray()
                Playlist(o.getString("id"), o.getString("name"), List(t.length()) { t.getString(it) })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- per-track edits (rename / cover) ----------
    fun setEdit(key: String, e: TrackEdit) {
        val m = edits.value.toMutableMap()
        if (e.isEmpty) m.remove(key) else m[key] = e
        edits.value = m
        val o = JSONObject()
        m.forEach { (k, v) ->
            val j = JSONObject()
            v.title?.let { j.put("t", it) }
            v.artist?.let { j.put("a", it) }
            v.album?.let { j.put("al", it) }
            v.cover?.let { j.put("c", it) }
            o.put(k, j)
        }
        sp.edit().putString("edits", o.toString()).apply()
    }

    private fun readEdits(): Map<String, TrackEdit> {
        val raw = sp.getString("edits", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, TrackEdit>()
            o.keys().forEach { k ->
                val j = o.getJSONObject(k)
                out[k] = TrackEdit(
                    if (j.has("t")) j.getString("t") else null,
                    if (j.has("a")) j.getString("a") else null,
                    if (j.has("al")) j.getString("al") else null,
                    if (j.has("c")) j.getString("c") else null
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---------- added files / folders ----------
    fun extraFiles(): List<String> = readList("files")
    fun extraFolders(): List<String> = readList("folders")

    fun addExtraFiles(keys: List<String>) = writeList("files", (extraFiles() + keys).distinct())
    fun addExtraFolder(key: String) = writeList("folders", (extraFolders() + key).distinct())
    fun removeExtraFile(key: String) = writeList("files", extraFiles() - key)
    fun clearExtras() {
        writeList("files", emptyList())
        writeList("folders", emptyList())
    }

    // ---------- settings ----------
    fun update(block: (Settings) -> Settings) {
        val s = block(settings.value)
        settings.value = s
        sp.edit()
            .putInt("accent", s.accentIndex)
            .putFloat("blur", s.blurDp)
            .putBoolean("refraction", s.refraction)
            .putBoolean("dynamic", s.dynamicColor)
            .putBoolean("haptics", s.haptics)
            .putInt("minSeconds", s.minSeconds)
            .putInt("bg", s.bgIndex)
            .putFloat("hue", s.customHue)
            .putInt("tint", s.tintPct)
            .apply()
    }

    private fun readSettings(): Settings {
        val d = Settings()
        return Settings(
            accentIndex = sp.getInt("accent", d.accentIndex),
            blurDp = sp.getFloat("blur", d.blurDp),
            refraction = sp.getBoolean("refraction", d.refraction),
            dynamicColor = sp.getBoolean("dynamic", d.dynamicColor),
            haptics = sp.getBoolean("haptics", d.haptics),
            minSeconds = sp.getInt("minSeconds", d.minSeconds),
            bgIndex = sp.getInt("bg", d.bgIndex),
            customHue = sp.getFloat("hue", d.customHue),
            tintPct = sp.getInt("tint", d.tintPct)
        )
    }

    // ---------- helpers ----------
    private fun readList(name: String): List<String> {
        val raw = sp.getString(name, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(name: String, v: Collection<String>) {
        sp.edit().putString(name, JSONArray(v).toString()).apply()
    }
}
