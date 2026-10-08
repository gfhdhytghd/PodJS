package dev.podjs.takeover;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shared UTF-8/binary/empty fixture used by Python's streaming tar test. */
public final class TakeoverDigestTest {
    public static void main(String[] args)throws Exception {
        Properties manifest=new Properties();List<String> rows=Files.readAllLines(Paths.get(args[0]),StandardCharsets.UTF_8);
        int i=0;
        for(String row:rows) {
            String[] cells=row.split("\t",-1);byte[] content=new byte[cells[1].length()/2];
            for(int n=0;n<content.length;n++)content[n]=(byte)Integer.parseInt(cells[1].substring(n*2,n*2+2),16);
            manifest.setProperty("entry."+i+".type","file");manifest.setProperty("entry."+i+".path",cells[0]);
            manifest.setProperty("entry."+i+".size",Integer.toString(content.length));
            manifest.setProperty("entry."+i+".sha256",hex(MessageDigest.getInstance("SHA-256").digest(content)));i++;
        }
        manifest.setProperty("entries",Integer.toString(i));
        List<String> lines=TakeoverDigest.digestManifest(manifest,"SNAPSHOTTED","g-fixture");
        Properties reversed=new Properties();reversed.setProperty("entries",Integer.toString(i));
        for(int n=0;n<i;n++)for(String key:new String[]{"type","path","size","sha256"})reversed.setProperty("entry."+(i-n-1)+"."+key,manifest.getProperty("entry."+n+"."+key));
        if(!lines.equals(TakeoverDigest.digestManifest(reversed,"SNAPSHOTTED","g-fixture")))throw new AssertionError("order unstable");
        String empty=hex(MessageDigest.getInstance("SHA-256").digest());
        if(lines.stream().noneMatch(line->line.contains("\"root\":\"no_backup\",\"files\":0,\"bytes\":0,\"sha256\":\""+empty+"\"")))throw new AssertionError("empty root");
        for(String line:lines) {
            if(line.contains("cookie")||line.contains("account")||line.contains(".bin")||line.contains(".xml")||line.contains("watchrss.db")||line.contains("synthetic"))throw new AssertionError("private metadata");
            System.out.println(line);
        }
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
