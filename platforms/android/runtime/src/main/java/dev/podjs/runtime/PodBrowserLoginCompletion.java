package dev.podjs.runtime;

/** Per-authorization policy. No credentials or page content are retained. */
final class PodBrowserLoginCompletion {
    private boolean sawPanel;
    private boolean completed;

    boolean accept(PodBrowserAuthStatus snapshot, boolean hasRequiredCookies) {
        if (completed || snapshot.page == null || snapshot.page.optBoolean("loading", true)
                || !(snapshot.page.opt("loginPanelVisible") instanceof Boolean)) return false;
        boolean visible = snapshot.page.optBoolean("loginPanelVisible");
        if (visible) sawPanel = true;
        if (!sawPanel || visible || snapshot.verifying || snapshot.qr != null || !hasRequiredCookies) return false;
        completed = true;
        return true;
    }
}
