package net.wcfcarolina13.GameAI.services.dialogue;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live state for the cross-lane speech floor. Every decision is delegated to
 * {@link SpeechFloorPolicy}; this class only remembers, per audience, when the floor reopens.
 *
 * <p>Keyed by <b>audience</b> — the player the speech is aimed at — not by bot. Two companions
 * addressing the same player share one floor, which is exactly the pile-up being fixed; two
 * companions addressing different players are unaffected.
 *
 * <p>Threading: the scripted reaction services, {@code SoulBanterDirector.tick} and
 * {@code GroupScenePlayback.tick} all run on the server tick thread, but
 * {@code GroupScenePlayback.enqueue} runs on a provider worker, so the map is a
 * {@link ConcurrentHashMap} and {@link #noteSpeech} arms through an atomic {@code merge}.
 *
 * <p>Static, like the reaction services it gates. Those services register no SERVER_STOPPING
 * teardown of their own (only {@code END_SERVER_TICK}), so no lifecycle hook is invented here;
 * {@link #clear(UUID)} and {@link #clearAll()} exist for callers that want to reset a floor
 * explicitly (a test, a debug command, or a future teardown that already owns a hook). The map
 * itself is bounded by the number of audiences ever spoken to and stores one long each.
 */
public final class SpeechFloorService {

    /**
     * Key used when a bot's owner cannot be resolved (unowned or config-less bot). All such
     * speech shares one floor: it is aimed at "whoever is listening", and serialising it against
     * itself is the desired behaviour.
     */
    private static final UUID UNATTRIBUTED_AUDIENCE = new UUID(0L, 0L);

    private static final ConcurrentHashMap<UUID, Long> BUSY_UNTIL_MS = new ConcurrentHashMap<>();

    private SpeechFloorService() {
    }

    /** True while {@code audienceId} may be spoken to by any lane. */
    public static boolean isFloorOpen(UUID audienceId) {
        UUID key = key(audienceId);
        return SpeechFloorPolicy.isOpen(System.currentTimeMillis(), BUSY_UNTIL_MS.getOrDefault(key, 0L));
    }

    /**
     * Records that something was just said to {@code audienceId} and closes the floor for the
     * duration {@code source} carries. Never shortens a floor already armed by a longer source.
     */
    public static void noteSpeech(UUID audienceId, SpeechFloorPolicy.Source source) {
        if (source == null) {
            return;
        }
        UUID key = key(audienceId);
        long now = System.currentTimeMillis();
        BUSY_UNTIL_MS.merge(key,
                SpeechFloorPolicy.armedUntil(now, 0L, source),
                (existing, ignored) -> SpeechFloorPolicy.armedUntil(now, existing, source));
    }

    /** Milliseconds until the floor reopens for {@code audienceId}, clamped at 0. Logging only. */
    public static long remainingMs(UUID audienceId) {
        UUID key = key(audienceId);
        return SpeechFloorPolicy.remainingMs(System.currentTimeMillis(), BUSY_UNTIL_MS.getOrDefault(key, 0L));
    }

    /** Forgets one audience's floor (the audience may be spoken to immediately). */
    public static void clear(UUID audienceId) {
        BUSY_UNTIL_MS.remove(key(audienceId));
    }

    /** Forgets every floor. */
    public static void clearAll() {
        BUSY_UNTIL_MS.clear();
    }

    private static UUID key(UUID audienceId) {
        return audienceId == null ? UNATTRIBUTED_AUDIENCE : audienceId;
    }
}
