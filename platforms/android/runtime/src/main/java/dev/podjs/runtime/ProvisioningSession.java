package dev.podjs.runtime;

import java.io.Closeable;
import org.json.JSONObject;

/** One foreground, QR-authorized setup channel. Every transport carries the same encrypted
 * business wire; receipt is never durable success, the app responds only after saving. */
public interface ProvisioningSession extends Closeable {
    String INTERNET="internet";
    String BLUETOOTH="bluetooth";
    static String closeReason(Object value){return value instanceof String&&((String)value).matches("user_declined|user_back|background|timeout|error")?(String)value:"error";}
    void start() throws Exception;
    String qr() throws Exception;
    String sessionId();
    /** Legacy relays permit 0..15. An extension requires an authenticated server advertisement. */
    default int messageSequenceMax(){return 15;}
    default boolean ownershipReleaseSupported(){return false;}
    /** Bounded RPC within the owned session; watch capability stays native. */
    default JSONObject sessionRequest(String path,JSONObject body)throws Exception{throw new UnsupportedOperationException("session_rpc_unavailable");}
    JSONObject receive() throws Exception;
    /** Non-consuming liveness check, including while a request awaits user confirmation. */
    void checkActive() throws Exception;
    void respond(int sequence,JSONObject result) throws Exception;
    /** Optional diagnostic reason; older transports keep their close behavior. */
    default void close(String reason){close();}
    @Override void close();

    /** User-safe failure with a stable reason; never carries secrets, tokens or server bodies. */
    final class Failure extends IllegalStateException {
        public static final String OFFLINE="offline",EXPIRED="expired",CLOSED="closed",REJECTED="rejected";
        public final String reason;
        public Failure(String reason,String message){super(message);this.reason=reason;}
    }
}
