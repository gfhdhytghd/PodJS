package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.util.function.Consumer;

/** A settled video can feed SurfaceFlinger without waking the application's ViewRoot
 * for every decoded frame. The existing texture remains the interactive output. Only
 * one output is connected to the decoder at a time. The last picture covers each switch. */
final class PodVideoDirectOutput {
    private static final long SETTLE_MS = 450;
    private static final long FIRST_FRAME_TIMEOUT_MS = 1500;
    private final Handler main;
    private final float[] coefficients = new float[9];
    private final Rect content = new Rect();
    private final Rect nextContent = new Rect();
    private FrameLayout layer;
    private TextureView texture;
    private ExoPlayer player;
    private Surface target;
    private boolean revealed, returning, switching;
    private long candidateSince = -1, suppressUntil, previousTimestamp;
    private AutoCloseable charge;
    private final Runnable timeout = this::timedOut;
    private final AnalyticsListener frames = new AnalyticsListener() {
        @Override public void onRenderedFirstFrame(EventTime time, Object output, long renderedMs) {
            if (target == null || output != target || returning || layer == null) return;
            main.removeCallbacks(timeout);
            revealed = true;
            placeAboveTexture();
        }
    };

    PodVideoDirectOutput(Handler main) { this.main = main; }

    // A SurfaceView scales to its layout rectangle. Rotation, cropping and pan outside
    // the page require the original texture matrix. Reject them before switching.
    static boolean contentRect(Matrix matrix, int w, int h, float[] m, Rect out) {
        if (matrix == null || w <= 0 || h <= 0) return false;
        matrix.getValues(m);
        if (Math.abs(m[Matrix.MSKEW_X]) > 0.00001f || Math.abs(m[Matrix.MSKEW_Y]) > 0.00001f
                || m[Matrix.MSCALE_X] <= 0 || m[Matrix.MSCALE_Y] <= 0
                || m[Matrix.MPERSP_0] != 0 || m[Matrix.MPERSP_1] != 0 || m[Matrix.MPERSP_2] != 1) return false;
        int x = Math.round(m[Matrix.MTRANS_X]), y = Math.round(m[Matrix.MTRANS_Y]);
        out.set(x, y, Math.round(m[Matrix.MSCALE_X] * w + m[Matrix.MTRANS_X]),
                    Math.round(m[Matrix.MSCALE_Y] * h + m[Matrix.MTRANS_Y]));
        return x >= 0 && y >= 0 && out.right <= w && out.bottom <= h && !out.isEmpty();
    }

