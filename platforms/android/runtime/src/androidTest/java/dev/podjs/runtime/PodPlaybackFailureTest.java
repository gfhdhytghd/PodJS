package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;

public class PodPlaybackFailureTest {
    @Test public void matchesNativeConcreteCauseChainWithoutGuessingFromMessages() {
        for (Throwable cause : new Throwable[]{new UnknownHostException(), new ConnectException(),
                new NoRouteToHostException(), new SocketTimeoutException(), new SSLException("test")})
            assertTrue(PodPlaybackFailure.isNetwork(new RuntimeException("wrapper", cause)));
        assertFalse(PodPlaybackFailure.isNetwork(new IOException("timeout")));
        assertFalse(PodPlaybackFailure.isNetwork(new RuntimeException("network offline")));
        assertFalse(PodPlaybackFailure.isNetwork(null));
    }
    @Test public void malformedCyclicCauseChainTerminates() {
        Throwable first = new Throwable(), second = new Throwable(first); first.initCause(second);
        assertFalse(PodPlaybackFailure.isNetwork(first));
    }
}
