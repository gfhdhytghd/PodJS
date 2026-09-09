package dev.podjs.companion;

/** Source-compatible phone facade over the shared Android BLE attempt. */
public final class PodBleAttempt extends dev.podjs.runtime.PodSyncBleAttempt {
    public PodBleAttempt(PodCompanion client,String peer,String[] channels,int timeoutMillis) {
        super(client,peer,channels,timeoutMillis);
    }
}
