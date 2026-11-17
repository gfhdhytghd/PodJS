package dev.podjs.runtime;

import java.io.IOException;

/** Optional per-source bound on both encoded video dimensions. Zero disables it. */
final class PodVideoShortEdge {
    static final String KEY = "maxVideoShortEdge";
    static final String ERROR = "resolution_limit";

    static boolean exceeds(int limit, int width, int height) {
        return limit > 0 && width > limit && height > limit;
    }

    static final class Rejected extends IOException {
        Rejected() { super(ERROR); }
    }
}
