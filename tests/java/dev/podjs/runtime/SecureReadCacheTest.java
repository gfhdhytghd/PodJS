package dev.podjs.runtime;

import java.nio.charset.StandardCharsets;

public final class SecureReadCacheTest {
    public static void main(String[] args) throws Exception {
        SecureReadCache cache = new SecureReadCache();
        int[] calls = {0};
        SecureReadCache.Decoder decoder = bytes -> {
            calls[0]++;
            if (bytes[0] == '!') throw new Exception("corrupt");
            return new String(bytes, StandardCharsets.UTF_8);
        };
        byte[] first = "old".getBytes(StandardCharsets.UTF_8);
        require(cache.read(first, decoder).equals("old"));
        for (int i = 0; i < 10000; i++) require(cache.read("old".getBytes(StandardCharsets.UTF_8), decoder).equals("old"));
        require(calls[0] == 1);
        first[0] = 'x'; // Own a copy, never retain a caller-mutable version token.
        require(cache.read(first, decoder).equals("xld"));
        require(cache.read("new".getBytes(StandardCharsets.UTF_8), decoder).equals("new"));
        // Equal length and equal mtime replacements still invalidate, including
        // an external process replacing the file while this host is suspended.
        try { cache.read("!ad".getBytes(StandardCharsets.UTF_8), decoder); throw new AssertionError(); }
        catch (Exception expected) { require(expected.getMessage().equals("corrupt")); }
        int before = calls[0];
        require(cache.read("new".getBytes(StandardCharsets.UTF_8), decoder).equals("new"));
        require(calls[0] == before + 1);
        cache.clear(); // File deletion, write and host close forget the projection.
        cache.read("new".getBytes(StandardCharsets.UTF_8), decoder);
        require(calls[0] == before + 2);
        System.out.println("SecureReadCacheTest passed (10000 unchanged reads, replacements, corruption, clear)");
    }
    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
}
