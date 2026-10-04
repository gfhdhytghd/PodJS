package dev.podjs.runtime;

import android.view.InputDevice;
import android.view.MotionEvent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PodRuntimeViewMotionTest {
    private static MotionEvent scrollEvent(int source, int axis, float value) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(axis, value);
        return MotionEvent.obtain(
            1L, 1L, MotionEvent.ACTION_SCROLL, 1,
            new MotionEvent.PointerProperties[] { properties },
            new MotionEvent.PointerCoords[] { coords },
            0, 0, 1f, 1f, 0, 0, source, 0
        );
    }

    @Test public void rawScrollPreservesUnscaledMouseAndRotaryValues() {
        for (int source : new int[]{InputDevice.SOURCE_MOUSE,InputDevice.SOURCE_ROTARY_ENCODER}) {
            MotionEvent event=scrollEvent(source,MotionEvent.AXIS_VSCROLL,-400f);
            try {assertEquals(-400f,PodRuntimeView.rawScrollDelta(event),0f);} finally {event.recycle();}
        }
        MotionEvent fallback=scrollEvent(InputDevice.SOURCE_ROTARY_ENCODER,MotionEvent.AXIS_SCROLL,.125f);
        try {assertEquals(.125f,PodRuntimeView.rawScrollDelta(fallback),0f);} finally {fallback.recycle();}
    }
    @Test public void rawScrollRejectsZeroAndNonfiniteValues() {
        for(float value:new float[]{0f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            MotionEvent event=scrollEvent(InputDevice.SOURCE_MOUSE,MotionEvent.AXIS_VSCROLL,value);
            try {assertTrue(Float.isNaN(PodRuntimeView.rawScrollDelta(event)));assertTrue(Float.isNaN(PodRuntimeView.scrollDegrees(event)));} finally {event.recycle();}
        }
    }

    @Test
    public void testMouseWheelUsesVerticalScrollAxis() {
        MotionEvent event = scrollEvent(InputDevice.SOURCE_MOUSE, MotionEvent.AXIS_VSCROLL, -1f);
        try {
            assertEquals(15f / 48f, PodRuntimeView.scrollDegrees(event), 0f);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void testMouseWheelScrollAxisFallbackIsAlsoScaled() {
        MotionEvent event = scrollEvent(InputDevice.SOURCE_MOUSE, MotionEvent.AXIS_SCROLL, -1f);
        try {
            assertEquals(15f / 48f, PodRuntimeView.scrollDegrees(event), 0f);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void testRotaryEncoderFallsBackToScrollAxis() {
        MotionEvent event = scrollEvent(
            InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL, 1f
        );
        try {
            assertEquals(-15f, PodRuntimeView.scrollDegrees(event), 0f);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void testScrollWithoutVerticalMotionIsNotClaimed() {
        MotionEvent event = scrollEvent(
            InputDevice.SOURCE_MOUSE, MotionEvent.AXIS_VSCROLL, 0f
        );
        try {
            assertTrue(Float.isNaN(PodRuntimeView.scrollDegrees(event)));
        } finally {
            event.recycle();
        }
    }

    @Test
    public void testOppoMarkerMatchesManufacturerAndFingerprintWithoutFalsePositives() {
        assertTrue(PodRuntimeView.isOppoMarker("OPPO"));
        assertTrue(PodRuntimeView.isOppoMarker("oplus_watch_1.0/release"));
        assertTrue(!PodRuntimeView.isOppoMarker("samsung/wearable"));
    }
}
