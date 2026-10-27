package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleHistoryTest {
    @Test fun appendingTracksKeepsPreviousSongsInPlaybackOrder() {
        repeat(100) {
            val order = shuffleOrderKeepingHistory(listOf(4, 1), 3, 9).toList()
            assertEquals(listOf(4, 1, 3), order.take(3))
            assertEquals((0 until 9).toSet(), order.toSet())
            assertEquals(9, order.size)
            assertEquals(1, order[order.indexOf(3) - 1])
            assertEquals(4, order[order.indexOf(1) - 1])
        }
    }

    @Test fun emptyHistoryKeepsCurrentFirst() {
        assertEquals(2, shuffleOrderKeepingHistory(emptyList(), 2, 5).first())
        assertEquals(0, shuffleOrderKeepingHistory(emptyList(), 0, 0).size)
    }

    @Test fun staleAndDuplicateHistoryCannotCorruptThePermutation() {
        val order = shuffleOrderKeepingHistory(listOf(-1, 2, 2, 3, 99), 3, 5).toList()
        assertEquals(listOf(2, 3), order.take(2))
        assertEquals(5, order.size)
        assertEquals((0 until 5).toSet(), order.toSet())
    }
}
