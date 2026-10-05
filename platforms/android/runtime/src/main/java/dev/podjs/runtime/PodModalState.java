package dev.podjs.runtime;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Separate host-owned transient state. No DOM, cookies, history Bundle or files.
 * A text draft crosses configuration changes only, never the saved Bundle. */
final class PodModalState {
    static final int MAX_BYTES = 32 * 1024;
    private JSONObject input;
    static String key(Object raw) {
        if (!(raw instanceof String) || !((String)raw).matches("[a-zA-Z0-9_.:-]{1,160}"))
            throw new IllegalArgumentException("Invalid input restoration key");
        return (String)raw;
    }
    synchronized void restore(String retained) {
        input = null;
        try {
            JSONObject state = decode(retained);
            if (state == null) return;
            if(!onlyKeys(state,"v","input"))return;
            JSONObject draft = state.optJSONObject("input");
            if (draft == null) return;
            if(!onlyKeys(draft,"key","title","text","start","end"))return;
            key(draft.opt("key"));
            if (!(draft.opt("text") instanceof String) || !(draft.opt("title") instanceof String)) return;
            for(String field:new String[]{"start","end"}) {
                Object value=draft.opt(field);
                if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())return;
            }
            int start = draft.getInt("start"), end = draft.getInt("end");
            String text = draft.getString("text");
            if (start < 0 || end < 0 || start > text.length() || end > text.length()) return;
            input = draft;
        } catch (Exception invalid) { input = null; }
    }
    synchronized JSONObject pending(String key) {
        return input != null && key.equals(input.optString("key")) ? copy(input) : null;
    }
    synchronized void update(String key, String title, String text, int start, int end) {
        try {
            JSONObject draft = new JSONObject().put("key",key(key)).put("title",title).put("text",text)
                .put("start",Math.max(0,Math.min(text.length(),start))).put("end",Math.max(0,Math.min(text.length(),end)));
            input = encode(new JSONObject().put("v",1).put("input",draft)) == null ? null : draft;
        } catch (Exception invalid) { input = null; }
    }
    synchronized void clear(String key) { if (input != null && key.equals(input.optString("key"))) input = null; }
    synchronized String retained() {
        try { return input == null ? null : encode(new JSONObject().put("v",1).put("input",input)); }
        catch (Exception invalid) { return null; }
    }
    static JSONObject decode(String raw) {
        try {
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;
            JSONObject state = new JSONObject(raw);
            return state.opt("v") instanceof Number && ((Number)state.opt("v")).doubleValue() == 1 ? state : null;
        } catch (Exception invalid) { return null; }
    }
    static boolean onlyKeys(JSONObject value,String... allowed) {
        for(java.util.Iterator<String> it=value.keys();it.hasNext();)if(!java.util.Arrays.asList(allowed).contains(it.next()))return false;
        return true;
    }
    static String encode(JSONObject state) {
        String raw = state.toString();
        return raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES ? raw : null;
    }
    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
