package dev.podjs.runtime;
import java.nio.file.*;import javax.crypto.spec.SecretKeySpec;import org.json.JSONObject;
/** Real immutable-cache publications, not a mocked secure.get implementation. */
public final class SecurePagedReadTest {
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 static JSONObject page(PodDeviceServices d,int offset,Integer version)throws Exception{
  JSONObject a=new JSONObject().put("key","legacy").put("offset",offset).put("maxChars",16384);if(version!=null)a.put("version",version);return d.execute("secure.get",a);
 }
 public static void main(String[] args)throws Exception{
  Path p=Paths.get(args[0]);Files.createDirectories(p);
  try(PodDeviceServices reader=new PodDeviceServices(p.resolve("files").toFile(),()->new SecretKeySpec(new byte[32],"AES"));PodDeviceServices writer=new PodDeviceServices(p.resolve("files").toFile(),()->new SecretKeySpec(new byte[32],"AES"))){
   String expected="x".repeat(1024*1024-1)+"😀";
   writer.execute("secure.set",new JSONObject().put("key","legacy").put("value",expected));
   JSONObject first=page(reader,0,null);int version=first.getInt("version"),offset=0,pages=0;StringBuilder result=new StringBuilder();
   while(offset<expected.length()){
    if(pages<3)writer.execute("secure.set",new JSONObject().put("key","unrelated").put("value","rotation"+pages));
    JSONObject next=page(reader,offset,version);check(!next.optBoolean("changed"),"unrelated publication invalidated page");check(next.getInt("version")==version,"unrelated publication changed version");String part=next.getString("value");check(!part.isEmpty(),"page did not advance");result.append(part);offset+=part.length();pages++;
   }
   check(result.toString().equals(expected),"page bytes differ");
   writer.execute("secure.set",new JSONObject().put("key","legacy").put("value","short"));
   JSONObject stale=page(reader,16384,version);check(stale.optBoolean("changed"),"shrink did not fence stale offset");check(!stale.has("value"),"stale page exposed new bytes");
   JSONObject fresh=page(reader,0,null);check(fresh.getString("value").equals("short"),"fresh value missing");
   writer.execute("secure.delete",new JSONObject().put("key","legacy"));check(!page(reader,0,null).getBoolean("exists"),"delete left value");
   System.out.println("SECURE_PAGED_READ_PASS pages="+pages+" unrelatedWrites=3 surrogate=true shrink=true delete=true");
  }
 }
}
