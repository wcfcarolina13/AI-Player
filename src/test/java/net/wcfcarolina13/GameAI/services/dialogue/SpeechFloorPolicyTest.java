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

    // --- source-aware preemption (1.1.216 review finding 1) ---

    @Test
    void aSceneOpensThroughAScriptedFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        long midFloor = NOW + 1_000L;

        // The floor really is closed...
        assertFalse(SpeechFloorPolicy.isOpen(midFloor, scripted));
        // ...but a scene preempts scripted flavour, which is what stops the 5s banter evaluation
        // from being starved forever by a steady 4s scripted stream.
        assertTrue(SpeechFloorPolicy.isOpenFor(midFloor, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertTrue(SpeechFloorPolicy.isOpenFor(midFloor, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, SpeechFloorPolicy.Source.SOUL_SCENE_END));
    }

    @Test
    void aScriptedRequestDoesNotOpenThroughASceneFloor() {
        long sceneLine = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_LINE);
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        long midFloor = NOW + 1_000L;

        assertFalse(SpeechFloorPolicy.isOpenFor(midFloor, sceneLine,
                SpeechFloorPolicy.Source.SOUL_SCENE_LINE, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertFalse(SpeechFloorPolicy.isOpenFor(midFloor, sceneEnd,
                SpeechFloorPolicy.Source.SOUL_SCENE_END, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        // A scripted request respects a scripted floor too — the preemption is one-directional.
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertFalse(SpeechFloorPolicy.isOpenFor(midFloor, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
    }

    @Test
    void anExpiredFloorIsOpenToEverySource() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        long after = NOW + 4_000L;
        for (SpeechFloorPolicy.Source requesting : SpeechFloorPolicy.Source.values()) {
            assertTrue(SpeechFloorPolicy.isOpenFor(after, scripted,
                    SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, requesting),
                    "expired floor must be open to " + requesting);
        }
    }

    @Test
    void anUnknownRequestingSourceRespectsEveryFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, null));
        // ...and a floor with no recorded owner is not preemptible either.
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, scripted,
                null, SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
    }

    @Test
    void aSceneFloorIsNotShortenedNorRelabelledByALaterScriptedArm() {
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        SpeechFloorPolicy.Source owner = SpeechFloorPolicy.armedBySource(
                NOW, 0L, null, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_END, owner);

        long later = NOW + 1_000L;
        long afterScripted = SpeechFloorPolicy.armedUntil(later, sceneEnd, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        SpeechFloorPolicy.Source ownerAfter = SpeechFloorPolicy.armedBySource(
                later, sceneEnd, owner, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);

        // Deadline unchanged (monotonic) AND still labelled as the scene's, so a scripted arm
        // inside a post-scene quiet cannot make that quiet preemptible by the next scene.
        assertEquals(sceneEnd, afterScripted);
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_END, ownerAfter);
        assertFalse(SpeechFloorPolicy.isOpenFor(later, afterScripted, ownerAfter,
                SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
    }

    @Test
    void armedBySourceFollowsWhicheverDeadlineSurvives() {
        // A longer incoming arm takes ownership...
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_END,
                SpeechFloorPolicy.armedBySource(NOW, scripted, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT,
                        SpeechFloorPolicy.Source.SOUL_SCENE_END));
        // ...and a scripted re-arm that outlasts an almost-expired scene floor owns it, so the
        // label and the deadline can never disagree.
        long sceneLine = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_LINE);
        long nearlyOver = NOW + 5_000L;
        assertEquals(nearlyOver + 4_000L,
                SpeechFloorPolicy.armedUntil(nearlyOver, sceneLine, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertEquals(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT,
                SpeechFloorPolicy.armedBySource(nearlyOver, sceneLine, SpeechFloorPolicy.Source.SOUL_SCENE_LINE,
                        SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        // A null incoming source arms nothing and changes no label.
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_LINE,
                SpeechFloorPolicy.armedBySource(NOW, sceneLine, SpeechFloorPolicy.Source.SOUL_SCENE_LINE, null));
    }

    @Test
    void sceneSourcesAreTheOnesThatPreempt() {
        assertTrue(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertTrue(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SOUL_SCENE_END));
        assertFalse(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertFalse(SpeechFloorPolicy.isSceneSource(null));
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
