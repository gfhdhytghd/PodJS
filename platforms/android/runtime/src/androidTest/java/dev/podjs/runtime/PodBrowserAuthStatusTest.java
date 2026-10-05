package dev.podjs.runtime;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PodBrowserAuthStatusTest {
    private static String snapshot(String kind, String mime, boolean verifying) throws Exception {
        return new JSONObject().put("qr", new JSONObject().put("kind",kind).put("mime",mime).put("imageBase64","cXI="))
                .put("verifying",verifying).put("loading",false).put("loginPanelVisible",true).toString();
    }
    @Test public void recognizesBothQrTypesAndLegacyStrings() throws Exception {
        PodBrowserAuthStatus login=PodBrowserAuthStatus.parse(snapshot("login","image/png",false));
        assertEquals("login",login.qr.getString("kind"));
        assertEquals("data:image/png;base64,cXI=",login.dataImage());
        PodBrowserAuthStatus second=PodBrowserAuthStatus.parse(snapshot("secondVerify","image/jpeg",false));
        assertEquals("secondVerify",second.qr.getString("kind"));
        assertEquals("image/jpeg",second.qr.getString("mime"));
        assertEquals("secondVerify",PodBrowserAuthStatus.parse(JSONObject.quote(second.dataImage())).qr.getString("kind"));
        assertNull(PodBrowserAuthStatus.parse(snapshot("login","image/jpeg",false)).qr);
    }
    @Test public void verificationAlwaysRevealsWebViewAndMalformedSnapshotsClearQr() throws Exception {
        PodBrowserAuthStatus verifying=PodBrowserAuthStatus.parse(snapshot("secondVerify","image/jpeg",true));
        assertTrue(verifying.verifying);assertNull(verifying.qr);assertNull(verifying.dataImage());
        for(String bad:new String[]{null,"null","{}","broken",JSONObject.quote("https://example.org/qr.png"),snapshot("login","image/png",false).replace("cXI=","!!!!")})
            assertNull(PodBrowserAuthStatus.parse(bad).dataImage());
        String oversized="A".repeat(PodBrowserAuthStatus.MAX_IMAGE+1);
        assertNull(PodBrowserAuthStatus.parse(JSONObject.quote("data:image/png;base64,"+oversized)).qr);
    }
    @Test public void transitionsDeduplicateClearAndStopAfterEveryTerminalPath() throws Exception {
        List<JSONObject> events=new ArrayList<>();
        PodBrowserAuthStatus.Stream stream=new PodBrowserAuthStatus.Stream(events::add);
        PodBrowserAuthStatus login=PodBrowserAuthStatus.parse(snapshot("login","image/png",false));
        stream.accept(login);int initial=events.size();stream.accept(login);assertEquals(initial,events.size());
        stream.accept(PodBrowserAuthStatus.parse(snapshot("secondVerify","image/jpeg",true)));
        assertEquals("qrCleared",events.get(events.size()-1).getString("type"));
        stream.accept(PodBrowserAuthStatus.parse(snapshot("secondVerify","image/jpeg",false)));
        assertEquals("secondVerify",events.get(events.size()-1).getString("kind"));
        stream.accept(PodBrowserAuthStatus.parse("null"));assertEquals("qrCleared",events.get(events.size()-1).getString("type"));
        stream.end(true);int end=events.size();assertEquals("cancelled",events.get(end-1).getString("type"));
        stream.accept(login);stream.end(true);assertEquals(end,events.size());
        PodBrowserAuthStatus.Stream next=new PodBrowserAuthStatus.Stream(events::add);
        next.accept(login);assertEquals(end+initial,events.size());next.end(false);next.accept(login);
        assertEquals(end+initial,events.size());
    }
}
