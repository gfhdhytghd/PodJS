package dev.podjs.runtime;

import org.json.JSONObject;
import android.util.Base64;

/** Internal worker-owned payload. Never serialized as part of the JSON lane. */
final class PodBytePayload {
    static byte[] input(JSONObject args) throws Exception {
        Object bytes=args.opt("_podBytes");
        if(args.optBoolean("_podByteMode")) {
            if(!(bytes instanceof byte[])||((byte[])bytes).length>65536)throw new IllegalArgumentException("Invalid byte attachment");
            return (byte[])bytes;
        }
        return Base64.decode(args.getString("dataBase64"),Base64.DEFAULT);
    }
    static JSONObject output(JSONObject args,JSONObject result,byte[] bytes) throws Exception {
        return args.optBoolean("_podByteMode")?result.put("_podBytes",bytes):result.put("dataBase64",Base64.encodeToString(bytes,Base64.NO_WRAP));
    }
}
