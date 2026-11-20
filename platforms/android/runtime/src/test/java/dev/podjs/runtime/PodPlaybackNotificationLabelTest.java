package dev.podjs.runtime;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodPlaybackNotificationLabelTest {
    private static CharSequence notificationTitle(Service service) {
        Notification notification = Shadows.shadowOf(service).getLastForegroundNotification();
        assertNotNull(notification);
        return notification.extras.getCharSequence(Notification.EXTRA_TITLE);
    }

    @Test public void pagerPlaybackUsesTheHostingApplicationLabel() {
        Context context = ApplicationProvider.getApplicationContext();
        ExoPlayer player = new ExoPlayer.Builder(context).build();
        Object owner = new Object();
        ServiceController<PodSessionPlaybackService> controller =
            Robolectric.buildService(PodSessionPlaybackService.class).create();
        PodSessionPlaybackService service = controller.get();
        CharSequence previousLabel = service.getApplicationInfo().nonLocalizedLabel;
        try {
            service.getApplicationInfo().nonLocalizedLabel = "示例播放器";
            PodSessionPlaybackService.attach(context, owner, player, () -> {});
            controller.startCommand(0, 1);
            assertEquals("示例播放器", notificationTitle(service));
        } finally {
            service.getApplicationInfo().nonLocalizedLabel = previousLabel;
            PodSessionPlaybackService.detach(context, owner);
            controller.destroy();
            player.release();
        }
    }

    @Test public void standalonePlaybackUsesTheHostingApplicationLabel() {
        ServiceController<PodVideoPlaybackService> controller =
            Robolectric.buildService(PodVideoPlaybackService.class).create();
        PodVideoPlaybackService service = controller.get();
        CharSequence previousLabel = service.getApplicationInfo().nonLocalizedLabel;
        try {
            service.getApplicationInfo().nonLocalizedLabel = "Example Watch Player";
            controller.startCommand(0, 1);
            assertEquals("Example Watch Player", notificationTitle(service));
        } finally {
            service.getApplicationInfo().nonLocalizedLabel = previousLabel;
            controller.destroy();
        }
    }
}
