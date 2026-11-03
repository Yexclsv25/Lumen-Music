package com.yexclsv.localfiles.data

/** Parses .lrc (time-stamped) or plain-text lyrics. */
object LyricsParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")
    private val meta = Regex("^\\[[a-zA-Z]+:.*]$")

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val f = m.groupValues[3]
        val frac = if (f.isEmpty()) 0L else f.padEnd(3, '0').take(3).toLong()
        return min * 60_000 + sec * 1000 + frac
    }

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
            // Remove every time tag from the text, including word-by-word <mm:ss.xx> tags.
            val text = wordTag.replace(stamp.replace(t, ""), "").replace(Regex("\\s+"), " ").trim()
            // Several stamps on one line normally mean a repeated chorus line. Stamps that sit
            // within 10 seconds of each other are start/end markers, so they must not repeat the line.
            var last = Long.MIN_VALUE
            for (tm in matches.map { toMs(it) }.sorted()) {
                if (last == Long.MIN_VALUE || tm - last >= 10_000) {
                    synced.add(LyricLine(tm, text))
                    last = tm
                }
            }
        }
        if (synced.size >= 2) {
            val cleaned = ArrayList<LyricLine>()
            for (l in synced.sortedBy { it.timeMs }) {
                val prev = cleaned.lastOrNull()
                // The same line twice in a row within 3 seconds is a duplicate entry, not a new line.
                if (prev != null && prev.text == l.text && l.timeMs - prev.timeMs < 3000) continue
                cleaned.add(l)
            }
            return Lyrics(cleaned, true)
        }
        val tidy = ArrayList<LyricLine>()
        for (l in plain) {
            if (l.text.isEmpty() && (tidy.isEmpty() || tidy.last().text.isEmpty())) continue
            tidy.add(l)
        }
        return Lyrics(tidy.dropLastWhile { it.text.isEmpty() }, false)
    }
}
