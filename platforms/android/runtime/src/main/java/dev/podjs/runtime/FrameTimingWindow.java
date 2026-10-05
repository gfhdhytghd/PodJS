package dev.podjs.runtime;

import java.util.Arrays;

final class FrameTimingWindow {
    static final long REPORT_NANOS = 1_000_000_000L;
    private static final int CAPACITY = 256;

    static final class Distribution {
        final long p50;
        final long p95;
        final long max;

        Distribution(long p50, long p95, long max) {
            this.p50 = p50;
            this.p95 = p95;
            this.max = max;
        }
    }

    static final class Snapshot {
        final long windowNanos;
        final int frames;
        final Distribution callbackTotal;
        final Distribution nativeFrame;
        final Distribution accessibility;
        final Distribution effects;
        final Distribution notifications;

        Snapshot(long windowNanos, int frames, Distribution callbackTotal,
                Distribution nativeFrame, Distribution accessibility,
                Distribution effects, Distribution notifications) {
            this.windowNanos = windowNanos;
            this.frames = frames;
            this.callbackTotal = callbackTotal;
            this.nativeFrame = nativeFrame;
            this.accessibility = accessibility;
            this.effects = effects;
            this.notifications = notifications;
        }
    }

    private final long[] callbackTotal = new long[CAPACITY];
    private final long[] nativeFrame = new long[CAPACITY];
    private final long[] accessibility = new long[CAPACITY];
    private final long[] effects = new long[CAPACITY];
    private final long[] notifications = new long[CAPACITY];
    private int count;
    private long windowStartedNanos;

    Snapshot add(long frameBegan, long nativeDone, long accessibilityDone,
            long effectsDone, long frameDone) {
        if (windowStartedNanos == 0) windowStartedNanos = frameBegan;
        // 60 Hz produces about 60 samples per window. Saturating makes the
        // diagnostics safe on higher-refresh displays without per-frame growth.
        if (count < CAPACITY) {
            callbackTotal[count] = frameDone - frameBegan;
            nativeFrame[count] = nativeDone - frameBegan;
            accessibility[count] = accessibilityDone - nativeDone;
            effects[count] = effectsDone - accessibilityDone;
            notifications[count] = frameDone - effectsDone;
            count++;
        }
        if (frameDone - windowStartedNanos < REPORT_NANOS) return null;
        Snapshot result = new Snapshot(
                frameDone - windowStartedNanos,
                count,
                summarize(callbackTotal, count),
                summarize(nativeFrame, count),
                summarize(accessibility, count),
                summarize(effects, count),
                summarize(notifications, count));
        count = 0;
        windowStartedNanos = frameDone;
        return result;
    }

    private static Distribution summarize(long[] values, int length) {
        if (length == 0) return new Distribution(0, 0, 0);
        long[] sorted = Arrays.copyOf(values, length);
        Arrays.sort(sorted);
        return new Distribution(
                sorted[nearestRank(length, 50)],
                sorted[nearestRank(length, 95)],
                sorted[length - 1]);
    }

    private static int nearestRank(int length, int percentile) {
        int rank = (length * percentile + 99) / 100;
        return Math.max(1, rank) - 1;
    }
}
