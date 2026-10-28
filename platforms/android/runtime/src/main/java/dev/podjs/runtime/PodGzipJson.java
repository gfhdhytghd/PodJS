package dev.podjs.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/** Generic bounded text projection. Runs on the SQL worker; only fingerprints
 * cross the bridge. No source mutation, temporary files or application schema. */
final class PodGzipJson {
    private static String hash(byte[] bytes,int start,int count) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(bytes,start,count);
        StringBuilder out=new StringBuilder(64);char[] hex="0123456789abcdef".toCharArray();
        for(byte b:digest.digest()){out.append(hex[(b&255)>>>4]);out.append(hex[b&15]);}return out.toString();
    }
    private static void quote(StringBuilder out,String value) {
        out.append('"');char[] hex="0123456789abcdef".toCharArray();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){
            case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '/':out.append("\\/");break;
            case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;
            default:if(c<32){out.append("\\u00").append(hex[c>>>4]).append(hex[c&15]);}else out.append(c);
        }}out.append('"');
    }
    static String json(JSONObject row,JSONArray fields,File root,int limit,Runnable check) throws Exception {
        if(fields.length()<1||fields.length()>8)throw new IllegalArgumentException("invalid_projection_fields");
        StringBuilder out=new StringBuilder("{");boolean comma=false;int inputs=0;
        for(int i=0;i<fields.length();i++){
            check.run();JSONObject field=fields.getJSONObject(i);String column=field.getString("column");
            if(!row.has(column))throw new IllegalArgumentException("missing_projection_column");
            Object raw=row.get(column);String value=raw==JSONObject.NULL?null:raw instanceof String?(String)raw:null;
            if(raw!=JSONObject.NULL&&!(raw instanceof String))throw new IllegalArgumentException("invalid_projection_text");
            if(value==null&&field.optBoolean("omitNull"))continue;
            if(value!=null){
                String prefix=field.optString("filePrefix","");
                if(!prefix.isEmpty()&&value.startsWith(prefix)){
                    String name=value.substring(prefix.length()),pattern=field.optString("filePattern","");
                    if(name.length()<=200&&!pattern.isEmpty()&&pattern.length()<=256&&name.matches(pattern)){
                        if(name.contains("/")||name.contains("\\")||name.contains(".."))throw new IllegalArgumentException("invalid_projection_file");
                        File file=new File(new File(root,field.getString("directory")),name).getCanonicalFile();
                        String base=root.getCanonicalPath()+File.separator;if(!file.getPath().startsWith(base))throw new IllegalArgumentException("projection_file_outside_root");
                        if(!file.isFile())throw new IOException("projection_file_unavailable");
                        if(file.length()>limit)return null;
                        ByteArrayOutputStream content=new ByteArrayOutputStream();try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){check.run();if(content.size()+count>limit)return null;content.write(buffer,0,count);}}value=new String(content.toByteArray(),StandardCharsets.UTF_8);
                    }
                }
                if(value.length()>limit||inputs+value.length()>limit)return null;inputs+=value.length();
            }
            if(comma)out.append(',');comma=true;quote(out,field.getString("name"));out.append(':');if(value==null)out.append("null");else quote(out,value);
            if(out.length()>limit)return null;
        }
        out.append('}');return out.toString();
    }
    static JSONObject fingerprint(JSONObject row,JSONArray fields,File root,String expected,int chunkSize,int limit,Runnable check) throws Exception {
        if(chunkSize<1||chunkSize>256*1024||limit<1||limit>1024*1024)throw new IllegalArgumentException("invalid_projection_limit");
        String json=json(row,fields,root,limit,check);if(json==null)return new JSONObject().put("oversized",true);
        byte[] plain=json.getBytes(StandardCharsets.UTF_8);if(plain.length>limit)return new JSONObject().put("oversized",true);check.run();
        ByteArrayOutputStream sink=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(sink)){gzip.write(plain);}byte[] zipped=sink.toByteArray();
        // Pin newly encoded members across Android/JVM releases. Existing
        // advertised OS variants still require an exact whole-body digest.
        for(int at=4;at<8;at++)zipped[at]=0;zipped[9]=(byte)255;
        String digest=hash(zipped,0,zipped.length);int original=zipped[9]&255;
        if(!expected.isEmpty()&&!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid_projection_hash");
        if(!expected.isEmpty()&&!expected.equals(digest)){
            boolean matched=false;for(int os:new int[]{0,3,255}){check.run();zipped[9]=(byte)os;String candidate=hash(zipped,0,zipped.length);if(expected.equals(candidate)){digest=candidate;matched=true;break;}}
            if(!matched)zipped[9]=(byte)original;
        }
        JSONArray chunks=new JSONArray();for(int at=0;at<zipped.length;at+=chunkSize){check.run();chunks.put(zipped.length<=chunkSize?digest:hash(zipped,at,Math.min(chunkSize,zipped.length-at)));}
        return new JSONObject().put("sha256",digest).put("size",zipped.length).put("chunkSize",chunkSize).put("chunkHashes",chunks);
    }
}
