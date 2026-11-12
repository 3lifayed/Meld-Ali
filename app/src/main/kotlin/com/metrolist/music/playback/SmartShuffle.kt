package com.metrolist.music.playback

import kotlin.random.Random

internal const val DEFAULT_DISCOVERY_INTERVAL = 3
internal const val MAX_DISCOVERY_INTERVAL = 10

internal fun validDiscoveryInterval(value: Int): Int = value.coerceIn(1, MAX_DISCOVERY_INTERVAL)

enum class ShuffleStyle { RANDOM, BETTER, DISCOVERY }

internal data class ShuffleTrack(val id: String, val artists: Set<String>)

internal fun smartShuffleOrder(
    tracks: List<ShuffleTrack>,
    playedIndices: List<Int>,
    currentIndex: Int,
    recentIds: List<String>,
    recommendationIds: Set<String> = emptySet(),
    originalQueueSize: Int = tracks.size,
    playlistFirst: Boolean = false,
    random: Random = Random.Default,
    discoveryInterval: Int = DEFAULT_DISCOVERY_INTERVAL,
    style: ShuffleStyle = ShuffleStyle.BETTER,
): IntArray {
    if (tracks.isEmpty()) return intArrayOf()
    require(currentIndex in tracks.indices)
    val prefix = playedIndices.filter { it in tracks.indices && it != currentIndex }.distinct() + currentIndex
    val used = prefix.toSet()
    val remaining = tracks.indices.filter { it !in used }.shuffled(random).toMutableList()
    val ordered = prefix.toMutableList()
    val recent = recentIds.takeLast(50).withIndex().associate { it.value to it.index }
    val recommendations = mutableListOf<Int>()
    if (recommendationIds.isNotEmpty()) {
        remaining.filterTo(recommendations) { tracks[it].id in recommendationIds }
        remaining.removeAll(recommendations.toSet())
    }

    while (remaining.isNotEmpty()) {
        val lastArtists = ordered.takeLast(2).flatMap { tracks[it].artists }.toSet()
        val lastIds = ordered.takeLast(20).map { tracks[it].id }.toSet()
        var next = remaining.first()
        var bestScore = Int.MAX_VALUE
        for (index in remaining) {
            val track = tracks[index]
            // In Discovery, keep the preference for unheard tracks without sorting
            // recently heard tracks into the same history order every session.
            val recentPenalty = recent[track.id]?.let { rank ->
                if (style == ShuffleStyle.DISCOVERY) 100 else 100 + rank * 2
            } ?: 0
            val score = (if (playlistFirst && index >= originalQueueSize) 10000 else 0) +
                (if (track.id in lastIds) 1000 else 0) +
                recentPenalty +
                track.artists.count { it in lastArtists } * 10
            if (score < bestScore) {
                next = index
                bestScore = score
            }
            if (score == 0) break
        }
        ordered.add(next)
        remaining.remove(next)
    }

    if (recommendations.isEmpty()) return ordered.toIntArray()
    return interleaveDiscovery(ordered.take(prefix.size), ordered.drop(prefix.size), recommendations, tracks, recommendationIds, validDiscoveryInterval(discoveryInterval))
}

internal fun originalsSinceRecommendation(prefix: List<Int>, tracks: List<ShuffleTrack>, ids: Set<String>): Int =
    prefix.asReversed().takeWhile { tracks[it].id !in ids }.size

private fun interleaveDiscovery(
    prefix: List<Int>,
    originals: List<Int>,
    recommendations: List<Int>,
    tracks: List<ShuffleTrack>,
    ids: Set<String>,
    interval: Int,
): IntArray {
    val order = prefix.toMutableList()
    var originalsSince = originalsSinceRecommendation(prefix, tracks, ids)
    val pending = recommendations.toMutableList()
    for (index in originals) {
        if (originalsSince >= interval && pending.isNotEmpty()) {
            order.add(pending.removeAt(0))
            originalsSince = 0
        }
        order.add(index)
        originalsSince++
    }
    // Small queues can still discover one song after their last original track.
    order.addAll(pending)
    return order.toIntArray()
}

internal fun discoverySlots(prefixOriginals: Int, remainingOriginals: Int, interval: Int = DEFAULT_DISCOVERY_INTERVAL): Int =
    if (remainingOriginals == 0 && prefixOriginals == 0) 0
    else ((prefixOriginals + remainingOriginals) / validDiscoveryInterval(interval)).coerceIn(1, 4)
