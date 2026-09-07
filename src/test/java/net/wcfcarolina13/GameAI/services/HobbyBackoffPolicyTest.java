package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HobbyBackoffPolicyTest {

    private static final long NOW = 10_000L;

    private static HobbyBackoffPolicy.Attempt failure(int consecutiveFailures) {
        return new HobbyBackoffPolicy.Attempt("woodcut", false, false, consecutiveFailures, NOW);
    }

    @Test
    void firstFailureBacksOffExactlySixtySeconds() {
        assertEquals(NOW + 1200L, HobbyBackoffPolicy.nextAllowedTick(failure(0)));
        assertEquals(1200L, HobbyBackoffPolicy.BASE_FAILURE_TICKS);
    }

    @Test
    void failuresDoubleUntilTheCap() {
        assertEquals(NOW + 1200L, HobbyBackoffPolicy.nextAllowedTick(failure(0)));
        assertEquals(NOW + 2400L, HobbyBackoffPolicy.nextAllowedTick(failure(1)));
        assertEquals(NOW + 4800L, HobbyBackoffPolicy.nextAllowedTick(failure(2)));
        assertEquals(NOW + 9600L, HobbyBackoffPolicy.nextAllowedTick(failure(3)));
        // 1200 << 4 == 19200, clamped to the 10-minute ceiling.
        assertEquals(NOW + HobbyBackoffPolicy.MAX_BACKOFF_TICKS, HobbyBackoffPolicy.nextAllowedTick(failure(4)));
    }

    @Test
    void backoffIsMonotonicUpToTheCap() {
        long previous = NOW;
        for (int failures = 0; failures <= 4; failures++) {
            long next = HobbyBackoffPolicy.nextAllowedTick(failure(failures));
            assertTrue(next >= previous, "backoff must not shrink at failures=" + failures);
            previous = next;
        }
    }

    @Test
    void capIsNeverExceededAndNeverOverflows() {
        int[] hugeCounts = {5, 16, 31, 32, 53, 64, 1_000, 1_000_000, Integer.MAX_VALUE};
        for (int failures : hugeCounts) {
            long next = HobbyBackoffPolicy.nextAllowedTick(failure(failures));
            // Never negative (the overflow bug an unclamped shift would introduce) …
            assertTrue(next > NOW, "backoff overflowed or vanished at failures=" + failures);
            // … and never longer than the documented ceiling.
            assertEquals(NOW + HobbyBackoffPolicy.MAX_BACKOFF_TICKS, next,
                    "backoff exceeded the cap at failures=" + failures);
        }
    }

    @Test
    void negativeFailureCountIsTreatedAsTheFirstFailure() {
        assertEquals(NOW + HobbyBackoffPolicy.BASE_FAILURE_TICKS,
                HobbyBackoffPolicy.nextAllowedTick(failure(-3)));
    }

    @Test
    void successNeverBacksOff() {
        HobbyBackoffPolicy.Attempt ok = new HobbyBackoffPolicy.Attempt("woodcut", true, false, 7, NOW);
        assertEquals(NOW, HobbyBackoffPolicy.nextAllowedTick(ok));
    }

    @Test
    void abortNeverBacksOff() {
        HobbyBackoffPolicy.Attempt aborted = new HobbyBackoffPolicy.Attempt("woodcut", false, true, 7, NOW);
        assertEquals(NOW, HobbyBackoffPolicy.nextAllowedTick(aborted));
    }

    @Test
    void nullAttemptIsHarmless() {
        assertEquals(0L, HobbyBackoffPolicy.nextAllowedTick(null));
    }

    @Test
    void successResetsTheFailureCount() {
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(0, true));
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(9, true));
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(9, true, false));
    }

    @Test
    void failureIncrementsTheFailureCount() {
        assertEquals(1, HobbyBackoffPolicy.nextFailureCount(0, false));
        assertEquals(2, HobbyBackoffPolicy.nextFailureCount(1, false));
        assertEquals(4, HobbyBackoffPolicy.nextFailureCount(3, false, false));
    }

    @Test
    void abortDoesNotIncrementTheFailureCount() {
        assertEquals(3, HobbyBackoffPolicy.nextFailureCount(3, false, true));
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(0, false, true));
        // An abort on an otherwise-successful run still leaves the counter alone.
        assertEquals(5, HobbyBackoffPolicy.nextFailureCount(5, true, true));
    }

    @Test
    void failureCountSaturatesInsteadOfOverflowing() {
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT,
                HobbyBackoffPolicy.nextFailureCount(HobbyBackoffPolicy.MAX_FAILURE_COUNT, false));
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT,
                HobbyBackoffPolicy.nextFailureCount(Integer.MAX_VALUE, false));
        assertEquals(1, HobbyBackoffPolicy.nextFailureCount(-4, false));
    }

    @Test
    void repeatedFailuresWalkTheSequenceThenHoldAtTheCap() {
        // The storm scenario end to end: a woodcut that fails deterministically every time.
        int count = 0;
        long[] expected = {1200L, 2400L, 4800L, 9600L, 12_000L, 12_000L, 12_000L};
        for (int i = 0; i < expected.length; i++) {
            long next = HobbyBackoffPolicy.nextAllowedTick(failure(count));
            assertEquals(NOW + expected[i], next, "unexpected backoff at attempt " + (i + 1));
            count = HobbyBackoffPolicy.nextFailureCount(count, false, false);
        }
        assertEquals(expected.length, count);
    }

    @Test
    void describeNamesTheHobbyAndRemainingSeconds() {
        String text = HobbyBackoffPolicy.describe("woodcut", 2, 1200L);
        assertTrue(text.contains("woodcut"), text);
        assertTrue(text.contains("failures=2"), text);
        assertTrue(text.contains("60s"), text);
    }
}
