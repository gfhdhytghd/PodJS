package dev.podjs.runtime;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PodSiteChallengePolicyTest {
    @Test public void exportsOnlyCompleteRequestedCookiesAndKeepsCookieValuesIntact() {
        assertEquals("challenge=ab==",PodSiteChallengeServices.selectCookies("sessionid=account-secret; challenge=ab==; other=private",Arrays.asList("challenge")));
        assertEquals("",PodSiteChallengeServices.selectCookies("sessionid=account-secret; challenge=",Arrays.asList("challenge")));
        assertEquals("",PodSiteChallengeServices.selectCookies("a=one",Arrays.asList("a","b")));
        assertEquals("a=one; b=two",PodSiteChallengeServices.selectCookies("b=two; a=one; sessionid=secret",Arrays.asList("a","b")));
        assertEquals("",PodSiteChallengeServices.selectCookies("a=bad\r\nheader",Arrays.asList("a")));
    }
    @Test public void cachedTokensBindToBothUserAgentAndCookie() throws Exception {
        String original=PodSiteChallengeServices.fingerprint("Reader A","challenge=one");
        assertNotEquals(original,PodSiteChallengeServices.fingerprint("Reader B","challenge=one"));
        assertNotEquals(original,PodSiteChallengeServices.fingerprint("Reader A","challenge=two"));
        assertEquals(original,PodSiteChallengeServices.fingerprint("Reader A","challenge=one"));
    }
}
