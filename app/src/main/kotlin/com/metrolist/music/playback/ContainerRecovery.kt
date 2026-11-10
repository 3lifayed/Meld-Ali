package com.metrolist.music.playback

import android.content.SharedPreferences
import androidx.media3.common.PlaybackException
import java.io.EOFException

internal fun isMp4ContainerFailure(error: PlaybackException): Boolean {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    val parsingFailed = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )
    val truncatedInput = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
        causes.any { it is EOFException }
    return (parsingFailed || truncatedInput) && causes.any { cause ->
        cause.stackTrace.any { it.className.startsWith("androidx.media3.extractor.mp4.") }
    }
}

internal fun shouldRecoverContainerFailure(error: PlaybackException, fromSavedAudio: Boolean): Boolean =
    isMp4ContainerFailure(error) || (fromSavedAudio && isMalformedContainerError(error))

internal fun isMalformedContainerError(error: PlaybackException): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        val stack = cause.stackTrace
        for (frame in stack) {
            val cls = frame.className
            val inExtractor = cls.contains("androidx.media3.extractor.mkv") ||
                cls.contains("androidx.media3.extractor.mp4") ||
                cls.contains("androidx.media3.extractor.ogg") ||
                cls.contains("androidx.media3.extractor.DefaultExtractorInput")
            if (inExtractor) {
                if (cause is ArrayIndexOutOfBoundsException ||
                    cause is IndexOutOfBoundsException ||
                    cause is NumberFormatException ||
                    cause is java.io.EOFException ||
                    cause is IllegalStateException
                ) {
                    return true
                }
                // Media3 ParserException for malformed EBML/MKV headers (issue #142:
                // "Invalid integer size: 78" from DefaultEbmlReader on a truncated
                // stream). Match by class name to avoid an extra direct dependency
                // on androidx.media3.common from here.
                if (cause.javaClass.name == "androidx.media3.common.ParserException") {
                    return true
                }
            }
        }
        cause = cause.cause
    }
    return false
}

internal class ContainerRecovery(private val preferences: SharedPreferences) {
    // Keep the budget after STATE_READY: the same bad file can fail again mid-song.
    private val attempts = object : LinkedHashMap<String, Int>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > 100
    }

    @Synchronized fun nextAttempt(mediaId: String): Int? {
        val count = attempts[mediaId] ?: 0
        if (count >= 2) return null
        return (count + 1).also { attempts[mediaId] = it }
    }

    @Synchronized fun quarantineSavedCopy(mediaId: String, downloadVersion: Long?) {
        if (downloadVersion == null) return
        preferences.edit().putLong(mediaId, downloadVersion).apply()
        attempts.putIfAbsent(mediaId, 0)
    }

    @Synchronized fun refreshSavedCopy(mediaId: String, downloadVersion: Long?) {
        if (!preferences.contains(mediaId)) return
        if (downloadVersion == preferences.getLong(mediaId, -1L)) {
            attempts.putIfAbsent(mediaId, 0)
        } else {
            // A replacement download can be used normally; only the failed copy is quarantined.
            preferences.edit().remove(mediaId).apply()
            attempts.remove(mediaId)
        }
    }

    @Synchronized fun isActive(mediaId: String) = mediaId in attempts

    @Synchronized fun cacheKey(mediaId: String, itag: Int): String? =
        attempts[mediaId]?.let { "container-retry:$mediaId:$it:$itag" }
}
