package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodTakeoverDiagnosticsTest {
    @Test public void pausedReleaseHasOneSafeLineAndNoThrowable()throws Exception {
        File root=Files.createTempDirectory("paused-log-").toFile();
        IOException secret=new IOException("https://secret.invalid/token private-key-value",new IOException("private cause"));
        secret.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("java.io.FileInputStream","read","FileInputStream.java",8),
            new StackTraceElement("dev.podjs.runtime.PodFileConverter","snapshot","PodFileConverter.java",166),
            new StackTraceElement("dev.podjs.runtime.PodTakeoverCoordinator","run","PodTakeoverCoordinator.java",110)});
        PodTakeoverCoordinator.Converter converter=new PodTakeoverCoordinator.Converter() {
            public String version(){return "fixture";}
            public void snapshot(File g)throws Exception{throw secret;}
            public void resume(File g,PodTakeoverCoordinator.Phase p){}
            public void copy(File g){}
            public String verify(File g){return null;}
        };
        try {
            ShadowLog.clear();
            Properties p=new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(true,null,18,0,1),converter,
                new PodTakeoverCoordinator.Checkpoint() {
                    public void saved(PodTakeoverCoordinator.Phase p){}
                    public void failed(PodTakeoverCoordinator.Phase phase,Exception error){PodTakeoverDiagnostics.warn(phase.name(),error);}
                }).run();
            assertEquals("PAUSED",p.getProperty("phase"));
            assertEquals("DETECTED",p.getProperty("resumePhase"));
            List<ShadowLog.LogItem> logs=ShadowLog.getLogsForTag("PodTakeover");
            assertEquals(1,logs.size());assertEquals(android.util.Log.WARN,logs.get(0).type);
            assertEquals("phase=DETECTED exception=java.io.IOException frame=dev.podjs.runtime.PodFileConverter#snapshot:166",logs.get(0).msg);
            assertNull(logs.get(0).throwable);
            PodTakeoverDiagnostics.warn("ADMISSION",secret);
            assertEquals("phase=ADMISSION exception=java.io.IOException frame=dev.podjs.runtime.PodFileConverter#snapshot:166",ShadowLog.getLogsForTag("PodTakeover").get(1).msg);
            secret.setStackTrace(new StackTraceElement[0]);
            assertEquals("phase=ADMISSION exception=java.io.IOException frame=none",PodTakeoverDiagnostics.line("ADMISSION",secret));
            assertFalse(new String(Files.readAllBytes(new File(root,"journal").toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("secret"));
        }finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
