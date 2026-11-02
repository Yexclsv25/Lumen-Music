package com.yexclsv.localfiles.data

/** Parses .lrc (time-stamped) or plain-text lyrics. */
object LyricsParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val meta = Regex("^\\[[a-zA-Z]+:.*]$")

    fun parse(raw: String): Lyrics {
        val synced = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (line in raw.replace("\r", "").split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) {
                plain.add(LyricLine(-1, ""))
                continue
            }
            if (meta.matches(t)) continue
            val matches = stamp.findAll(t).toList()
            if (matches.isEmpty()) {
                plain.add(LyricLine(-1, t))
                continue
            }
            val text = t.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = if (fracStr.isEmpty()) 0L else (fracStr.padEnd(3, '0').take(3)).toLong()
                synced.add(LyricLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
        return if (synced.size >= 2) Lyrics(synced.sortedBy { it.timeMs }, true)
        else Lyrics(plain.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }, false)
    }
}
