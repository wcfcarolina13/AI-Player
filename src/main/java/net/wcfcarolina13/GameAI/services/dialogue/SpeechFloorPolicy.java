package net.wcfcarolina13.GameAI.services.dialogue;

/**
 * Pure-logic policy: the cross-lane "speech floor" that keeps one audience from being talked at
 * by several dialogue lanes at once.
 *
 * <p>No Minecraft imports — the whole decision is expressed over epoch millis and an enum, so it
 * is unit-testable (see {@code TravelWaitPolicy} for the same shape).
 *
 * <p>Background: before this policy every dialogue lane owned its own cooldown, and those
 * cooldowns were keyed by (bot, trigger-pool) only. Nothing measured how recently the
 * <em>audience</em> had last been spoken to, so two companions running two different pools could
 * — and in the 2026-09-06 field log did — land three lines on one player inside a single second,
 * and a soul scene could start nine seconds after the previous one ended. The floor is the one
 * piece of shared state that all lanes consult: while it is closed, nobody speaks to that
 * audience.
 *
 * <p>The floor is deliberately <em>not</em> a queue. A scripted ambient line that arrives while
 * the floor is closed is dropped, not deferred; deferring would only move the pile-up a few
 * seconds later. Scenes outrank scripted flavour: a scene line arms a longer floor than a
 * scripted line, and the end of a scene arms the longest floor of all so the player gets a real
 * beat of quiet before the ambient chatter resumes.
 */
public final class SpeechFloorPolicy {

    /**
     * Which lane armed the floor. The source determines only how long the floor stays closed —
     * every lane reads the same floor, so the policy stays lane-agnostic (an IDLE-lane scene and
     * an ACTIVE-lane scene arm the identical floor, and neither is coupled to the other's toggle).
     */
    public enum Source {
        /** A scripted ambient/reaction line (pet proximity, context reactions). */
        SCRIPTED_AMBIENT,
        /** One delivered line of a soul group scene. */
        SOUL_SCENE_LINE,
        /** A soul group scene reaching its finish site. */
        SOUL_SCENE_END
    }

    /**
     * Floor armed by a single scripted ambient line: 4s.
     *
     * <p>Long enough that a second bot's reaction to the same stimulus (both companions notice the
     * same wolf on the same tick) cannot land on top of the first, and long enough to cover the
     * overhead-line display window plus its voice playback for a one-sentence line. Short enough
     * that two genuinely independent reactions a handful of seconds apart both still get through —
     * scripted flavour is meant to feel alive, not rationed.
     */
    public static final long SCRIPTED_LINE_FLOOR_MS = 4_000L;

    /**
     * Floor armed by each delivered soul scene line: 6s.
     *
     * <p>Longer than a scripted line because a scene line is model-written and typically longer
     * than the one-sentence scripted pool entries, and because the scene's own per-line pacing
     * already leaves a gap the scripted lanes must not fill. This is what stops a scripted line
     * from being wedged between two lines of the same conversation.
     */
    public static final long SCENE_LINE_FLOOR_MS = 6_000L;

    /**
     * Floor armed when a scene finishes: 20s.
     *
     * <p>The scene occupancy flag ({@code SoulRuntime.isSceneBudgetFree}) clears the instant the
     * scene's finish site runs, which is why a second scene could begin nine seconds after the
     * first ended. This is the post-scene quiet period that occupancy alone never provided: after
     * a conversation the audience gets twenty seconds of silence from every lane before anything
     * else may speak.
     */
    public static final long POST_SCENE_QUIET_MS = 20_000L;

    /**
     * Sanity horizon for a stored floor: twice {@link #POST_SCENE_QUIET_MS}.
     *
     * <p>No source can arm a floor further out than {@link #POST_SCENE_QUIET_MS}, so a stored
     * value further into the future than that plus one more quiet window cannot have been written
     * by this policy against the current clock — it is a wall-clock jump (NTP correction, a
     * suspended laptop resuming, a save carried between machines). Rather than muting an audience
     * for however long the bogus value says, such a value is clamped back to a single quiet
     * window from now.
     */
    public static final long MAX_FLOOR_HORIZON_MS = 2L * POST_SCENE_QUIET_MS;

    private SpeechFloorPolicy() {
    }

    /** How long {@code source} closes the floor for. A null source arms nothing. */
    public static long floorDurationMs(Source source) {
        if (source == null) {
            return 0L;
        }
        return switch (source) {
            case SCRIPTED_AMBIENT -> SCRIPTED_LINE_FLOOR_MS;
            case SOUL_SCENE_LINE -> SCENE_LINE_FLOOR_MS;
            case SOUL_SCENE_END -> POST_SCENE_QUIET_MS;
        };
    }

    /**
     * Normalises a stored busy-until stamp against the current clock: a non-positive value means
     * "no floor was ever armed", and a value beyond {@link #MAX_FLOOR_HORIZON_MS} is treated as
     * clock corruption and pulled back to one quiet window from now.
     */
    public static long sanitize(long nowMs, long busyUntilMs) {
        if (busyUntilMs <= 0L) {
            return 0L;
        }
        if (busyUntilMs > nowMs + MAX_FLOOR_HORIZON_MS) {
            return nowMs + POST_SCENE_QUIET_MS;
        }
        return busyUntilMs;
    }

    /**
     * True while the audience may be spoken to. The boundary tick counts as open:
     * {@code nowMs == busyUntilMs} means the floor has just expired.
     */
    public static boolean isOpen(long nowMs, long busyUntilMs) {
        return nowMs >= sanitize(nowMs, busyUntilMs);
    }

    /**
     * The new busy-until stamp after {@code source} speaks at {@code nowMs}.
     *
     * <p>Never shortens an existing floor: a scripted line arriving in the middle of a 20s
     * post-scene quiet does not cut that quiet down to 4s. A longer floor always wins, which is
     * how "a scene outranks scripted ambient" is enforced without any lane needing to know about
     * the other lanes.
     */
    public static long armedUntil(long nowMs, long currentBusyUntilMs, Source source) {
        return Math.max(sanitize(nowMs, currentBusyUntilMs), nowMs + floorDurationMs(source));
    }

    /** Milliseconds until the floor reopens, clamped at 0. For logging only. */
    public static long remainingMs(long nowMs, long busyUntilMs) {
        return Math.max(0L, sanitize(nowMs, busyUntilMs) - nowMs);
    }
}
