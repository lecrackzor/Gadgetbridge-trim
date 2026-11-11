package nodomain.freeyourgadget.gadgetbridge.devices;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage;

public class ComputedHrvSummarySampleProviderTest {
    @Test
    public void lastSessionStartingBetweenPicksLatestInWindow() {
        final SleepSession early = session(1_000L, 2_000L);
        final SleepSession late = session(3_000L, 4_000L);
        final SleepSession outside = session(10_000L, 11_000L);

        final SleepSession picked = ComputedHrvSummarySampleProvider.lastSessionStartingBetween(
                Arrays.asList(early, late, outside),
                0L,
                5_000L
        );

        assertSame(late, picked);
    }

    @Test
    public void lastSessionStartingBetweenReturnsNullWhenEmpty() {
        assertNull(ComputedHrvSummarySampleProvider.lastSessionStartingBetween(
                Collections.emptyList(),
                0L,
                5_000L
        ));
    }

    @Test
    public void lastSessionStartingBetweenIgnoresSessionsAtWindowEnd() {
        final SleepSession atEnd = session(5_000L, 6_000L);
        final List<SleepSession> sessions = Collections.singletonList(atEnd);

        assertNull(ComputedHrvSummarySampleProvider.lastSessionStartingBetween(sessions, 0L, 5_000L));
        assertEquals(atEnd, ComputedHrvSummarySampleProvider.lastSessionStartingBetween(sessions, 0L, 5_001L));
    }

    private static SleepSession session(long startMs, long endMs) {
        return new SleepSession(Collections.singletonList(
                new SleepStage(ActivityKind.LIGHT_SLEEP, startMs, endMs)
        ));
    }
}
