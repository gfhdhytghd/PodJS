package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PodMediaServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void stateAndUnsupportedMethodsAreAsync() throws Exception {
        PodMediaServices services = new PodMediaServices(context());
        Result state = dispatch(services, "audio.state", new JSONObject());
        assertNull(state.error);
        assertEquals("idle", state.value.getString("state"));
        Result unsupported = dispatch(services, "video.start", new JSONObject());
        assertEquals("unsupported_method", unsupported.error);
        services.close();
    }

    @Test public void relativeMediaPathIsPrivateAndTraversalRejected() throws Exception {
        File root = new File(context().getFilesDir(), "podjs/files");
        File sample = new File(root, "media-test.raw");
        try (FileOutputStream out = new FileOutputStream(sample)) { out.write(new byte[] { 0, 1, 2, 3 }); }
        PodMediaServices services = new PodMediaServices(context());
        Result absolute = dispatch(services, "audio.start", new JSONObject().put("url", sample.getAbsolutePath()));
        assertEquals("invalid_argument", absolute.error);
        Result traversal = dispatch(services, "audio.start", new JSONObject().put("url", "../media-test.raw"));
        assertNotNull(traversal.error);
        services.close();
        assertTrue(sample.delete());
    }

    @Test public void privateTempMp3PlaysAndStopsThroughAudioService() throws Exception {
        File root = new File(context().getFilesDir(), "podjs/files");
        File directory = new File(root, "tts");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File sample = new File(directory, "speech-fixture.part");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-silence.mp3");
             FileOutputStream output = new FileOutputStream(sample)) {
            byte[] chunk = new byte[4096]; int read;
            while ((read = input.read(chunk)) >= 0) output.write(chunk, 0, read);
        }
        PodMediaServices services = new PodMediaServices(context());
        try {
            Result started = dispatch(services, "audio.start", new JSONObject().put("url", "tts/speech-fixture.part"));
            assertNull("MP3 preparation: " + started.message, started.error);
            assertEquals("playing", started.value.getString("state"));
            assertEquals("invalid_argument", dispatch(services, "audio.setSpeed", new JSONObject().put("speed", 3)).error);
            assertNull(dispatch(services, "audio.setSpeed", new JSONObject().put("speed", 1.25)).error);
            long deadline = System.currentTimeMillis() + 4000;
            String state = "playing";
            while (System.currentTimeMillis() < deadline) {
                Result current = dispatch(services, "audio.state", new JSONObject());
                assertNull(current.error);
                state = current.value.getString("state");
                if ("stopped".equals(state)) break;
                Thread.sleep(100);
            }
            assertEquals("stopped", state);
        } finally { services.close(); assertTrue(sample.delete()); }
    }

    @Test public void ttsInitializationCanRetryAndStopWithoutSpeaking() throws Exception {
        PodMediaServices services = new PodMediaServices(context());
        try {
            Result first = dispatch(services, "tts.init", new JSONObject().put("language", "zh-CN"));
            Result second = dispatch(services, "tts.init", new JSONObject().put("language", "zh-CN"));
            android.util.Log.i("PodMediaServicesTest", "TTS init=" + first.error + ", retry=" + second.error);
            if (first.error == null) {
                assertNull(second.error);
                assertTrue(second.value.getBoolean("initialized"));
            } else {
                assertNotNull("retry must report unavailable engine", second.error);
            }
            Result stopped = dispatch(services, "tts.stop", new JSONObject());
            assertNull(stopped.error);
            assertNotEquals("speaking", stopped.value.getString("state"));
        } finally { services.close(); }
    }

    @Test public void mediaVolumeUsesPhysicalIndicesAndReadsBackSystemPolicy() throws Exception {
        final int[] current = {9}, writes = {0}; final boolean[] fixed = {false};
        PodMediaServices.VolumeAccess access = new PodMediaServices.VolumeAccess() {
            public int min() { return 2; } public int max() { return 15; }
            public int current() { return current[0]; } public boolean fixed() { return fixed[0]; }
            public void set(int index) { writes[0]++; current[0] = Math.min(10, index); }
        };
        PodMediaServices services = new PodMediaServices(context(), event -> {}, access);
        try {
            Result read = dispatch(services, "audio.volumeState", new JSONObject());
            assertNull(read.error); assertEquals(2, read.value.getInt("min")); assertEquals(15, read.value.getInt("max"));
            for (Object value : new Object[] {-1, 1, 16, 2.5, "3", JSONObject.NULL}) {
                assertEquals("invalid_argument", dispatch(services, "audio.setVolumeIndex", new JSONObject().put("index", value)).error);
            }
            assertEquals(0, writes[0]);
            Result clamped = dispatch(services, "audio.setVolumeIndex", new JSONObject().put("index", 12));
            assertNull(clamped.error); assertEquals(10, clamped.value.getInt("current")); assertEquals(1, writes[0]);
            dispatch(services, "audio.setVolumeIndex", new JSONObject().put("index", 10)); assertEquals(1, writes[0]);
            fixed[0] = true;
            Result skipped = dispatch(services, "audio.setVolumeIndex", new JSONObject().put("index", 2));
            assertNull(skipped.error); assertTrue(skipped.value.getBoolean("fixed")); assertEquals(10, skipped.value.getInt("current")); assertEquals(1, writes[0]);
        } finally { services.close(); }
    }

    @Test public void mediaVolumeStateMatchesSystemWithoutChangingIt() throws Exception {
        android.media.AudioManager manager = (android.media.AudioManager)context().getSystemService(Context.AUDIO_SERVICE);
        PodMediaServices services = new PodMediaServices(context());
        try {
            Result read = dispatch(services, "audio.volumeState", new JSONObject());
            assertNull(read.error);
            assertEquals(manager.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC), read.value.getInt("min"));
            assertEquals(manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), read.value.getInt("max"));
            assertEquals(manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), read.value.getInt("current"));
            assertEquals(manager.isVolumeFixed(), read.value.getBoolean("fixed"));
        } finally { services.close(); }
    }

    private static Result dispatch(PodMediaServices services, String method, JSONObject args) throws Exception {
        Result result = new Result();
        CountDownLatch latch = new CountDownLatch(1);
        services.dispatch(method, args, new PodMediaServices.Callback() {
            @Override public void complete(JSONObject value) { result.value = value; latch.countDown(); }
            @Override public void fail(String code, String message) { result.error = code; result.message = message; latch.countDown(); }
        });
        assertTrue("callback timeout", latch.await(5, TimeUnit.SECONDS));
        return result;
    }
    private static final class Result { JSONObject value; String error; String message; }
}
