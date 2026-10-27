package com.metrolist.music.playback

internal fun shuffleOrderKeepingHistory(
    playedIndices: List<Int>,
    currentIndex: Int,
    totalCount: Int,
): IntArray {
    if (totalCount == 0) return intArrayOf()
    require(currentIndex in 0 until totalCount)
    val played = playedIndices.filter { it in 0 until totalCount && it != currentIndex }.distinct()
    val playedSet = played.toSet()
    val remaining = (0 until totalCount)
        .filter { it != currentIndex && it !in playedSet }
        .shuffled()
    return (played + currentIndex + remaining).toIntArray()
}
