package dev.podjs.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class FrameTimingWindowTest {
    @Test
    public void reportsNearestRankPercentilesAndResetsWindow() {
        FrameTimingWindow window = new FrameTimingWindow();
        FrameTimingWindow.Snapshot snapshot = null;
        long began = 1_000_000_000L;
        for (int i = 1; i <= 20; i++) {
            long duration = i * 1_000_000L;
            long frameBegan = began + (i - 1) * 52_000_000L;
            snapshot = window.add(
                    frameBegan,
                    frameBegan + duration,
                    frameBegan + duration + 100,
                    frameBegan + duration + 300,
                    frameBegan + duration + 600);
            if (i < 20) assertNull(snapshot);
        }
        assertEquals(20, snapshot.frames);
        assertEquals(10_000_600L, snapshot.callbackTotal.p50);
        assertEquals(19_000_600L, snapshot.callbackTotal.p95);
        assertEquals(20_000_600L, snapshot.callbackTotal.max);
        assertEquals(200L, snapshot.effects.p50);
        assertEquals(300L, snapshot.notifications.max);

        long next = 3_000_000_000L;
        assertNull(window.add(next, next + 1, next + 2, next + 3, next + 4));
    }
}
