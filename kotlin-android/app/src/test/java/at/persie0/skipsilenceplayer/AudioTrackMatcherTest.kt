package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackMatcherTest {
    @Test
    fun singleTrackAlwaysMatches() {
        assertEquals(
            7,
            chooseAudioStreamIndex(
                listOf(AudioStreamCandidate(7, null)),
                desiredLanguage = null
            )
        )
    }

    @Test
    fun matchesIso639AndLanguageSubtags() {
        val streams = listOf(
            AudioStreamCandidate(1, "eng"),
            AudioStreamCandidate(4, "spa"),
            AudioStreamCandidate(5, "deu")
        )
        assertEquals(1, chooseAudioStreamIndex(streams, "en-US"))
        assertEquals(4, chooseAudioStreamIndex(streams, "es"))
        assertEquals(5, chooseAudioStreamIndex(streams, "de-DE"))
    }

    @Test
    fun ambiguousAndMissingLanguagesFailClosed() {
        val candidates = listOf(
            AudioStreamCandidate(0, "eng"),
            AudioStreamCandidate(1, "eng")
        )
        assertNull(chooseAudioStreamIndex(candidates, "en"))
        assertNull(chooseAudioStreamIndex(candidates, null))
        assertNull(chooseAudioStreamIndex(candidates, "und"))
        assertNull(chooseAudioStreamIndex(emptyList(), "en"))
    }
}
