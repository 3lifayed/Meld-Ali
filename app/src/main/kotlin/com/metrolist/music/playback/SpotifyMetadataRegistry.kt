/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.util.LruCache
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Maps a resolved YouTube video id back to the originating [SpotifyTrack] so the
 * player can recover Spotify metadata (ISRC, album, etc.) for a queued item.
 *
 * Backed by a bounded, thread-safe [LruCache] so the registry can't grow without
 * limit over a long-lived process (one entry per unique resolved track otherwise
 * lived forever). The eldest entries are evicted once the bound is reached.
 */
object SpotifyMetadataRegistry {
    private const val MAX_ENTRIES = 1024

    private val byYoutubeId = LruCache<String, SpotifyTrack>(MAX_ENTRIES)
    private val spotifyIds = LruCache<String, String>(MAX_ENTRIES)
    private val queueIdentities = LruCache<String, String>(MAX_ENTRIES)

    fun register(youtubeId: String, track: SpotifyTrack) {
        byYoutubeId.put(youtubeId, track)
        spotifyIds.put(youtubeId, track.id)
    }

    fun get(youtubeId: String): SpotifyTrack? = byYoutubeId.get(youtubeId)
        ?.takeIf { it.id == getSpotifyId(youtubeId) }

    fun getSpotifyId(youtubeId: String): String? = queueIdentities.get(youtubeId) ?: spotifyIds.get(youtubeId)

    fun rememberIdentity(youtubeId: String, spotifyId: String) {
        // A background match for another version must not replace an explicit queue identity.
        if (spotifyId.isNotBlank()) queueIdentities.put(youtubeId, spotifyId)
    }

    fun invalidate(youtubeId: String) {
        byYoutubeId.remove(youtubeId)
        spotifyIds.remove(youtubeId)
        queueIdentities.remove(youtubeId)
    }

    fun clearAll() {
        byYoutubeId.evictAll()
        spotifyIds.evictAll()
        queueIdentities.evictAll()
    }
}
