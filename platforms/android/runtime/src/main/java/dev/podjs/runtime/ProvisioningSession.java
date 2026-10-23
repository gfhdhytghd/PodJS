package dev.podjs.runtime;

import java.io.Closeable;
import org.json.JSONObject;

/** One foreground, QR-authorized setup channel. Every transport carries the same encrypted
 * business wire; receipt is never durable success, the app responds only after saving. */
public interface ProvisioningSession extends Closeable {
    String INTERNET="internet";
    String BLUETOOTH="bluetooth";
    void start() throws Exception;
    String qr() throws Exception;
    String sessionId();
    JSONObject receive() throws Exception;
    /** Non-consuming liveness check, including while a request awaits user confirmation. */
    void checkActive() throws Exception;
    void respond(int sequence,JSONObject result) throws Exception;
    @Override void close();

    /** User-safe failure with a stable reason; never carries secrets, tokens or server bodies. */
    final class Failure extends IllegalStateException {
        public static final String OFFLINE="offline",EXPIRED="expired",CLOSED="closed",REJECTED="rejected";
        public final String reason;
        public Failure(String reason,String message){super(message);this.reason=reason;}
    }
}
