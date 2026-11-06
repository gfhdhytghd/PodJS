package dev.podjs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Historical 128-KiB reader for background packages and companion sync only.
 * Build output has a separate budget; other legacy readers retain their own limits. */
final class PodManifest {
    static final int MAX_BYTES=128*1024;
    /** Caller owns input. Count raw file bytes, including whitespace, before decoding. */
    static JSONObject read(InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int count;
        while((count=input.read(block))!=-1) {
            if(count>MAX_BYTES-bytes.size())throw new IllegalArgumentException("Manifest too large");
            bytes.write(block,0,count);
        }
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        return new JSONObject(json);
    }
}
