package dev.podjs.runtime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Parse the real build-output buffers from the review-counterexample regressions. */
public final class PackageManifestReadTest {
    static int checks;
    static void check(boolean condition){checks++;if(!condition)throw new AssertionError();}
    static JSONObject read(Path file)throws Exception {
        try(InputStream in=Files.newInputStream(file)){return PodManifest.read(in);}
    }
    static void rejects(Path file)throws Exception {
        try{read(file);throw new AssertionError("accepted oversized manifest");}
        catch(IllegalArgumentException expected){check(expected.getMessage().equals("Manifest too large"));}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);
        check(PodManifest.MAX_BYTES==Integer.parseInt(args[1]));
        for(String name:new String[]{"r1-legacy","r1-fallback","exact-limit","newline-fallback"}) {
            JSONObject manifest=read(root.resolve(name+".json"));
            check(manifest.getInt("schema")==1);
            check(manifest.getString("target").equals("android-watch"));
            check(manifest.getJSONObject("background").length()==0);
            check(manifest.getJSONArray("capabilities").length()==0);
        }
        check(Files.size(root.resolve("r1-legacy.json"))==131000);
        check(Files.size(root.resolve("r1-expanded.json"))==131266);
        check(!read(root.resolve("r1-fallback.json")).getJSONObject("assets").getJSONObject("fonts/watch-sans.ttf").has("pakFont"));
        check(read(root.resolve("exact-limit.json")).getJSONObject("assets").getJSONObject("fonts/watch-sans.ttf").has("pakFont"));
        check(Files.size(root.resolve("exact-limit.json"))==PodManifest.MAX_BYTES);
        check(Files.size(root.resolve("newline-overflow.json"))==PodManifest.MAX_BYTES+1);
        check(Files.size(root.resolve("r2-appendix.json"))==2097153);
        rejects(root.resolve("r1-expanded.json"));
        rejects(root.resolve("newline-overflow.json"));
        rejects(root.resolve("r2-appendix.json"));
        // UTF-8 byte boundaries, not UTF-16 units; caller closes its own stream.
        byte[] invalid={(byte)0xc0,(byte)0xaf};
        try{PodManifest.read(new ByteArrayInputStream(invalid));throw new AssertionError("accepted invalid UTF-8");}
        catch(java.nio.charset.CharacterCodingException expected){checks++;}
        System.out.println("PackageManifestReadTest PASS: "+checks+" checks; shared 128-KiB contract, real writer outputs, R1/R2 rejection and fallback");
    }
}
