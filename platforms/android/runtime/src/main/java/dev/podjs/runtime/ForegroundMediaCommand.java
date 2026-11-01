package dev.podjs.runtime;

import android.os.Handler;
import android.os.Looper;

/** Playback mutations execute in the input/effect turn, before any IO reply.
 * Ordered prepare/control dispatch shares this lane; secure/file/crypto stay on IO. */
final class ForegroundMediaCommand {
    static void dispatch(Handler main, String method, Runnable command) {
        boolean control = !method.contains(".frame.")
                && !method.endsWith(".captureFrame") && !method.endsWith(".captureBackdrop")
                && !method.endsWith(".capabilities");
        if (control && Looper.myLooper() == main.getLooper()) command.run();
        else main.post(command);
    }
}
