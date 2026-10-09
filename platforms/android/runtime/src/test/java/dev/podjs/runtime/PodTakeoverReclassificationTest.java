package dev.podjs.runtime;

import dev.podjs.takeover.NativeBackup;
import java.io.*;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real filesystem publication evidence must veto legacy reclassification. */
public final class PodTakeoverReclassificationTest {
    @Test public void publicationTreesVetoFreshClassification()throws Exception {
        File sandbox=Files.createTempDirectory("reclassification").toFile();
        try {
            PodTakeoverBackup publisher=new PodTakeoverBackup(null,sandbox);
            publisher.beforeReclassification();
            for(String name:new String[]{NativeBackup.BACKUP,NativeBackup.RECOVERED,"files/.podjs-restore-stage","files/.podjs-recovery-state"}) {
                File evidence=new File(sandbox,name);evidence.getParentFile().mkdirs();evidence.mkdir();
                try{publisher.beforeReclassification();fail("publication tree accepted");}catch(IOException expected){assertEquals("unexpected_activation",expected.getMessage());}
                assertTrue(evidence.isDirectory());assertTrue(evidence.delete());
            }
            File link=new File(sandbox,NativeBackup.BACKUP);Files.createSymbolicLink(link.toPath(),new File(sandbox,"missing").toPath());
            try{publisher.beforeReclassification();fail("dangling backup link accepted");}catch(IOException expected){assertEquals("unexpected_activation",expected.getMessage());}
            Files.delete(link.toPath());
        }finally{new File(sandbox,"files").delete();sandbox.delete();}
    }
    @Test public void unknownPathsRedactIdentifiersAndLogControls() {
        assertEquals("files/dir/<id:8>/<id:36>/<id:2>",PodFileConverter.redactedPath("files/dir/deadbeef/12345678-1234-1234-1234-123456789abc/42"));
        assertEquals("files/dir/line_break_",PodFileConverter.redactedPath("files/dir/line\nbreak\""));
        assertEquals("files/dir/<id:33>",PodFileConverter.redactedPath("files/dir/"+"z".repeat(33)));
    }
}
