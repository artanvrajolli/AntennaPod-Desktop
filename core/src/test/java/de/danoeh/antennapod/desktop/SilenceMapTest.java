package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Where silence is skipped from and to. The skipping this replaces jumped a second ahead of
 * 400 ms of quiet and cut the start of the next sentence, and took quiet speech for silence;
 * these pin down that neither can happen from the map.
 */
public class SilenceMapTest {
    private static final long FRAME_MS = 50;
    private static final double SPEECH_DB = -20;
    private static final double NOTHING = Double.NaN;

    /** Feeds a stretch of one level and returns where it ended. */
    private static long feed(SilenceMap map, long fromMs, long lengthMs, double levelDb) {
        for (long at = fromMs; at < fromMs + lengthMs; at += FRAME_MS) {
            map.add(at, FRAME_MS, levelDb);
        }
        return fromMs + lengthMs;
    }

    /** Ten seconds of speech, a gap, five more seconds of speech; returns the map. */
    private static SilenceMap speechGapSpeech(long gapMs, double gapDb) {
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, SPEECH_DB);
        at = feed(map, at, gapMs, gapDb);
        feed(map, at, 5_000, SPEECH_DB);
        return map;
    }

    private static void assertNothingSkipped(SilenceMap map, long fromMs, long toMs) {
        for (long position = fromMs; position <= toMs; position += 10) {
            assertNull("skipped from " + position, map.skipFrom(position));
        }
    }

    @Test
    public void aSkipLandsJustBeforeTheSoundReturns() {
        SilenceMap map = speechGapSpeech(4_000, NOTHING);
        SilenceMap.Skip skip = map.skipFrom(10_000 + SilenceMap.FRONT_PAD_MS);
        assertNotNull(skip);
        assertEquals(14_000 - SilenceMap.BACK_PAD_MS, skip.targetMs);
        // wherever in the silence it is asked from, the landing is the same
        assertEquals(14_000 - SilenceMap.BACK_PAD_MS, map.skipFrom(12_000).targetMs);
    }

    @Test
    public void nothingIsSkippedOutsideTheSilence() {
        SilenceMap map = speechGapSpeech(4_000, NOTHING);
        // not from the speech before, not from the silence's first moments (a word rings out)
        assertNothingSkipped(map, 0, 10_000 + SilenceMap.FRONT_PAD_MS - 10);
        // not once the jump would save too little to be worth a seek, and not from the speech after
        assertNothingSkipped(map, 14_000 - SilenceMap.BACK_PAD_MS - SilenceMap.MIN_SKIP_MS + 10, 19_000);
    }

    @Test
    public void thePausesOfOrdinarySpeechAreLeftAlone() {
        // sentence pauses of up to a second: the old skipping jumped out of every one of them
        SilenceMap map = new SilenceMap(-70);
        long at = 0;
        for (long pauseMs = 200; pauseMs < SilenceMap.MIN_RUN_MS; pauseMs += 50) {
            at = feed(map, at, 3_000, SPEECH_DB);
            at = feed(map, at, pauseMs, NOTHING);
        }
        feed(map, at, 3_000, SPEECH_DB);
        assertNothingSkipped(map, 0, at + 3_000);
    }

    @Test
    public void aQuietSpeakerIsNotSilence() {
        // 18 dB under the speaker before: the old fixed line called all of this complete silence
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, SPEECH_DB);
        at = feed(map, at, 20_000, SPEECH_DB - 18);
        feed(map, at, 5_000, SPEECH_DB);
        assertNothingSkipped(map, 0, 35_000);
    }

    @Test
    public void silenceIsMeasuredAgainstTheSpeechAroundIt() {
        // room tone well under the voice is silence; a noise floor close under it is not
        assertNotNull(speechGapSpeech(4_000, SPEECH_DB - SilenceMap.BELOW_SPEECH_DB - 2).skipFrom(11_000));
        assertNothingSkipped(speechGapSpeech(4_000, SPEECH_DB - SilenceMap.BELOW_SPEECH_DB + 4), 0, 19_000);
    }

    @Test
    public void theThresholdFollowsTheLevelOfTheLastSeconds() {
        SilenceMap map = new SilenceMap(-70);
        assertEquals(SilenceMap.UNKNOWN_SPEECH_THRESHOLD_DB, map.thresholdDb(), 0.0);
        long at = feed(map, 0, 10_000, -20);
        assertEquals(-50, map.thresholdDb(), 0.001);
        // a quieter speaker takes over: within ten seconds the line has followed down
        at = feed(map, at, 12_000, -27);
        assertEquals(-57, map.thresholdDb(), 0.001);
        // and it has limits both ways
        at = feed(map, at, 12_000, -40);
        assertEquals(SilenceMap.LOWEST_THRESHOLD_DB, map.thresholdDb(), 0.001);
        feed(map, at, 12_000, -3);
        assertEquals(SilenceMap.HIGHEST_THRESHOLD_DB, map.thresholdDb(), 0.001);
    }

    @Test
    public void nothingAboveTheHighestThresholdIsEverSilence() {
        // loud music, then a quiet passage 35 dB under it: quiet, but well above any silence
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, -5);
        at = feed(map, at, 5_000, -40);
        feed(map, at, 5_000, -5);
        assertNothingSkipped(map, 0, 20_000);
    }

    @Test
    public void anySoundEndsTheSilence() {
        // a single frame of sound two seconds into a gap: the skip stops in front of it
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, SPEECH_DB);
        at = feed(map, at, 2_000, NOTHING);
        at = feed(map, at, FRAME_MS, SPEECH_DB - 10);
        at = feed(map, at, 2_000, NOTHING);
        feed(map, at, 5_000, SPEECH_DB);
        assertEquals(12_000 - SilenceMap.BACK_PAD_MS, map.skipFrom(10_500).targetMs);
        assertEquals(14_050 - SilenceMap.BACK_PAD_MS, map.skipFrom(12_050 + SilenceMap.FRONT_PAD_MS).targetMs);
    }

    @Test
    public void aSilenceCarriesOnSlightlyAboveItsThreshold() {
        // room tone wavering around the line is one silence, not a dozen short ones
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, SPEECH_DB);
        for (int i = 0; i < 40; i++) {
            at = feed(map, at, FRAME_MS, i % 2 == 0 ? -51 : -48);
        }
        feed(map, at, 5_000, SPEECH_DB);
        assertEquals(12_000 - SilenceMap.BACK_PAD_MS, map.skipFrom(10_500).targetMs);
    }

    @Test
    public void aSilenceStillBeingScannedIsSkippedAsFarAsItHasBeenLookedAt() {
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, SPEECH_DB);
        // not yet long enough to be worth anything
        at = feed(map, at, SilenceMap.MIN_RUN_MS - FRAME_MS, NOTHING);
        assertNothingSkipped(map, 10_000, at);
        // dead air, the scan 20 s into it: the jump stays behind the scan by the usual margin
        at = feed(map, at, 20_000, NOTHING);
        assertEquals(at - SilenceMap.BACK_PAD_MS, map.skipFrom(10_500).targetMs);
        // the sound returns: what is left is skipped to just before it
        at = feed(map, at, 3_000, NOTHING);
        feed(map, at, 1_000, SPEECH_DB);
        assertEquals(at - SilenceMap.BACK_PAD_MS, map.skipFrom(31_000).targetMs);
    }

    @Test
    public void noSilenceRunsAcrossAudioNobodyLookedAt() {
        // frames went missing in the middle of a gap: each side is judged alone
        SilenceMap map = new SilenceMap(-70);
        long at = feed(map, 0, 10_000, SPEECH_DB);
        feed(map, at, 800, NOTHING);
        feed(map, 12_000, 800, NOTHING);
        feed(map, 12_800, 5_000, SPEECH_DB);
        assertNothingSkipped(map, 0, 17_800);
    }

    @Test
    public void beforeAnySpeechOnlyNearSilenceCounts() {
        // the scan starts inside a quiet passage: without a voice to measure against, be strict
        SilenceMap quietTalk = new SilenceMap(-70);
        feed(quietTalk, 0, 5_000, -50);
        assertNothingSkipped(quietTalk, 0, 5_000);

        SilenceMap deadAir = new SilenceMap(-70);
        feed(deadAir, 0, 5_000, -62);
        assertNotNull(deadAir.skipFrom(1_000));
    }

    @Test
    public void framesAreReadFromTheSpectrum() {
        SilenceMap map = new SilenceMap(-70);
        float[] speech = new float[128];
        float[] silence = new float[128];
        java.util.Arrays.fill(speech, -70f);
        java.util.Arrays.fill(silence, -70f);
        for (int band = 2; band < 20; band++) {
            speech[band] = -38f;
        }
        long at = 0;
        for (; at < 5_000; at += FRAME_MS) {
            map.add(at, FRAME_MS, speech);
        }
        for (; at < 8_000; at += FRAME_MS) {
            map.add(at, FRAME_MS, silence);
        }
        map.add(at, FRAME_MS, speech);
        assertEquals(8_000 - SilenceMap.BACK_PAD_MS, map.skipFrom(6_000).targetMs);
        assertNull(map.skipFrom(4_000));
    }

    @Test
    public void theMapKnowsHowFarItHasLooked() {
        SilenceMap map = new SilenceMap(-70);
        assertEquals(-1, map.scannedToMs());
        feed(map, 30_000, 10_000, SPEECH_DB);
        assertEquals(40_000, map.scannedToMs());
        // a frame handed in twice changes nothing
        map.add(39_950, FRAME_MS, NOTHING);
        assertEquals(40_000, map.scannedToMs());
        // and nothing is skipped from beyond it: a silence there has not been seen to end
        feed(map, 40_000, 5_000, NOTHING);
        assertNull(map.skipFrom(45_500));
        assertNull(map.skipFrom(60_000));
    }

    @Test
    public void clearingForgetsTheSilenceAndTheSpeech() {
        SilenceMap map = speechGapSpeech(4_000, NOTHING);
        map.clear();
        assertNull(map.skipFrom(12_000));
        assertEquals(-1, map.scannedToMs());
        assertEquals(SilenceMap.UNKNOWN_SPEECH_THRESHOLD_DB, map.thresholdDb(), 0.0);
        // and it can be filled again from somewhere else
        feed(map, 100_000, 3_000, NOTHING);
        assertNotNull(map.skipFrom(101_000));
    }

    @Test
    public void theSkipCarriesTheLevelItsSilenceWasJudgedAgainst() {
        SilenceMap map = speechGapSpeech(4_000, NOTHING);
        assertEquals(SPEECH_DB - SilenceMap.BELOW_SPEECH_DB, map.skipFrom(12_000).thresholdDb, 0.001);
    }

    @Test
    public void thePlayingPlayerHasToHearSilenceToo() {
        SilenceMap.Heard heard = new SilenceMap.Heard();
        // nothing heard yet, or not enough of it
        assertFalse(heard.quiet(-50, 1_000));
        heard.add(-60, 1_000);
        heard.add(NOTHING, 1_050);
        assertFalse(heard.quiet(-50, 1_050));
        heard.add(-55, 1_100);
        assertTrue(heard.quiet(-50, 1_100));
        // one frame of sound and it no longer agrees, until that frame has passed
        heard.add(-30, 1_150);
        assertFalse(heard.quiet(-50, 1_150));
        heard.add(-60, 1_200);
        heard.add(-60, 1_250);
        assertFalse(heard.quiet(-50, 1_250));
        heard.add(-60, 1_300);
        assertTrue(heard.quiet(-50, 1_300));
    }

    @Test
    public void whatWasHeardGoesStale() {
        SilenceMap.Heard heard = new SilenceMap.Heard();
        for (long at = 0; at < 200; at += FRAME_MS) {
            heard.add(NOTHING, at);
        }
        assertTrue(heard.quiet(-50, 200));
        // no frames arrive while paused or stalled: an old reading is no reading
        assertFalse(heard.quiet(-50, 150 + SilenceMap.Heard.FRESH_MS + 1));
        // and after a seek it says nothing about where playback is now
        heard.add(NOTHING, 600);
        assertTrue(heard.quiet(-50, 600));
        heard.reset();
        assertFalse(heard.quiet(-50, 600));
    }
}
