package com.projectx.quest.runtime

object RecentChat {
    private const val CAPACITY = 64
    private const val DEFAULT_WINDOW_MS = 8_000L

    private data class Entry(val at: Long, val text: String)

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val lock = Any()

    fun record(message: String) {
        if (message.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(Entry(now, message))
        }
    }

    /** Newest buffered message matching [regex], searched newest-first over the whole buffer. */
    fun latestMatch(regex: Regex): MatchResult? {
        synchronized(lock) {
            for (i in buffer.indices.reversed()) {
                regex.find(buffer[i].text)?.let { return it }
            }
        }
        return null
    }

    fun containsRecent(needle: String, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        if (needle.isBlank()) return false
        val n = needle.lowercase()
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(lock) {
            for (e in buffer) {
                if (e.at >= cutoff && e.text.lowercase().contains(n)) return true
            }
        }
        return false
    }
}
