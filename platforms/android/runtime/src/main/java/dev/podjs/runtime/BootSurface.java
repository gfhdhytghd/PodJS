package dev.podjs.runtime;

/** Surface identity and geometry captured by a completed background boot. */
final class BootSurface {
    static boolean needsResize(Object bootTexture, int bootWidth, int bootHeight,
            Object currentTexture, int currentWidth, int currentHeight, boolean rendererReady) {
        return !rendererReady || bootTexture != currentTexture
            || bootWidth != currentWidth || bootHeight != currentHeight;
    }
}
