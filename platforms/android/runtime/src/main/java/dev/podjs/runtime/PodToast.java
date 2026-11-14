package dev.podjs.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The one way the framework shows a toast.
 *
 * <p>On a round screen, while the app is in the foreground, the message is a dark pill whose four
 * corners stay inside the circle at its tallest ({@link #MAX_LINES} lines). Everywhere else it is a
 * plain {@link Toast#makeText} toast: square screens keep the platform look, and since Android 11
 * the system drops custom-view toasts posted from the background but still shows text toasts.
 */
final class PodToast {
    static final int MAX_LINES = 4;
    static final int BACKGROUND = 0xdb1c1f21; // #1c1f21 at 86%, as the app's own overlays
    private static final float TEXT_SP = 14, PAD_X_DP = 14, PAD_Y_DP = 8, MARGIN_DP = 4, TOP_FRACTION = .17f;

    private PodToast() {}

    static void show(Context context, CharSequence message, int duration) {
        show(context, message, duration, foreground());
    }

    static Toast show(Context context, CharSequence message, int duration, boolean foreground) {
        Toast toast = roundView(context.getResources().getConfiguration().isScreenRound(), foreground)
            ? round(context, message, duration) : Toast.makeText(context, message, duration);
        toast.show();
        return toast;
    }

    /** Custom views are only safe in the foreground, and only needed on a round screen. */
    static boolean roundView(boolean screenRound, boolean foreground) { return screenRound && foreground; }

    static boolean foreground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        try { ActivityManager.getMyMemoryState(info); } catch (RuntimeException unavailable) { return false; }
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /**
     * Widest centred box with its top edge at {@code top} and the given height whose corners all lie
     * at least {@code margin} inside the circle inscribed in {@code width}×{@code height}. The edge
     * farther from the centre limits the width. Returns 0 when no width fits.
     */
    static int chordWidth(int width, int height, int top, int boxHeight, int margin) {
        double cy = height / 2.0, inner = Math.min(width, height) / 2.0 - margin;
        double far = Math.max(Math.abs(top - cy), Math.abs(top + boxHeight - cy));
        double square = inner * inner - far * far;
        return square <= 0 ? 0 : (int) Math.floor(2 * Math.sqrt(square));
    }

    static boolean cornersInside(int width, int height, int left, int top, int boxWidth, int boxHeight) {
        double cx = width / 2.0, cy = height / 2.0, radius = Math.min(width, height) / 2.0;
        for (int x : new int[]{left, left + boxWidth}) for (int y : new int[]{top, top + boxHeight})
            if (Math.hypot(x - cx, y - cy) > radius + 1e-6) return false;
        return true;
    }

    @SuppressWarnings("deprecation") // setView: foreground only, see roundView().
    private static Toast round(Context context, CharSequence message, int duration) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        TextView text = new TextView(context);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(MAX_LINES);
        text.setEllipsize(TextUtils.TruncateAt.END);
        int padX = px(metrics, PAD_X_DP), padY = px(metrics, PAD_Y_DP);
        text.setPadding(padX, padY, padX, padY);
        int top = Math.round(height * TOP_FRACTION);
        int tallest = text.getLineHeight() * MAX_LINES + 2 * padY;
        int box = chordWidth(width, height, top, tallest, px(metrics, MARGIN_DP));
        if (box < 2 * padX + 4 * text.getLineHeight()) return Toast.makeText(context, message, duration);
        text.setMaxWidth(box);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(BACKGROUND);
        pill.setCornerRadius(text.getLineHeight() / 2f + padY);
        text.setBackground(pill);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        Toast toast = new Toast(context);
        toast.setView(text); // Before setGravity, which warns on text toasts.
        toast.setDuration(duration);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, top);
        return toast;
    }

    private static int px(DisplayMetrics metrics, float dp) { return Math.round(dp * metrics.density); }
}
