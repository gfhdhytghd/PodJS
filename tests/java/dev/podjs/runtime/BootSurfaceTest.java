package dev.podjs.runtime;

public final class BootSurfaceTest {
    public static void main(String[] args) {
        Object bootTexture = new Object(), replacement = new Object();
        assert !BootSurface.needsResize(bootTexture, 466, 466, bootTexture, 466, 466, true)
            : "A ready renderer on the boot surface must retain frame zero";
        assert BootSurface.needsResize(bootTexture, 466, 466, replacement, 466, 466, true)
            : "A replacement surface of equal size still requires attachment";
        assert BootSurface.needsResize(bootTexture, 466, 466, bootTexture, 480, 466, true);
        assert BootSurface.needsResize(bootTexture, 466, 466, bootTexture, 466, 480, true);
        assert BootSurface.needsResize(bootTexture, 466, 466, bootTexture, 466, 466, false)
            : "Invalid renderers must retain the recovery path";
        assert BootSurface.needsResize(bootTexture, 466, 466, replacement, 480, 480, false);
        System.out.println("BootSurface lifecycle tests passed");
    }
}
