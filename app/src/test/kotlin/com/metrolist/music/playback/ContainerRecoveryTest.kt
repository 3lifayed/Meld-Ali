package com.metrolist.music.playback

import android.app.Application
import android.content.Context
import java.io.EOFException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ContainerRecoveryTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("container-recovery-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test fun detectsTheReportedMp4ParserFailureWithoutInterceptingNetworkErrors() {
        val cause = IllegalStateException("Skipping atom with length > 2147483647 (unsupported).")
        cause.stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.mp4.FragmentedMp4Extractor", "readAtomHeader", "FragmentedMp4Extractor.java", 682))
        assertTrue(isMp4ContainerFailure(PlaybackException("Source error", cause, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertFalse(isMp4ContainerFailure(PlaybackException("Source error", cause, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        cause.stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.mkv.MatroskaExtractor", "read", "MatroskaExtractor.java", 12))
        assertFalse(isMp4ContainerFailure(PlaybackException("Source error", cause, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
    }

    @Test fun retriesAreBoundedAndIsolateFormatsAndAttemptsFromSavedAudio() {
        val recovery = ContainerRecovery(preferences())
        assertFalse(recovery.isActive("song"))
        assertNull(recovery.cacheKey("song", 140))
        assertEquals(1, recovery.nextAttempt("song"))
        val first = recovery.cacheKey("song", 251)!!
        assertNotEquals("song", first)
        assertNotEquals(first, recovery.cacheKey("song", 140))
        assertEquals(2, recovery.nextAttempt("song"))
        assertNotEquals(first, recovery.cacheKey("song", 251))
        assertNull(recovery.nextAttempt("song"))
        assertTrue(recovery.isActive("song"))
        assertFalse(recovery.isActive("other"))
        assertEquals(1, recovery.nextAttempt("other"))
    }

    @Test fun detectsSavedMp4EndOfFileWithoutTreatingOtherIoFailuresAsContainerCorruption() {
        val eof = EOFException()
        eof.stackTrace = arrayOf(
            StackTraceElement("androidx.media3.extractor.DefaultExtractorInput", "skipFully", "DefaultExtractorInput.java", 109),
            StackTraceElement("androidx.media3.extractor.mp4.FragmentedMp4Extractor", "readAtomPayload", "FragmentedMp4Extractor.java", 707),
        )
        assertTrue(isMp4ContainerFailure(PlaybackException("Source error", eof, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse(isMp4ContainerFailure(PlaybackException("Source error", eof, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        val other = java.io.IOException("connection reset")
        other.stackTrace = eof.stackTrace
        assertFalse(isMp4ContainerFailure(PlaybackException("Source error", other, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        eof.stackTrace = arrayOf(StackTraceElement("java.net.SocketInputStream", "read", "SocketInputStream.java", 1))
        assertFalse(isMp4ContainerFailure(PlaybackException("Source error", eof, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
    }

    @Test fun recoversSavedWebmVarintFailuresWithoutRetryingUnrelatedIoErrors() {
        val invalidVarint = IllegalStateException("No valid varint length mask found").apply {
            stackTrace = arrayOf(StackTraceElement(
                "androidx.media3.extractor.mkv.VarintReader", "readUnsignedVarint", "VarintReader.java", 89,
            ))
        }
        val failure = PlaybackException(
            "Source error", java.io.IOException("Unexpected loader failure", invalidVarint),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertTrue(shouldRecoverContainerFailure(failure, fromSavedAudio = true))
        assertFalse(shouldRecoverContainerFailure(failure, fromSavedAudio = false))
        val networkError = PlaybackException(
            "Connection reset", java.net.SocketException("Connection reset"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertFalse(shouldRecoverContainerFailure(networkError, fromSavedAudio = true))
    }

    @Test fun remembersTheFailedDownloadAcrossSessionsAndAcceptsAReplacement() {
        val preferences = preferences()
        val first = ContainerRecovery(preferences)
        first.quarantineSavedCopy("song", 123L)
        first.nextAttempt("song")
        val restored = ContainerRecovery(preferences)
        restored.refreshSavedCopy("song", 123L)
        assertTrue(restored.isActive("song"))
        assertNotNull(restored.cacheKey("song", 251))
        assertEquals(1, restored.nextAttempt("song"))
        restored.refreshSavedCopy("song", 123L)
        assertEquals(2, restored.nextAttempt("song"))
        restored.refreshSavedCopy("song", 123L)
        assertNull(restored.nextAttempt("song"))
        restored.refreshSavedCopy("song", 456L)
        assertFalse(restored.isActive("song"))
        assertNull(restored.cacheKey("song", 251))
        assertFalse(preferences.contains("song"))
    }

    @Test fun recoversTheActualExtractorExceptionFromATruncatedMp4() {
        val truncated = java.nio.ByteBuffer.allocate(8).putInt(1024).putInt(0x66726565).array()
        val source = ByteArrayDataSource(truncated)
        source.open(DataSpec.Builder().setUri("https://example.invalid/truncated.mp4").build())
        val extractor = FragmentedMp4Extractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = error("No tracks in this truncated file")
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        try {
            extractor.read(DefaultExtractorInput(source, 0, truncated.size.toLong()), PositionHolder())
            fail("The truncated MP4 must fail with EOFException")
        } catch (error: EOFException) {
            assertTrue(isMp4ContainerFailure(PlaybackException("Source error", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        } finally {
            extractor.release()
            source.close()
        }
    }

    @Test fun removingADownloadClearsItsQuarantineWithoutAffectingOtherSongs() {
        val recovery = ContainerRecovery(preferences())
        recovery.quarantineSavedCopy("first", 123L)
        recovery.quarantineSavedCopy("second", 456L)
        recovery.refreshSavedCopy("first", null)
        assertFalse(recovery.isActive("first"))
        recovery.refreshSavedCopy("second", 456L)
        assertTrue(recovery.isActive("second"))
        recovery.quarantineSavedCopy("stream-only", null)
        assertFalse(recovery.isActive("stream-only"))
    }

    @Test fun cachedUrlsKeepRecoveryKeysAndRangesOnEveryRead() {
        val cache = StreamUrlCache()
        cache.put("song", "https://example.invalid/audio", mapOf("User-Agent" to "test"), "WEB", 60,
            requireBoundedRange = true, rangeChunkSizeBytes = 1024, cacheKey = "container-retry:song:1:251")
        val original = DataSpec.Builder().setUri("https://example.invalid/original").setKey("song").setPosition(53000).build()
        val resolved = original.withResolvedStream(cache["song"]!!)
        assertEquals("container-retry:song:1:251", resolved.key)
        assertEquals(53000L, resolved.position)
        assertEquals(1024L, resolved.length)
        assertEquals("test", resolved.httpRequestHeaders["User-Agent"])
        assertEquals("song", original.key)
        val normal = original.withResolvedStream(CachedStreamUrl("https://example.invalid/normal", emptyMap(), "WEB"))
        assertEquals("song", normal.key)
    }

    @Test fun restartingTheSourceKeepsSpotifyIdentityPositionAndShuffleHistory() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Application>()).build()
        try {
            val items = (0..2).map { MediaMetadata("song$it", "Song", emptyList(), 180, spotifyTrackId = "spotify$it").toMediaItem() }
            player.setMediaItems(items)
            player.shuffleModeEnabled = true
            player.setShuffleOrder(DefaultShuffleOrder(intArrayOf(2, 1, 0), 1L))
            player.seekTo(1, 53000)
            player.playWhenReady = true
            player.stop()
            player.seekTo(1, 53000)
            assertEquals(3, player.mediaItemCount)
            assertEquals(53000L, player.currentPosition)
            assertEquals("spotify1", player.currentMediaItem?.metadata?.spotifyTrackId)
            assertEquals(2, player.previousMediaItemIndex)
            assertEquals(0, player.nextMediaItemIndex)
            assertTrue(player.playWhenReady)
        } finally { player.release() }
    }
}
