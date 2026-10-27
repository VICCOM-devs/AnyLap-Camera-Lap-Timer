package app.anylap.platform

/** Small set of things the shared code needs from the operating system. */
expect object Platform {
    val name: String

    /** Monotonic clock in nanoseconds – the same clock camera frame timestamps use. */
    fun monotonicNanos(): Long

    /** Wall clock, epoch milliseconds. */
    fun nowMillis(): Long

    /** Private app storage for data files. */
    val dataDir: String

    /** Private app storage for session videos and other large files. */
    val mediaDir: String

    /** Temporary files for sharing. */
    val cacheDir: String

    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun writeBytes(path: String, bytes: ByteArray)
    fun readBytes(path: String): ByteArray?
    fun exists(path: String): Boolean
    fun delete(path: String)

    /** Local date/time text, e.g. pattern "EEE d MMM · HH:mm". */
    fun formatDate(epochMs: Long, pattern: String): String

    /** Days since epoch in the local time zone (for "today"/"yesterday"). */
    fun localDay(epochMs: Long): Long

    fun shareText(text: String, title: String)
    fun shareFile(path: String, mimeType: String, title: String)

    /** Camera screens: landscape, full screen, screen kept on. */
    fun setCameraMode(enabled: Boolean)

    /** A short beep (start lights). No-op where not available. */
    fun beep()
}

/** Spoken lap announcements. */
expect class Speaker() {
    fun say(text: String)
    fun shutdown()
}

fun joinPath(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"
