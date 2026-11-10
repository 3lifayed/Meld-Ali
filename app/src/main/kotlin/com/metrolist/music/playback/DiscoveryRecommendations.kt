package com.metrolist.music.playback

import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.filterExplicit
import com.metrolist.music.playback.queues.filterVideoSongs

internal class DiscoveryHistory(private val preferences: SharedPreferences) {
    val spotifyIds: Set<String> get() = preferences.getStringSet("spotify_ids", emptySet()).orEmpty().toSet()
    val youtubeIds: Set<String> get() = preferences.getStringSet("youtube_ids", emptySet()).orEmpty().toSet()

    fun record(items: List<MediaItem>) {
        if (items.isEmpty()) return
        preferences.edit()
            .putStringSet("spotify_ids", spotifyIds + items.mapNotNull { it.metadata?.spotifyTrackId })
            .putStringSet("youtube_ids", youtubeIds + items.map { it.mediaId })
            .apply()
    }
}

internal suspend fun collectDiscoveryRecommendations(
    queue: Queue,
    excludedIds: Set<String>,
    limit: Int,
    hideExplicit: Boolean,
    hideVideos: Boolean,
): List<MediaItem> {
    val additions = linkedMapOf<String, MediaItem>()
    // Empty or duplicate-heavy pages do not mean later candidates are exhausted.
    while (additions.size < limit && queue.hasNextPage()) {
        for (item in queue.nextPage().filterExplicit(hideExplicit).filterVideoSongs(hideVideos)) {
            if (item.mediaId !in excludedIds) additions.putIfAbsent(item.mediaId, item)
            if (additions.size >= limit) break
        }
    }
    return additions.values.toList()
}
