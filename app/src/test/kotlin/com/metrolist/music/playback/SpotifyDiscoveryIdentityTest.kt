package com.metrolist.music.playback

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SpotifyMatchEntity
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.withUpdatedMetadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.isSpotifyCatalogTrack
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpotifyDiscoveryIdentityTest {
    private val spotifyId = "0123456789ABCDEFGHIJKL"

    @After fun clearRegistry() = SpotifyMetadataRegistry.clearAll()

    @Test fun oldSavedQueuesRemainReadable() {
        val stream = javaClass.getResourceAsStream("/legacy-media-metadata.ser")!!
        val metadata = ObjectInputStream(stream).use { it.readObject() as MediaMetadata }
        assertEquals("legacy-song", metadata.id)
        assertEquals(180, metadata.duration)
        assertNull(metadata.spotifyTrackId)
    }

    @Test fun spotifyIdentitySurvivesSavingQueueAndRefreshingLikeMetadata() {
        val original = MediaMetadata("youtube-id", "Song", emptyList(), 180, spotifyTrackId = spotifyId)
        val bytes = ByteArrayOutputStream().also { buffer -> ObjectOutputStream(buffer).use { it.writeObject(original) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as MediaMetadata }
        assertEquals(spotifyId, restored.spotifyTrackId)
        val dbMetadata = MediaMetadata("youtube-id", "Song", emptyList(), 180, liked = true)
        val refreshed = restored.toMediaItem().withUpdatedMetadata(dbMetadata).metadata!!
        assertEquals(spotifyId, refreshed.spotifyTrackId)
        assertTrue(refreshed.liked)
    }

    @Test fun restoredQueueIdentityOverridesAnotherVersionMappedToTheSameAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val database = MusicDatabase(room)
            database.upsertSpotifyMatch(SpotifyMatchEntity("other-version", "youtube-id", "Song", "Artist", 1.0))
            SpotifyMetadataRegistry.register("youtube-id", SpotifyTrack(id = "other-version"))
            SpotifyMetadataRegistry.rememberIdentity("youtube-id", spotifyId)
            SpotifyMetadataRegistry.register("youtube-id", SpotifyTrack(id = "prefetched-different-version"))
            assertNull(SpotifyMetadataRegistry.get("youtube-id"))
            val mapper = SpotifyYouTubeMapper(database) { error("Known Spotify IDs must not require a search") }
            assertEquals("spotify:track:$spotifyId", mapper.resolveToSpotifyUri("youtube-id", "Song", "Artist"))
        } finally { room.close() }
    }

    @Test fun seedLookupDoesNotReplaceAKnownSpotifyTrackWithASimilarVersion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        try {
            SpotifyMetadataRegistry.rememberIdentity("youtube-id", spotifyId)
            val mapper = SpotifyYouTubeMapper(MusicDatabase(room)) {
                listOf(SpotifyTrack(id = "different-version", name = "Song", durationMs = 180000))
            }
            assertNull(mapper.resolveToSpotifyTrack("youtube-id", "Song", "Artist", 180))
        } finally { room.close() }
    }

    @Test fun localHistoryAndYoutubeIdsCannotBecomeSpotifyDiscoveryRecommendations() {
        assertTrue(isSpotifyCatalogTrack(SpotifyTrack(id = spotifyId)))
        assertFalse(isSpotifyCatalogTrack(SpotifyTrack(id = "dQw4w9WgXcQ")))
        assertFalse(isSpotifyCatalogTrack(SpotifyTrack(id = spotifyId, isLocal = true)))
        assertFalse(isSpotifyCatalogTrack(SpotifyTrack(id = "")))
    }
}
