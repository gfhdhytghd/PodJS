package dev.podjs.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A bounded encrypted program runs in its caller's existing SQLite transaction.
 * No program may open/commit a transaction, attach databases, or nest programs.
 * Authentication/chain failures throw, rolling back every prior statement. */
public final class PodSqlProgram {
 private PodSqlProgram() {}
 public static boolean validateOnly(JSONArray statements) {
  for(int i=0;i<statements.length();i++){JSONObject s=statements.optJSONObject(i);JSONObject p=s==null?null:s.optJSONObject("program");if(p!=null&&p.optBoolean("validateOnly",false))return true;}return false;
 }
 private static String sha(String text)throws Exception {byte[] h=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte b:h)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
 private static String quote(String input){StringBuilder b=new StringBuilder("\"");for(int i=0;i<input.length();i++){char c=input.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c=='\b')b.append("\\b");else if(c=='\f')b.append("\\f");else if(c=='\n')b.append("\\n");else if(c=='\r')b.append("\\r");else if(c=='\t')b.append("\\t");else if(Character.isHighSurrogate(c)&&i+1<input.length()&&Character.isLowSurrogate(input.charAt(i+1))){b.append(c).append(input.charAt(++i));}else if(c<32||Character.isSurrogate(c))b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
 public static void execute(SQLiteDatabase db,JSONObject p,CancellationSignal cancellation)throws Exception {
  String binding=p.getString("binding"),id=p.getString("id"),expected=p.getString("root"),prefix=p.getString("aadPrefix");int pages=p.getInt("pages"),expectedFragments=p.getInt("fragments"),expectedOps=p.getInt("operations");long expectedBytes=p.getLong("bytes"),totalBytes=0;
  if(!expected.matches("[a-f0-9]{64}")||pages<0||pages>1000000||expectedFragments<0||expectedOps<0||expectedOps>1000000||expectedBytes<0||expectedBytes>9007199254740991L)throw new IllegalArgumentException("sql_program_invalid");
  try(Cursor c=db.rawQuery("SELECT status,pages,fragments,operations,root,bytes FROM podjs_sql_programs WHERE binding=? AND id=?",new String[]{binding,id})){
   if(!c.moveToFirst()||!"ready".equals(c.getString(0))||c.getInt(1)!=pages||c.getInt(2)!=expectedFragments||c.getInt(3)!=expectedOps||!expected.equals(c.getString(4))||c.getLong(5)!=expectedBytes)throw new IllegalStateException("sql_program_incomplete");
  }
  byte[] key=Base64.decode(p.getString("keyBase64"),Base64.DEFAULT);if(key.length!=32)throw new IllegalArgumentException("sql_program_key");
  LinkedHashMap<String,SQLiteStatement> prepared=new LinkedHashMap<>();String root=new String(new char[64]).replace('\0','0');int fragments=0,operations=0;
  try {for(int page=0;page<pages;page++){
   cancellation.throwIfCanceled();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();int parts=1;
   for(int part=0;part<parts;part++){
    String body;try(Cursor c=db.rawQuery("SELECT body FROM podjs_sql_program_pages WHERE binding=? AND id=? AND page=? AND part=?",new String[]{binding,id,Integer.toString(page),Integer.toString(part)})){if(!c.moveToFirst())throw new IllegalStateException("sql_program_incomplete");body=c.getString(0);}
    root=sha(root+":"+body.length()+":"+body);JSONObject box=new JSONObject(body);byte[] nonce=Base64.decode(box.getString("nonceBase64"),Base64.DEFAULT),sealed=Base64.decode(box.getString("ciphertextBase64"),Base64.DEFAULT);if(nonce.length!=12||sealed.length<16||sealed.length>65536)throw new IllegalArgumentException("sql_program_invalid");
    Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));String aad=prefix+":"+page+":"+part;aes.updateAAD((p.optBoolean("aadJson",false)?quote(aad):aad).getBytes(StandardCharsets.UTF_8));JSONObject value=new JSONObject(new String(aes.doFinal(sealed),StandardCharsets.UTF_8));
    int count=value.getInt("parts");if(value.getInt("page")!=page||value.getInt("part")!=part||count<1||count>256||part>0&&parts!=count)throw new IllegalArgumentException("sql_program_invalid");parts=count;bytes.write(Base64.decode(value.getString("data"),Base64.DEFAULT));fragments++;if(bytes.size()>2*1024*1024)throw new IllegalArgumentException("sql_program_invalid");
   }
   totalBytes+=bytes.size();JSONArray statements=new JSONArray(new String(bytes.toByteArray(),StandardCharsets.UTF_8));if(statements.length()>64)throw new IllegalArgumentException("sql_program_invalid");
   for(int i=0;i<statements.length();i++){
    cancellation.throwIfCanceled();JSONObject op=statements.getJSONObject(i);String sql=op.getString("sql"),head=sql.trim().toUpperCase(java.util.Locale.ROOT);if(op.has("program")||head.startsWith("BEGIN")||head.startsWith("COMMIT")||head.startsWith("ROLLBACK")||head.startsWith("ATTACH"))throw new IllegalArgumentException("sql_program_invalid");
    SQLiteStatement s=prepared.get(sql);if(s==null){if(prepared.size()>=100){String first=prepared.keySet().iterator().next();prepared.remove(first).close();}s=db.compileStatement(sql);prepared.put(sql,s);}s.clearBindings();JSONArray params=op.optJSONArray("params");if(params!=null)for(int n=0;n<params.length();n++){Object v=params.get(n);if(v==JSONObject.NULL)s.bindNull(n+1);else if(v instanceof Float||v instanceof Double)s.bindDouble(n+1,((Number)v).doubleValue());else if(v instanceof Number)s.bindLong(n+1,((Number)v).longValue());else if(v instanceof String)s.bindString(n+1,(String)v);else throw new IllegalArgumentException("SQL parameters must be string, number or null");}s.execute();operations++;
   }
  }if(!root.equals(expected)||fragments!=expectedFragments||operations!=expectedOps||totalBytes!=expectedBytes)throw new IllegalStateException("sql_program_invalid");
  }finally{Arrays.fill(key,(byte)0);for(SQLiteStatement s:prepared.values())s.close();}
 }
}
