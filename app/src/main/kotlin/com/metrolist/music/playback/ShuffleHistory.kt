package com.metrolist.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer

internal fun shuffleOrderKeepingHistory(
    playedIndices: List<Int>,
    currentIndex: Int,
    totalCount: Int,
    originalQueueSize: Int = totalCount,
    playlistFirst: Boolean = false,
): IntArray {
    if (totalCount == 0) return intArrayOf()
    require(currentIndex in 0 until totalCount)
    val played = playedIndices.filter { it in 0 until totalCount && it != currentIndex }.distinct()
    val playedSet = played.toSet()
    val remaining = (0 until totalCount)
        .filter { it != currentIndex && it !in playedSet }
        .shuffled()
        .let { if (playlistFirst) it.sortedBy { index -> index >= originalQueueSize } else it }
    return (played + currentIndex + remaining).toIntArray()
}

internal fun ExoPlayer.mergeFullQueueKeepingHistory(items: List<MediaItem>, playedIndices: List<Int>): List<Int> {
    val oldTimeline = currentTimeline
    val heardUids = playedIndices.map { oldTimeline.getWindow(it, Timeline.Window()).uid }
    val available = (0 until mediaItemCount).map { getMediaItemAt(it) }.groupingBy { it.mediaId }.eachCount().toMutableMap()
    val missing = items.filter { item ->
        val count = available[item.mediaId] ?: 0
        if (count > 0) { available[item.mediaId] = count - 1; false } else true
    }
    addMediaItems(missing)
    // Moving windows preserves the current stream and the original playlist order.
    items.forEachIndexed { target, item ->
        val from = (target until mediaItemCount).first { getMediaItemAt(it).mediaId == item.mediaId }
        if (from != target) moveMediaItem(from, target)
    }
    val timeline = currentTimeline
    val byUid = (0 until timeline.windowCount).associateBy { timeline.getWindow(it, Timeline.Window()).uid }
    return heardUids.mapNotNull { byUid[it] }
}
