package dev.podjs.runtime;

import android.view.MotionEvent;

/** A JS-recognized pager can follow the current pointer on the UI thread.
 * Recognition, target identity, cancellation and commit remain with the pager. */
final class PodVideoDragInput {
    private int pointerId = -1;
    private float downY, currentY;
    private boolean down, armed;

    boolean accept(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointerId = event.getPointerId(0);
                downY = currentY = event.getY(0);
                down = true; armed = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (!down || event.getPointerCount() != 1 || index < 0) { disarm(); return false; }
                currentY = event.getY(index);
                return armed;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false; disarm();
                return false;
            default: return false;
        }
    }

    boolean arm() { armed = down; return armed; }
    boolean tracking() { return down && armed; }
    float distanceY() { return currentY - downY; }
    void disarm() { armed = false; }
}