    /** Called by the existing progress clock; it never installs a frame callback. */
    void update(ExoPlayer owner, TextureView view, Matrix matrix, boolean steady, long generation) {
        boolean eligible = steady && owner != null && view != null && view.isHardwareAccelerated()
                && view.isAttachedToWindow() && view.isShown() && view.getWindowVisibility() == View.VISIBLE && view.isAvailable()
                && view.getAlpha() == 1 && view.getTranslationX() == 0 && view.getTranslationY() == 0
                && view.getParent() instanceof FrameLayout
                && contentRect(matrix, view.getWidth(), view.getHeight(), coefficients, nextContent);
        if (!eligible || layer != null && (owner != player || view != texture || !nextContent.equals(content))) {
            interactive();
            return;
        }
        if (layer != null || !owner.isPlaying()) return;
        long now = SystemClock.uptimeMillis();
        if (now < suppressUntil) return;
        if (candidateSince < 0) { candidateSince = now; return; }
        if (now - candidateSince < SETTLE_MS) return;
        candidateSince = -1;
        // Account for the extra OS layer independently of the retained texture.
        VideoSize video = owner.getVideoSize();
        // The codec can retain its last full-resolution texture while the display
        // allocates its queue, even when the destination rectangle is small.
        long displayBytes = (long) nextContent.width() * nextContent.height() * 12;
        long retainedBytes = (long) video.width * video.height * 4;
        charge = PodMediaMemoryAccounting.reserveResource(this, generation, PodVideoCodecGate.OTHER,
                PodMediaMemoryBudget.Kind.SURFACE, Math.max(displayBytes, retainedBytes));
        if (charge == null) { suppressUntil = now + 5000; return; }
        player = owner;
        texture = view;
        content.set(nextContent);
        FrameLayout host = (FrameLayout) view.getParent();
        FrameLayout current = new FrameLayout(view.getContext());
        layer = current;
        current.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        current.setClickable(false);
        current.setFocusable(false);
        SurfaceView surface = new SurfaceView(view.getContext());
        surface.setClickable(false);
        surface.setFocusable(false);
        FrameLayout.LayoutParams picture = new FrameLayout.LayoutParams(content.width(), content.height());
        picture.leftMargin = content.left;
        picture.topMargin = content.top;
        current.addView(surface, picture);
        syncPose();
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (layer != current || returning) return;
                target = holder.getSurface();
                switching = true;
                try {
                    owner.setVideoSurface(target);
                    PodMediaMemoryAccounting.commitResource(charge);
                } catch (RuntimeException unavailable) {
                    main.post(() -> { if (layer == current) timedOut(); });
                } finally { switching = false; }
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (layer != current) return;
                // Reconnect the retained texture before the holder's Surface expires.
                restoreTexture();
                main.post(() -> { if (layer == current) discard(false); });
            }
        });
        owner.addAnalyticsListener(frames);
        try {
            // Keep the already latched texture above the unproven output.
            host.addView(current, host.indexOfChild(view), new FrameLayout.LayoutParams((FrameLayout.LayoutParams) view.getLayoutParams()));
            main.postDelayed(timeout, FIRST_FRAME_TIMEOUT_MS);
        } catch (RuntimeException unavailable) { discard(true); }
    }

    boolean active() { return layer != null && revealed && !returning; }
    boolean switchingOutput() { return switching; }
    boolean hasLayer() { return layer != null; }

    void syncPose() {
        if (layer == null) return;
        layer.setTranslationX(texture.getTranslationX());
        layer.setTranslationY(texture.getTranslationY());
        layer.setOutlineProvider(texture.getOutlineProvider());
        layer.setClipToOutline(texture.getClipToOutline());
        if (layer.getClipToOutline()) layer.invalidateOutline();
    }

    /** Preserve the relative order of the controls, cached frame and other pages. */
    void placeAboveTexture() {
        if (!revealed || layer == null || !(texture.getParent() instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) texture.getParent();
        if (layer.getParent() != host) return;
        int index = host.indexOfChild(texture);
        if (host.indexOfChild(layer) == index + 1) return;
        java.util.ArrayList<View> above = new java.util.ArrayList<>();
        for (int i = index + 1; i < host.getChildCount(); i++) {
            View sibling = host.getChildAt(i);
            if (sibling != layer) above.add(sibling);
        }
        host.bringChildToFront(layer);
        for (View sibling : above) host.bringChildToFront(sibling);
    }

    /** Hand back before a gesture starts. Keep the last direct picture until the
     * texture has acquired a buffer from its new connection. */
    void interactive() {
        candidateSince = -1;
        if (layer == null || returning) return;
        if (target == null) { discard(false); return; }
        restoreTexture();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, FIRST_FRAME_TIMEOUT_MS);
    }

    private void restoreTexture() {
        if (target == null || returning) return;
        returning = true;
        android.graphics.SurfaceTexture physical = texture.getSurfaceTexture();
        previousTimestamp = physical == null ? Long.MIN_VALUE : physical.getTimestamp();
        switching = true;
        try { player.setVideoTextureView(texture); }
        finally { switching = false; }
    }

    void textureUpdated(android.graphics.SurfaceTexture physical) {
        if (!returning || physical == null || physical.getTimestamp() == previousTimestamp) return;
        FrameLayout current = layer;
        main.post(() -> { if (layer == current && returning) discard(false); });
    }

    /** A hidden or released owner must not leave a window layer behind. */
    void discard(boolean restore) {
        candidateSince = -1;
        main.removeCallbacks(timeout);
        if (layer == null) return;
        if (restore) restoreTexture();
        FrameLayout previous = layer;
        layer = null;
        player.removeAnalyticsListener(frames);
        player = null; texture = null; target = null;
        revealed = returning = false;
        if (previous.getParent() instanceof ViewGroup) ((ViewGroup) previous.getParent()).removeView(previous);
        PodMediaMemoryAccounting.close(charge);
        charge = null;
    }

    private void timedOut() {
        if (layer == null) return;
        suppressUntil = SystemClock.uptimeMillis() + 5000;
        discard(true);
    }

    /** Captures the producer, excluding UI controls, using the same displayed
     * rectangle and size limit as the texture path. Caller owns the capture lease. */
    boolean capture(int maxEdge, Consumer<Bitmap> complete) {
        if (!revealed) return false;
        if (target == null || !target.isValid()) { complete.accept(null); return true; }
        final int w = texture.getWidth(), h = texture.getHeight();
        if (w <= 0 || h <= 0) { complete.accept(null); return true; }
        final float scale = Math.min(1f, maxEdge / (float) Math.max(w, h));
        final int width = Math.max(1, (int) (w * scale)), height = Math.max(1, (int) (h * scale));
        final Rect dst = new Rect(Math.round(content.left * scale), Math.round(content.top * scale),
                Math.round(content.right * scale), Math.round(content.bottom * scale));
        if (dst.isEmpty()) { complete.accept(null); return true; }
        final VideoSize video = player.getVideoSize();
        if (video.width < 1 || video.height < 1) { complete.accept(null); return true; }
        Bitmap pixels = null;
        try {
            pixels = Bitmap.createBitmap(dst.width(), dst.height(), Bitmap.Config.ARGB_8888);
            final Bitmap picture = pixels;
            final Surface expected = target;
            PixelCopy.request(expected, new Rect(0, 0, video.width, video.height), picture, status -> {
                Bitmap result = null;
                try {
                    if (status == PixelCopy.SUCCESS && target == expected) {
                        result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(result);
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(picture, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                    }
                } catch (RuntimeException | OutOfMemoryError unavailable) { result = null; }
                finally { picture.recycle(); }
                complete.accept(result);
            }, main);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            if (pixels != null) pixels.recycle();
            complete.accept(null);
        }
        return true;
    }
}
