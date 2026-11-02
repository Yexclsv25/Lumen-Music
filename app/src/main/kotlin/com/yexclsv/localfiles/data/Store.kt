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
    val plays = MutableStateFlow(readPlays())

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
        saveEdits(m)
    }

    private fun saveEdits(m: Map<String, TrackEdit>) {
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

    // ---------- play stats ----------
    fun recordPlay(key: String) {
        val m = plays.value.toMutableMap()
        val cur = m[key]
        m[key] = PlayStat((cur?.count ?: 0) + 1, System.currentTimeMillis())
        savePlays(m)
    }

    fun resetPlays() = savePlays(emptyMap())

    private fun savePlays(m: Map<String, PlayStat>) {
        plays.value = m
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray().put(v.count).put(v.last)) }
        sp.edit().putString("plays", o.toString()).apply()
    }

    private fun readPlays(): Map<String, PlayStat> {
        val raw = sp.getString("plays", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, PlayStat>()
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                out[k] = PlayStat(a.getInt(0), a.getLong(1))
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---------- session (resume where you left off) ----------
    fun saveSession(keys: List<String>, index: Int, positionMs: Long) {
        sp.edit().putString("sessKeys", JSONArray(keys).toString()).putInt("sessIndex", index)
            .putLong("sessPos", positionMs).apply()
    }

    fun loadSession(): Triple<List<String>, Int, Long>? {
        val keys = readList("sessKeys")
        if (keys.isEmpty()) return null
        return Triple(keys, sp.getInt("sessIndex", 0), sp.getLong("sessPos", 0L))
    }

    // ---------- small generic prefs ----------
    fun getLong(k: String, d: Long = 0L): Long = sp.getLong(k, d)
    fun putLong(k: String, v: Long) { sp.edit().putLong(k, v).apply() }
    fun getStr(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun putStr(k: String, v: String) { sp.edit().putString(k, v).apply() }

    // ---------- backup / restore ----------
    fun exportJson(): String {
        val o = JSONObject()
        o.put("app", "Lumen")
        o.put("version", 1)
        o.put("favorites", JSONArray(favorites.value))
        o.put("playlists", JSONArray(sp.getString("playlists", "[]")))
        val ed = JSONObject()
        edits.value.forEach { (k, v) ->
            if (v.title != null || v.artist != null || v.album != null) {
                val j = JSONObject()
                v.title?.let { j.put("t", it) }
                v.artist?.let { j.put("a", it) }
                v.album?.let { j.put("al", it) }
                ed.put(k, j)
            }
        }
        o.put("edits", ed)
        val pl = JSONObject()
        plays.value.forEach { (k, v) -> pl.put(k, JSONArray().put(v.count).put(v.last)) }
        o.put("plays", pl)
        val s = settings.value
        o.put("settings", JSONObject()
            .put("accent", s.accentIndex).put("blur", s.blurDp.toDouble()).put("refraction", s.refraction)
            .put("dynamic", s.dynamicColor).put("haptics", s.haptics).put("minSeconds", s.minSeconds)
            .put("bg", s.bgIndex).put("hue", s.customHue.toDouble()).put("tint", s.tintPct)
            .put("autoUpdate", s.autoUpdate).put("skipSilence", s.skipSilence)
            .put("bass", s.bassBoost).put("loud", s.loudness).put("eqOn", s.eqOn).put("eqLevels", s.eqLevels))
        return o.toString(2)
    }

    fun importJson(text: String): Boolean = try {
        val o = JSONObject(text)
        if (o.optString("app") != "Lumen") throw IllegalArgumentException("Not a Lumen backup")
        o.optJSONArray("favorites")?.let { a ->
            val set = LinkedHashSet<String>()
            for (i in 0 until a.length()) set.add(a.getString(i))
            favorites.value = set
            writeList("favs", set)
        }
        o.optJSONArray("playlists")?.let { arr ->
            val list = List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                val t = p.optJSONArray("tracks") ?: JSONArray()
                Playlist(p.getString("id"), p.getString("name"), List(t.length()) { t.getString(it) })
            }
            savePlaylists(list)
        }
        o.optJSONObject("edits")?.let { eo ->
            val m = HashMap<String, TrackEdit>()
            eo.keys().forEach { k ->
                val j = eo.getJSONObject(k)
                m[k] = TrackEdit(
                    if (j.has("t")) j.getString("t") else null,
                    if (j.has("a")) j.getString("a") else null,
                    if (j.has("al")) j.getString("al") else null,
                    edits.value[k]?.cover
                )
            }
            edits.value.forEach { (k, v) -> if (k !in m && v.cover != null) m[k] = TrackEdit(cover = v.cover) }
            saveEdits(m)
        }
        o.optJSONObject("plays")?.let { po ->
            val m = HashMap<String, PlayStat>()
            po.keys().forEach { k -> val a = po.getJSONArray(k); m[k] = PlayStat(a.getInt(0), a.getLong(1)) }
            savePlays(m)
        }
        o.optJSONObject("settings")?.let { j ->
            update { d ->
                d.copy(
                    accentIndex = j.optInt("accent", d.accentIndex),
                    blurDp = j.optDouble("blur", d.blurDp.toDouble()).toFloat(),
                    refraction = j.optBoolean("refraction", d.refraction),
                    dynamicColor = j.optBoolean("dynamic", d.dynamicColor),
                    haptics = j.optBoolean("haptics", d.haptics),
                    minSeconds = j.optInt("minSeconds", d.minSeconds),
                    bgIndex = j.optInt("bg", d.bgIndex),
                    customHue = j.optDouble("hue", d.customHue.toDouble()).toFloat(),
                    tintPct = j.optInt("tint", d.tintPct),
                    autoUpdate = j.optBoolean("autoUpdate", d.autoUpdate),
                    skipSilence = j.optBoolean("skipSilence", d.skipSilence),
                    bassBoost = j.optInt("bass", d.bassBoost),
                    loudness = j.optInt("loud", d.loudness),
                    eqOn = j.optBoolean("eqOn", d.eqOn),
                    eqLevels = j.optString("eqLevels", d.eqLevels)
                )
            }
        }
        true
    } catch (e: Exception) {
        false
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
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("skipSilence", s.skipSilence)
            .putInt("bass", s.bassBoost)
            .putInt("loud", s.loudness)
            .putBoolean("eqOn", s.eqOn)
            .putString("eqLevels", s.eqLevels)
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
            tintPct = sp.getInt("tint", d.tintPct),
            autoUpdate = sp.getBoolean("autoUpdate", d.autoUpdate),
            skipSilence = sp.getBoolean("skipSilence", d.skipSilence),
            bassBoost = sp.getInt("bass", d.bassBoost),
            loudness = sp.getInt("loud", d.loudness),
            eqOn = sp.getBoolean("eqOn", d.eqOn),
            eqLevels = sp.getString("eqLevels", d.eqLevels) ?: ""
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
