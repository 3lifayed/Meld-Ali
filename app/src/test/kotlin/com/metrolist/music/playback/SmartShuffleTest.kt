package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartShuffleTest {
    private fun track(id: String, artist: String = id) = ShuffleTrack(id, setOf(artist))

    @Test fun betterShuffleSpacesArtistsAndPrioritizesUnheardSongs() {
        val tracks = listOf(track("a1", "a"), track("a2", "a"), track("b1", "b"), track("c1", "c"), track("old", "d"))
        repeat(100) { seed ->
            val order = smartShuffleOrder(tracks, emptyList(), 0, listOf("old"), random = Random(seed)).toList()
            assertEquals(0, order.first())
            assertFalse(order[1] == 1)
            assertEquals(4, order.last())
            assertEquals(tracks.indices.toSet(), order.toSet())
        }
    }

    @Test fun queueExpansionAndModeChangesKeepPreviousAndForwardHistory() {
        val tracks = (0..15).map { track("song$it", "artist${it % 4}") }
        repeat(100) { seed ->
            val order = smartShuffleOrder(tracks, listOf(8, 3, 1), 6, listOf("song8", "song3"), random = Random(seed)).toList()
            assertEquals(listOf(8, 3, 1, 6), order.take(4))
            assertEquals(1, order[order.indexOf(6) - 1])
            assertEquals(6, order[order.indexOf(1) + 1])
            assertEquals(tracks.size, order.size)
            assertEquals(tracks.indices.toSet(), order.toSet())
        }
    }

    @Test fun smallLibrariesFavorTheLeastRecentlyPlayedTrack() {
        val tracks = listOf(track("current"), track("oldest"), track("newer"), track("newest"))
        repeat(20) { seed ->
            val order = smartShuffleOrder(tracks, emptyList(), 0, listOf("oldest", "newer", "newest", "current"), random = Random(seed))
            assertEquals(listOf(0, 1, 2, 3), order.toList())
        }
    }

    @Test fun discoveryVariesTheOrderWhenThePlaylistIsAlreadyInListeningHistory() {
        val tracks = (0..7).map { track("song$it") }
        val recent = tracks.map { it.id }
        val orders = (0..19).map { seed ->
            smartShuffleOrder(
                tracks, emptyList(), 0, recent,
                random = Random(seed), style = ShuffleStyle.DISCOVERY,
            ).toList()
        }

        assertTrue(orders.toSet().size > 1)
        orders.forEach { order ->
            assertEquals(0, order.first())
            assertEquals(tracks.indices.toSet(), order.toSet())
        }
    }

    @Test fun discoveryInterleavesAfterThreeOriginalsWithoutChangingPlayedHistory() {
        val tracks = (0..8).map { track("original$it") } + (0..2).map { track("recommended$it") }
        val ids = setOf("recommended0", "recommended1", "recommended2")
        val order = smartShuffleOrder(tracks, listOf(2, 0), 1, emptyList(), ids, random = Random(42)).toList()
        assertEquals(listOf(2, 0, 1), order.take(3))
        assertEquals(listOf(3, 7, 11), order.indices.filter { tracks[order[it]].id in ids })
        assertEquals(tracks.indices.toSet(), order.toSet())
    }

    @Test fun discoverySpacingContinuesAcrossRecommendationBatches() {
        val tracks = (0..9).map { track("song$it") }
        val ids = setOf("song3", "song7")
        val prefix = listOf(0, 1, 2, 3, 4)
        val order = smartShuffleOrder(tracks, prefix, 5, emptyList(), ids, random = Random(6)).toList()
        assertEquals(prefix + 5, order.take(6))
        assertEquals(7, order[7])
    }

    @Test fun shortQueuesAndMissingArtistsAlwaysProduceValidPermutations() {
        for (size in 1..100) {
            val tracks = List(size) { ShuffleTrack("same", emptySet()) }
            val order = smartShuffleOrder(tracks, listOf(-1, 999, 0, 0), size - 1, listOf("same"), random = Random(size))
            assertEquals(size, order.size)
            assertEquals(tracks.indices.toSet(), order.toSet())
        }
        assertTrue(smartShuffleOrder(emptyList(), emptyList(), -1, emptyList()).isEmpty())
    }

    @Test fun playlistFirstRemainsSupportedOutsideDiscovery() {
        val tracks = (0..9).map { track("song$it") }
        val order = smartShuffleOrder(tracks, emptyList(), 0, emptyList(), originalQueueSize = 5, playlistFirst = true).toList()
        assertEquals((0..4).toSet(), order.take(5).toSet())
    }

    @Test fun recommendationBudgetIsBoundedAndHasAShortQueueFallback() {
        assertEquals(0, discoverySlots(0, 0))
        assertEquals(1, discoverySlots(1, 0))
        assertEquals(1, discoverySlots(1, 4))
        assertEquals(3, discoverySlots(2, 7))
        assertEquals(4, discoverySlots(0, 10000))
    }

    @Test fun customFrequencyControlsEveryRecommendationSlot() {
        for (interval in listOf(1, 2, 3, 5, 10)) {
            val originals = List(interval * 3) { track("original$it") }
            val recommendations = List(3) { track("recommended$it") }
            val tracks = originals + recommendations
            val ids = recommendations.map { it.id }.toSet()
            val order = smartShuffleOrder(tracks, emptyList(), 0, emptyList(), ids, discoveryInterval = interval).toList()
            assertEquals((1..3).map { it * (interval + 1) - 1 }, order.indices.filter { tracks[order[it]].id in ids })
            assertEquals(tracks.indices.toSet(), order.toSet())
        }
    }

    @Test fun frequencyChangeKeepsHeardSongsAndUsesTheNewBudget() {
        val tracks = (0..11).map { track("song$it") }
        val ids = setOf("song3", "song10", "song11")
        val prefix = listOf(0, 1, 2, 3, 4)
        val order = smartShuffleOrder(tracks, prefix, 5, emptyList(), ids, discoveryInterval = 5).toList()
        assertEquals(prefix + 5, order.take(6))
        assertEquals(9, order.indices.first { it >= 6 && tracks[order[it]].id in ids })
        assertEquals(4, discoverySlots(1, 9, 1))
        assertEquals(2, discoverySlots(1, 9, 5))
        assertEquals(1, discoverySlots(1, 9, 10))
    }

    @Test fun restoredFrequencyIsClampedToTheSupportedRange() {
        assertEquals(1, validDiscoveryInterval(0))
        assertEquals(1, validDiscoveryInterval(-100))
        assertEquals(10, validDiscoveryInterval(Int.MAX_VALUE))
        assertEquals(3, validDiscoveryInterval(DEFAULT_DISCOVERY_INTERVAL))
        assertEquals(4, discoverySlots(1, 9, 0))
    }
}
