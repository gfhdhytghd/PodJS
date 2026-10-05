package dev.podjs.runtime;

import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PodDebugPerformanceTest {
    private ContextWrapper context(boolean debug) {
        return new ContextWrapper(null) {
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info=new ApplicationInfo();info.flags=debug?ApplicationInfo.FLAG_DEBUGGABLE:0;return info;
            }
        };
    }
    @Test public void releaseRejectsEveryOperationBeforeLookingUpActivity() throws Exception {
        for(String operation:new String[]{"start","read","stop"}) {
            try {new PodDebugPerformance().execute(context(false),"debug.performance."+operation,new JSONObject());fail("release allowed "+operation);}
            catch(SecurityException expected) {assertTrue(expected.getMessage().contains("debug"));}
        }
    }
    @Test public void staleOwnerCannotReadOrStop() throws Exception {
        for(String operation:new String[]{"read","stop"}) {
            try {new PodDebugPerformance().execute(context(true),"debug.performance."+operation,new JSONObject().put("sessionId",123));fail("stale allowed");}
            catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("Stale"));}
        }
    }
    @Test public void emptySnapshotDoesNotInventFrames() throws Exception {
        JSONObject result=new PodDebugPerformance().execute(context(true),"debug.performance.read",new JSONObject().put("sessionId",0));
        assertEquals(0,result.getLong("frames"));assertEquals(0,result.getDouble("jankPct"),0);assertEquals(0,result.getDouble("avgMs"),0);
        assertEquals(9,result.getJSONObject("lastFrame").length());
    }
}
