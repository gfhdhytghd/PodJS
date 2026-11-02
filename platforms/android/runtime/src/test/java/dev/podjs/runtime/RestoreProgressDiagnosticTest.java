package dev.podjs.runtime;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class RestoreProgressDiagnosticTest {
 JSONObject valid()throws Exception{JSONObject row=new JSONObject().put("event","restore_progress");for(String key:RestoreProgressDiagnostic.FIELDS)row.put(key,0);return row.put("version",1);}
 @Test public void emitsEveryFixedCounterAndNothingElse()throws Exception{String line=RestoreProgressDiagnostic.format(valid().put("applyDoneBytes",123456));assertNotNull(line);assertTrue(line.startsWith("restore_progress version=1 ready=0 full=0 "));assertTrue(line.contains("applyDoneBytes=123456"));assertEquals(22,line.split(" ").length);}
 @Test public void rejectsArbitraryTextExtraKeysAndIncompleteRecords()throws Exception{assertNull(RestoreProgressDiagnostic.format(valid().put("url","PRIVATE")));JSONObject missing=valid();missing.remove("downloadDone");assertNull(RestoreProgressDiagnostic.format(missing));assertNull(RestoreProgressDiagnostic.format(valid().put("ready","1")));}
 @Test public void rejectsNegativeFractionalAndUnsafeNumbers()throws Exception{for(Object bad:new Object[]{-1,1.5,9007199254740992L})assertNull(RestoreProgressDiagnostic.format(valid().put("headsTotal",bad)));assertNull(RestoreProgressDiagnostic.format(valid().put("full",2)));}
}
