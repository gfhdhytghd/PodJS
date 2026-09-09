package dev.podjs.companion;

/** Source-compatible phone facade over the shared Android connection attempt. */
public final class PodLanAttempt extends dev.podjs.runtime.PodSyncLanAttempt {
    public PodLanAttempt(PodCompanion client,String peer,String[] channels,int timeoutMillis) {
        super(client,peer,channels,timeoutMillis);
    }
}
