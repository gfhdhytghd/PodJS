package dev.podjs.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one way the framework shows a toast.
 *
 * <p>On a round screen, while the app is in the foreground, the message is a dark pill whose four
 * corners fit the circle using the measured wrapped height. Everywhere else it is a
 * plain {@link Toast#makeText} toast: square screens keep the platform look, and since Android 11
 * the system drops custom-view toasts posted from the background but still shows text toasts.
 */
final class PodToast {
    static final int MAX_LINES = 4;
    static final int BACKGROUND = 0xdb1c1f21; // #1c1f21 at 86%, as the app's own overlays
    private static final float TEXT_SP = 14, PAD_X_DP = 14, PAD_Y_DP = 8, MARGIN_DP = 4, TOP_FRACTION = .17f;

    static final int DEFAULT_DURATION_MS = 3000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Main-thread ownership only. The timeout clears both references after cancellation.
    private static Toast current;
    private static Runnable expiry;

    private PodToast() {}

    static void show(Context context, CharSequence message) {
        show(context, message, DEFAULT_DURATION_MS);
    }

    static void show(Context context, CharSequence message, int durationMs) {
        // Neither a queued dispatch nor the active Toast may keep an Activity alive.
        Context application = context.getApplicationContext();
        String text = message.toString();
        Runnable display = () -> show(application, text, durationMs, foreground());
        if (Looper.myLooper() == Looper.getMainLooper()) display.run();
        else MAIN.post(display);
    }

    static Toast show(Context context, CharSequence message, int durationMs, boolean foreground) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Toast display must run on the main thread");
        if (expiry != null) MAIN.removeCallbacks(expiry);
        if (current != null) cancel(current);
        expiry = null;
        current = null;
        Context application = context.getApplicationContext();
        Toast toast = roundView(application.getResources().getConfiguration().isScreenRound(), foreground)
            ? round(application, message.toString(), Toast.LENGTH_LONG)
            : Toast.makeText(application, message.toString(), Toast.LENGTH_LONG);
        toast.show();
        current = toast;
        expiry = () -> {
            cancel(toast);
            current = null;
            expiry = null;
        };
        MAIN.postDelayed(expiry, Math.max(1000, Math.min(10000, durationMs)));
        return toast;
    }

    @SuppressWarnings("deprecation") // Only custom foreground toasts have a view.
    private static void cancel(Toast toast) {
        View view = toast.getView();
        if (view != null) {
            // Android's exit animation otherwise keeps a cancelled pill visible past its deadline.
            if (view.isAttachedToWindow() && view.getLayoutParams() instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                params.windowAnimations = 0;
                ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(view, params);
            }
            view.setVisibility(View.INVISIBLE);
        }
        toast.cancel();
    }

    /** Custom views are only safe in the foreground, and only needed on a round screen. */
    static boolean roundView(boolean screenRound, boolean foreground) { return screenRound && foreground; }

    static boolean foreground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        try { ActivityManager.getMyMemoryState(info); } catch (RuntimeException unavailable) { return false; }
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    @SuppressWarnings("deprecation") // setView: foreground only, see roundView().
    private static Toast round(Context context, CharSequence message, int duration) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        TextView text = pill(context, message, metrics);
        int top = Math.round(height * TOP_FRACTION);
        int margin = px(metrics, MARGIN_DP);
        PodRecoveryRoundLayout.Rect box = fit(text, width, height, top, margin);
        if (box == null) {
            text.setMaxLines(1);
            box = fit(text, width, height, top, margin);
        }
        if (box == null) return Toast.makeText(context, message, duration);
        text.setMaxWidth(box.width);
        Toast toast = new Toast(context);
        toast.setView(text); // Before setGravity, which warns on text toasts.
        toast.setDuration(duration);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, box.y);
        return toast;
    }

    /** An inline document can keep this pill clear of its caret as the caret moves.
     * The view has no input handlers and shares the ordinary toast's style and lifetime. */
    static Runnable showInDocument(FrameLayout parent, String message,
            Supplier<PodRecoveryRoundLayout.Rect> caretLine) {
        Context context = parent.getContext();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = parent.getWidth(), height = parent.getHeight();
        int margin = px(metrics, MARGIN_DP);
        int top = Math.round(height * TOP_FRACTION);
        TextView text = pill(context, message, metrics);
        boolean round = context.getResources().getConfiguration().isScreenRound();
        PodRecoveryRoundLayout.Rect box;
        if (round) {
            box = fit(text, width, height, top, margin);
        } else {
            int available = width - 2 * margin;
            box = available > 0
                ? new PodRecoveryRoundLayout.Rect(margin, top, available, heightAtWidth(text, available))
                : null;
        }
        if (box == null) return () -> {};
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(box.width, box.height);
        params.leftMargin = box.x;
        text.setFocusable(false);
        text.setClickable(false);
        text.setVisibility(View.INVISIBLE);
        parent.addView(text, params);

        PodRecoveryRoundLayout.Rect span = round
            ? PodRecoveryRoundLayout.verticalSpan(width, height, box.x, box.x + box.width, margin)
            : new PodRecoveryRoundLayout.Rect(box.x, margin, box.width, height - 2 * margin);
        ViewTreeObserver.OnPreDrawListener place = () -> {
            int bottom = Math.min(span.y + span.height, parent.getHeight() - margin);
            int y = Math.min(box.y, bottom - box.height);
            PodRecoveryRoundLayout.Rect line = caretLine.get();
            if (line != null && y < line.y + line.height && y + box.height > line.y) {
                int above = line.y - box.height;
                y = above >= span.y ? above : line.y + line.height;
            }
            boolean fits = y >= span.y && y + box.height <= bottom;
            if (fits) text.setTranslationY(y);
            text.setVisibility(fits ? View.VISIBLE : View.INVISIBLE);
            return true;
        };
        parent.getViewTreeObserver().addOnPreDrawListener(place);
        Runnable dismiss = () -> {
            parent.getViewTreeObserver().removeOnPreDrawListener(place);
            parent.removeView(text);
        };
        MAIN.postDelayed(dismiss, DEFAULT_DURATION_MS);
        return () -> {
            MAIN.removeCallbacks(dismiss);
            dismiss.run();
        };
    }

    private static TextView pill(Context context, CharSequence message, DisplayMetrics metrics) {
        TextView text = new TextView(context);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(MAX_LINES);
        text.setEllipsize(TextUtils.TruncateAt.END);
        int padX = px(metrics, PAD_X_DP), padY = px(metrics, PAD_Y_DP);
        text.setPadding(padX, padY, padX, padY);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(BACKGROUND);
        pill.setCornerRadius(text.getLineHeight() / 2f + padY);
        text.setBackground(pill);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return text;
    }

    private static int heightAtWidth(TextView text, int width) {
        text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return text.getMeasuredHeight();
    }

    private static PodRecoveryRoundLayout.Rect fit(TextView text, int width, int height, int top, int margin) {
        Map<Integer,Integer> heights = new HashMap<>();
        int minimumWidth = text.getPaddingLeft() + text.getPaddingRight() + text.getLineHeight();
        return PodRecoveryRoundLayout.safeTopBlock(width, height, available -> heights.computeIfAbsent(available, size -> heightAtWidth(text, size)), top, width - 2 * margin, height, minimumWidth, margin);
    }

    private static int px(DisplayMetrics metrics, float dp) { return Math.round(dp * metrics.density); }
}
