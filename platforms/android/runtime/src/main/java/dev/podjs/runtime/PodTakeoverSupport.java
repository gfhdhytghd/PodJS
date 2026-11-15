package dev.podjs.runtime;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Pre-admission support. Never reads source payloads or exports private converter reports. */
final class PodTakeoverSupport {
    static final String CONSENT="log-upload-2026-11";
    interface Transport { Response request(String method,String url,Map<String,String> headers,byte[] body)throws Exception; }
    interface Receipts { JSONObject read()throws Exception; void save(JSONObject receipt)throws Exception; void remove()throws Exception; }
    static final class Response {
        final int status;final byte[] body;
        Response(int status,byte[] body){this.status=status;this.body=body;}
    }
    final String endpoint,publicKey;final Transport transport;final Receipts receipts;
    private JSONObject pending;
    java.util.function.UnaryOperator<String> redactor=value->value;
    static String generation(String value){if(value.isEmpty()||value.equals("unavailable"))return "unavailable";if(value.matches("<id:[0-9]{1,3}>"))return value;return value.matches("g-(?:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|v[0-9]{1,6}|test|fixture)")?value:"<id:"+Math.min(value.length(),255)+">";}
    static JSONObject classes(JSONObject input)throws Exception {
        JSONObject result=new JSONObject();if(input==null)return result;
        for(String name:Arrays.asList("asset","incomplete","refetchable","private-transfer-state","private-platform-state","private-log","private-test-state","platform-metadata","database-lock","cloud-unverified","unknown-user-content","native-transfer-checkpoint","inactive-profile-preserved"))
            if(input.has(name)){int count=input.getInt(name);if(count>=0)result.put(name,count);}
        return result;
    }
    synchronized JSONObject current()throws Exception{return pending!=null?pending:receipts.read();}
    PodTakeoverSupport(String endpoint,String publicKey,Transport transport,Receipts receipts)throws Exception {
        URI uri=new URI(endpoint);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()!=-1)throw new IOException("diagnostic_endpoint_invalid");
        this.endpoint=endpoint;this.publicKey=publicKey;this.transport=transport;this.receipts=receipts;
    }
    static long fileCount(JSONObject classes)throws Exception {long count=0;for(String name:PodFileConverter.names(classes))count=Math.addExact(count,classes.getLong(name));return classes.length()==0?-1:count;}
    static String phase(String value){return Arrays.asList("PAUSED","BLOCKED").contains(value)?value:"BLOCKED";}
    static String reason(String value){return Arrays.asList("unknown_user_files","store_unsupported_or_failed","stage_failed","space_insufficient","user_locked","migration_row_too_large","migration_timeout","migration_memory_limit","migration_interrupted","migration_admission_failed","target_conflict","store_needs_reauth_user_confirmation","migration_storage_invalid","verification_scratch_invalid","active_validation_failed","cloud_cache_binding_unverified","converter_changed_candidate_retained").contains(value)?value:"migration_unavailable";}
    static byte[] bytes(File file,int limit)throws Exception {
        PodFileConverter.safe(file.getParentFile(),file.getName());
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()>limit)throw new IOException("diagnostic_unavailable");
        try(FileChannel input=FileChannel.open(file.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer b=ByteBuffer.allocate(limit+1);while(b.hasRemaining()&&input.read(b)!=-1){}
            if(b.position()>limit)throw new IOException("diagnostic_too_large");return Arrays.copyOf(b.array(),b.position());
        }
    }
    static JSONObject summary(File sandbox)throws Exception {
        File control=PodFileConverter.safe(sandbox,"files/package-takeover");
        Properties journal=new Properties();try{journal.load(new ByteArrayInputStream(bytes(PodFileConverter.safe(control,"journal"),64*1024)));}catch(Exception unavailable){/* Failure before a journal exists still has a support exit. */}
        JSONObject result=new JSONObject().put("protocol","takeover-support-1").put("phase",phase(journal.getProperty("phase")))
            .put("reason",reason(journal.getProperty("reason")))
            .put("unknownFileCount",-1).put("paths",new JSONArray()).put("diagnosticsAvailable",false);
        String generation=journal.getProperty("generation","");
        result.put("generation",generation(generation)).put("classes",new JSONObject());
        if(!generation.matches("g-[A-Za-z0-9-]{1,128}"))return result;
        try {
            JSONObject report=new JSONObject(new String(bytes(PodFileConverter.safe(control,"generations/"+generation+"/files-report.json"),1024*1024),StandardCharsets.UTF_8));
            String version=report.getString("version");
            if(!Arrays.asList("watchrss-files-3","watchrss-files-4","watchrss-files-5","watchrss-files-6").contains(version))return result;
            int count=report.getInt("unknownFiles");if(count<0)throw new IOException("diagnostic_count_invalid");
            JSONArray paths=new JSONArray(),source=report.getJSONArray("unknownPaths");
            for(int i=0;i<Math.min(20,source.length());i++) {
                String path=source.getString(i);if(path.length()>4096)throw new IOException("diagnostic_path_invalid");
                paths.put(PodFileConverter.redactedPath(path));
            }
            if(paths.length()>count)throw new IOException("diagnostic_count_invalid");
            result.put("fileConverter",version).put("unknownFileCount",count).put("paths",paths)
                .put("pathsTruncated",count>paths.length()).put("classes",classes(report.optJSONObject("classes"))).put("diagnosticsAvailable",true);
            result.put("fileCount",fileCount(result.getJSONObject("classes")));
        }catch(Exception unavailable){/* No raw exception, path, key or report escapes. */}
        return result;
    }
    static long integer(JSONObject value,String name)throws Exception {
        Object number=value.get(name);if(!(number instanceof Number))throw new IOException("diagnostic_receipt_invalid");
        long result=((Number)number).longValue();if(result<0||result>9007199254740991L||((Number)number).doubleValue()!=result)throw new IOException("diagnostic_receipt_invalid");return result;
    }
    static JSONObject receipt(JSONObject r)throws Exception {
        if(!r.getString("id").matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")||
           !r.getString("code").matches("[1-9][0-9]{5}")||!r.getString("deleteToken").matches("[0-9a-fA-F]{64}")||
           integer(r,"fileSize")<0||integer(r,"charCount")<0||
           !Instant.parse(r.getString("expiresAt")).isAfter(Instant.parse(r.getString("createdAt"))))throw new IOException("diagnostic_receipt_invalid");
        return new JSONObject().put("id",r.getString("id")).put("code",r.getString("code")).put("deleteToken",r.getString("deleteToken"))
            .put("createdAt",r.getString("createdAt")).put("expiresAt",r.getString("expiresAt"))
            .put("fileSize",r.getLong("fileSize")).put("charCount",r.getLong("charCount"));
    }
    static final class Envelope { final byte[] body;final String key;Envelope(byte[] body,String key){this.body=body;this.key=key;} }
    static Envelope encrypt(String text,String publicKey)throws Exception {
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(compressed)){gzip.write(text.getBytes(StandardCharsets.UTF_8));}
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        try {
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));
            byte[] encrypted=aes.doFinal(compressed.toByteArray()),iv=aes.getIV();if(iv.length!=12)throw new IOException("diagnostic_iv_invalid");
            byte[] der=Base64.getDecoder().decode(publicKey.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""));
            Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE,KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
            byte[] wrapped=rsa.doFinal(key);if(wrapped.length!=256)throw new IOException("diagnostic_key_invalid");
            return new Envelope(ByteBuffer.allocate(1+iv.length+encrypted.length).put((byte)iv.length).put(iv).put(encrypted).array(),Base64.getEncoder().encodeToString(wrapped));
        }finally{Arrays.fill(key,(byte)0);}
    }
    synchronized JSONObject upload(JSONObject summary,boolean consent)throws Exception {
        if(!consent)throw new IOException("diagnostic_consent_required");
        if(current()!=null)throw new IOException("diagnostic_receipt_pending");
        // Rebuild a closed schema even if a caller passes a private report by mistake.
        JSONObject safe=new JSONObject().put("protocol","takeover-support-1").put("phase",phase(summary.optString("phase")))
            .put("reason",reason(summary.optString("reason")))
            .put("appVersionCode",summary.optLong("appVersionCode",0)).put("unknownFileCount",summary.optInt("unknownFileCount",-1))
            .put("diagnosticsAvailable",summary.optBoolean("diagnosticsAvailable",false));
        JSONArray paths=new JSONArray(),input=summary.optJSONArray("paths");
        if(input!=null)for(int i=0;i<Math.min(20,input.length());i++)paths.put(PodFileConverter.redactedPath(input.getString(i)));
        safe.put("paths",paths).put("pathsTruncated",safe.getInt("unknownFileCount")>paths.length())
            .put("generation",generation(summary.optString("generation",""))).put("classes",classes(summary.optJSONObject("classes")));
        safe.put("fileCount",fileCount(safe.getJSONObject("classes")));
        String appVersion=summary.optString("appVersionName","");if(appVersion.matches("[A-Za-z0-9._+-]{1,64}"))safe.put("appVersionName",appVersion);
        String version=summary.optString("fileConverter","");if(version.matches("watchrss-files-[3456]"))safe.put("fileConverter",version);
        String text=redactor.apply(safe.toString());Envelope envelope=encrypt(text,publicKey);
        Map<String,String> headers=new HashMap<>();headers.put("content-type","application/octet-stream");headers.put("x-log-key-envelope",envelope.key);
        headers.put("x-log-char-count",Integer.toString(text.length()));headers.put("x-log-consent-version",CONSENT);
        Response response=transport.request("POST",endpoint,headers,envelope.body);
        if(response.status<200||response.status>=300)throw new IOException("diagnostic_upload_failed");
        JSONObject r=receipt(new JSONObject(new String(response.body,StandardCharsets.UTF_8)));
        pending=r;
        try{receipts.save(r);pending=null;}catch(Exception failed){
            try{deleteRemote(r);pending=null;}catch(Exception rollback){throw new IOException("diagnostic_receipt_save_and_revoke_failed");}
            throw new IOException("diagnostic_receipt_save_failed_revoked");
        }
        return r;
    }
    private void deleteRemote(JSONObject r)throws Exception {
        Response response=transport.request("DELETE",endpoint+"/"+r.getString("id"),Collections.singletonMap("authorization","Bearer "+r.getString("deleteToken")),new byte[0]);
        if(response.status!=204&&response.status!=404)throw new IOException("diagnostic_delete_failed");
    }
    synchronized void delete()throws Exception {
        JSONObject r=current();if(r==null)return;deleteRemote(receipt(r));receipts.remove();pending=null;
    }
}
