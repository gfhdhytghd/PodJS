package dev.podjs.runtime;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.zip.GZIPOutputStream;import org.json.*;
public class GzipJsonTest {
 static void require(boolean yes){if(!yes)throw new AssertionError();}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
 public static void main(String[]args)throws Exception{
  File root=new File(args[0]);root.mkdirs();JSONObject input=new JSONObject(new String(Files.readAllBytes(new File(root,"input.json").toPath()),StandardCharsets.UTF_8));JSONArray fields=input.getJSONArray("fields"),cases=input.getJSONArray("cases"),out=new JSONArray();
  JSONArray articleFields=new JSONArray().put(new JSONObject().put("name","contentHtml").put("column","a").put("omitNull",true)).put(new JSONObject().put("name","contentText").put("column","b"));
  for(int i=0;i<cases.length();i++){JSONObject row=cases.getJSONObject(i),r=PodGzipJson.fingerprint(row,fields,root,"",131072,1048576,()->{});String json=PodGzipJson.json(row,fields,root,1048576,()->{});r.put("utf8Hex",hex(json.getBytes(StandardCharsets.UTF_8)));JSONObject article=PodGzipJson.fingerprint(row,articleFields,root,"",131072,1048576,()->{});article.put("utf8Hex",hex(PodGzipJson.json(row,articleFields,root,1048576,()->{}).getBytes(StandardCharsets.UTF_8)));r.put("article",article);out.put(r);}
  JSONObject row=cases.getJSONObject(1);String json=PodGzipJson.json(row,fields,root,1048576,()->{});ByteArrayOutputStream b=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(b)){gzip.write(json.getBytes(StandardCharsets.UTF_8));}byte[] z=b.toByteArray();z[9]=0;String expected=hex(MessageDigest.getInstance("SHA-256").digest(z));JSONObject matched=PodGzipJson.fingerprint(row,fields,root,expected,131072,1048576,()->{});require(expected.equals(matched.getString("sha256")));require(expected.equals(matched.getJSONArray("chunkHashes").getString(0)));
  require(!"0".repeat(64).equals(PodGzipJson.fingerprint(row,fields,root,"0".repeat(64),131072,1048576,()->{}).getString("sha256")));
  require(PodGzipJson.fingerprint(new JSONObject().put("a","x".repeat(300)).put("b",""),fields,root,"",128,32,()->{}).getBoolean("oversized"));
  boolean cancelled=false;try{PodGzipJson.fingerprint(row,fields,root,"",128,1048576,()->{throw new IllegalStateException("cancelled");});}catch(IllegalStateException e){cancelled=true;}require(cancelled);
  JSONArray files=new JSONArray().put(new JSONObject().put("name","text").put("column","a").put("filePrefix","@file:").put("filePattern","[A-Za-z.]+\\.txt").put("directory","texts"));new File(root,"texts").mkdir();Files.write(new File(root,"texts/ok.txt").toPath(),"file 中文 /".getBytes(StandardCharsets.UTF_8));
  require(PodGzipJson.json(new JSONObject().put("a","@file:ok.txt"),files,root,1048576,()->{}).equals("{\"text\":\"file 中文 \\/\"}"));
  boolean missing=false;try{PodGzipJson.json(new JSONObject().put("a","@file:missing.txt"),files,root,1048576,()->{});}catch(IOException e){missing=true;}require(missing);
  File outside=File.createTempFile("outside",".txt");try{Files.createSymbolicLink(new File(root,"texts/link.txt").toPath(),outside.toPath());boolean refused=false;try{PodGzipJson.json(new JSONObject().put("a","@file:link.txt"),files,root,1048576,()->{});}catch(IllegalArgumentException e){refused=true;}require(refused);}finally{outside.delete();}
  long started=System.nanoTime();for(int i=0;i<2135;i++)PodGzipJson.fingerprint(row,fields,root,"",131072,1048576,()->{});long ms=(System.nanoTime()-started)/1000000;require(ms<10000);
  Files.write(new File(root,"output.json").toPath(),out.toString().getBytes(StandardCharsets.UTF_8));System.out.println("GzipJsonTest PASS: Android JSON/UTF16, owned files, OS digest recovery, mismatch, bounds, cancellation, 2135 fingerprints="+ms+"ms");
 }
}
