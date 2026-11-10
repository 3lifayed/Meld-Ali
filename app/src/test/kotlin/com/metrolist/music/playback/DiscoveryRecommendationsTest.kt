package com.metrolist.music.playback

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.Queue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DiscoveryRecommendationsTest {
    private fun item(id: String, explicit: Boolean = false) =
        MediaMetadata(id, id, emptyList(), 180, explicit = explicit).toMediaItem()

    private class Pages(private val pages: List<List<MediaItem>>) : Queue {
        var offset = 0
        override val preloadItem: MediaMetadata? = null
        override suspend fun getInitialStatus(): Queue.Status = error("Discovery must not rematch the playing song")
        override fun hasNextPage() = offset < pages.size
        override suspend fun nextPage() = pages[offset++]
    }

    @Test fun continuesPastDuplicatesAndEmptyMatchPages() = runBlocking {
        val queue = Pages(listOf(listOf(item("existing")), emptyList(), listOf(item("new"))))
        val result = collectDiscoveryRecommendations(queue, setOf("existing"), 1, false, false)
        assertEquals(listOf("new"), result.map { it.mediaId })
        assertEquals(3, queue.offset)
    }

    @Test fun filtersAndDeduplicatesBeforeFillingSlots() = runBlocking {
        val queue = Pages(listOf(listOf(item("explicit", true), item("a"), item("a")), listOf(item("b")), listOf(item("c"))))
        val result = collectDiscoveryRecommendations(queue, emptySet(), 2, true, false)
        assertEquals(listOf("a", "b"), result.map { it.mediaId })
        assertEquals(2, queue.offset)
    }

    @Test fun exhaustsUnavailableCandidatesWithoutLoopingOrReturningDuplicates() = runBlocking {
        val queue = Pages(listOf(emptyList(), listOf(item("existing"))))
        assertTrue(collectDiscoveryRecommendations(queue, setOf("existing"), 4, false, false).isEmpty())
        assertEquals(2, queue.offset)
    }

    @Test fun remembersSuggestionsAcrossSessionsAndDoesNotReplaceHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("discovery-history-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val first = MediaMetadata("first-audio", "First", emptyList(), 180, spotifyTrackId = "spotify-first").toMediaItem()
        DiscoveryHistory(preferences).record(listOf(first))
        val restored = DiscoveryHistory(preferences)
        assertEquals(setOf("spotify-first"), restored.spotifyIds)
        val queue = Pages(listOf(listOf(first, item("second-audio"))))
        val result = collectDiscoveryRecommendations(queue, restored.youtubeIds, 1, false, false)
        assertEquals(listOf("second-audio"), result.map { it.mediaId })
        restored.record(result)
        assertEquals(setOf("first-audio", "second-audio"), DiscoveryHistory(preferences).youtubeIds)
        assertEquals(setOf("spotify-first"), DiscoveryHistory(preferences).spotifyIds)
    }
}
