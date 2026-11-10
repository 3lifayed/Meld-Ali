package com.metrolist.music.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SmartShufflePlayerTest {
    @Test fun changingFrequencyKeepsCurrentPositionAndPreviousTrack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        val tracks = (0..9).map { ShuffleTrack("song$it", emptySet()) }
        val recommendations = setOf("song9")
        try {
            player.setMediaItems(tracks.map { MediaItem.Builder().setMediaId(it.id).setUri("https://example.invalid/${it.id}").build() })
            player.shuffleModeEnabled = true
            player.setShuffleOrder(DefaultShuffleOrder(smartShuffleOrder(tracks, listOf(0, 1), 2, emptyList(), recommendations), 1L))
            player.seekTo(2, 12345)
            assertEquals(9, player.nextMediaItemIndex)
            player.setShuffleOrder(DefaultShuffleOrder(smartShuffleOrder(tracks, listOf(0, 1), 2, emptyList(), recommendations, discoveryInterval = 5), 2L))
            assertEquals(12345L, player.currentPosition)
            assertEquals(2, player.currentMediaItemIndex)
            assertEquals(1, player.previousMediaItemIndex)
            val timeline = player.currentTimeline
            var index = player.currentMediaItemIndex
            repeat(2) {
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                org.junit.Assert.assertNotEquals(9, index)
            }
            assertEquals(9, timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true))
        } finally {
            player.release()
        }
    }

    @Test fun expandingAPlaylistKeepsCurrentPositionDuplicatesAndHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id").build()
        try {
            player.setMediaItems(listOf(item("c"), item("d"), item("suggestion")))
            player.shuffleModeEnabled = true
            player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(1, 0, 2), 1L))
            player.seekTo(0, 12345)
            val heard = player.mergeFullQueueKeepingHistory(listOf(item("a"), item("a"), item("b"), item("c"), item("d")), listOf(1))
            assertEquals(listOf("a", "a", "b", "c", "d", "suggestion"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals("c", player.currentMediaItem?.mediaId)
            assertEquals(12345L, player.currentPosition)
            assertEquals(listOf(4), heard)
            val tracks = (0 until player.mediaItemCount).map { ShuffleTrack(player.getMediaItemAt(it).mediaId, emptySet()) }
            player.setShuffleOrder(DefaultShuffleOrder(smartShuffleOrder(tracks, heard, player.currentMediaItemIndex, emptyList()), 2L))
            player.seekToPreviousMediaItem()
            assertEquals("d", player.currentMediaItem?.mediaId)
        } finally {
            player.release()
        }
    }

    @Test fun previousAndNextTraverseHeardSongsAfterRecommendationsAreAdded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        try {
            val tracks = (0..8).map { ShuffleTrack("song$it", setOf("artist${it % 3}")) }
            player.setMediaItems(tracks.take(6).map { MediaItem.Builder().setMediaId(it.id).setUri("https://example.invalid/${it.id}").build() })
            player.shuffleModeEnabled = true
            player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(2, 0, 4, 1, 5, 3), 1L))
            player.seekTo(4, 0)
            val played = listOf(2, 0)
            player.addMediaItems(tracks.drop(6).map { MediaItem.Builder().setMediaId(it.id).setUri("https://example.invalid/${it.id}").build() })
            player.setShuffleOrder(DefaultShuffleOrder(smartShuffleOrder(tracks, played, 4, emptyList(), setOf("song6", "song7", "song8")), 2L))
            assertEquals(0, player.previousMediaItemIndex)
            player.seekToPreviousMediaItem()
            assertEquals("song0", player.currentMediaItem?.mediaId)
            assertEquals(2, player.previousMediaItemIndex)
            player.seekToNextMediaItem()
            assertEquals("song4", player.currentMediaItem?.mediaId)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        } finally {
            player.release()
        }
    }
}
