package dev.podjs.runtime;

import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Same concrete network causes as the native watch player, without leaking error text. */
final class PodPlaybackFailure {
    static boolean isNetwork(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof UnknownHostException || current instanceof ConnectException
                    || current instanceof NoRouteToHostException || current instanceof SocketTimeoutException
                    || current instanceof SSLException) return true;
        }
        return false;
    }
}
