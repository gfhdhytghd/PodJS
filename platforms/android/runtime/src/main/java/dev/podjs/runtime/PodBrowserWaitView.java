package dev.podjs.runtime;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Host-owned waiting UI over the browser area while a login QR is expected.
 * Native WebViewStyleLoadingIndicator: ProgressRingView filling the area, 12px round-capped stroke,
 * #476CFF progress from -90 degrees, no base ring; message bodySmall 12/14sp white with a soft
 * black shadow, centred with 88dp top and 20dp horizontal padding. */
final class PodBrowserWaitView extends FrameLayout {
    private final Ring ring;
    private final TextView message;

    PodBrowserWaitView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        ring = new Ring(context);
        addView(ring, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        message = new TextView(context);
        message.setTextColor(Color.WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        message.setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 2, context.getResources().getDisplayMetrics()), 1f);
        message.setShadowLayer(6f, 0f, 1f, Color.argb(166, 0, 0, 0));
        message.setGravity(Gravity.CENTER);
        FrameLayout padded = new FrameLayout(context);
        padded.setPadding(Math.round(20 * density), Math.round(88 * density), Math.round(20 * density), 0);
        padded.addView(message, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(padded, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /** Applies {@code {visible, message}} from {@link PodBrowserAuthStatus.Stream#qrWaitStatus()}. */
    void apply(boolean visible, String text) {
        message.setText(text);
        setVisibility(visible ? VISIBLE : GONE);
        if (visible) ring.start(); else ring.stop();
    }

    void release() { ring.stop(); setVisibility(GONE); }

    private static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private ValueAnimator animator;
        private float progress;

        Ring(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(12f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFF476CFF);
        }

        void start() {
            if (animator != null) return;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1600);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
            animator.start();
        }

        void stop() {
            if (animator != null) animator.cancel();
            animator = null;
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) / 2f - 6f;
            if (radius <= 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(bounds, -90f, progress * 360f, false, paint);
        }

        @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    }
}
