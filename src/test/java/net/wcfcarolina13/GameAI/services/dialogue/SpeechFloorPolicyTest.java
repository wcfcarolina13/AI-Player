package net.wcfcarolina13.GameAI.services.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the cross-lane speech floor. No Minecraft types. */
class SpeechFloorPolicyTest {

    private static final long NOW = 1_000_000L;

    @Test
    void eachSourceCarriesItsOwnFloorDuration() {
        assertEquals(4_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertEquals(6_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertEquals(20_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_END));
        assertEquals(SpeechFloorPolicy.SCRIPTED_LINE_FLOOR_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertEquals(SpeechFloorPolicy.SCENE_LINE_FLOOR_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertEquals(SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_END));
    }

    @Test
    void nullSourceArmsNothing() {
        assertEquals(0L, SpeechFloorPolicy.floorDurationMs(null));
        assertEquals(NOW, SpeechFloorPolicy.armedUntil(NOW, 0L, null));
    }

    @Test
    void neverArmedFloorIsOpen() {
        assertTrue(SpeechFloorPolicy.isOpen(NOW, 0L));
        assertTrue(SpeechFloorPolicy.isOpen(NOW, -5L));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, 0L));
    }

    @Test
    void isOpenTreatsTheExpiryInstantAsOpen() {
        assertFalse(SpeechFloorPolicy.isOpen(NOW, NOW + 1L));
        assertTrue(SpeechFloorPolicy.isOpen(NOW, NOW));          // boundary: nowMs == busyUntilMs
        assertTrue(SpeechFloorPolicy.isOpen(NOW, NOW - 1L));
    }

    @Test
    void armedUntilNeverShortensALongerExistingFloor() {
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        assertEquals(NOW + 20_000L, sceneEnd);

        // A scripted line arriving inside the post-scene quiet must not cut it down to 4s.
        long afterScripted = SpeechFloorPolicy.armedUntil(
                NOW + 1_000L, sceneEnd, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(sceneEnd, afterScripted);

        // Nor may a scene line (6s) shorten it.
        assertEquals(sceneEnd, SpeechFloorPolicy.armedUntil(
                NOW + 1_000L, sceneEnd, SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
    }

    @Test
    void aSceneEndOverridesAShorterScriptedFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(NOW + 4_000L, scripted);

        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, scripted, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        assertEquals(NOW + 20_000L, sceneEnd);
        assertFalse(SpeechFloorPolicy.isOpen(NOW + 5_000L, sceneEnd));
        assertTrue(SpeechFloorPolicy.isOpen(NOW + 20_000L, sceneEnd));
    }

    @Test
    void aScriptedLineExtendsAnAlmostExpiredFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        long later = NOW + 3_500L;
        long extended = SpeechFloorPolicy.armedUntil(later, scripted, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(later + 4_000L, extended);
    }

    @Test
    void remainingMsClampsAtZero() {
        assertEquals(4_000L, SpeechFloorPolicy.remainingMs(NOW, NOW + 4_000L));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, NOW));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, NOW - 60_000L));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, 0L));
    }

    @Test
    void aFloorBeyondTheSanityHorizonIsClamped() {
        // No source can arm past POST_SCENE_QUIET_MS, so anything past the horizon is a clock jump.
        long bogus = NOW + SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS + 1L;
        assertEquals(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS, SpeechFloorPolicy.sanitize(NOW, bogus));
        assertEquals(SpeechFloorPolicy.POST_SCENE_QUIET_MS, SpeechFloorPolicy.remainingMs(NOW, bogus));
        assertFalse(SpeechFloorPolicy.isOpen(NOW, bogus));
        // ...and the audience is speakable again one quiet window later, not a year later.
        assertTrue(SpeechFloorPolicy.isOpen(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.sanitize(NOW, bogus)));

        // A value exactly at the horizon is plausible enough to keep.
        long atHorizon = NOW + SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS;
        assertEquals(atHorizon, SpeechFloorPolicy.sanitize(NOW, atHorizon));

        // armedUntil sanitises its input too, so a bogus stored floor cannot survive an arm.
        assertEquals(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.armedUntil(NOW, bogus, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
    }
}
