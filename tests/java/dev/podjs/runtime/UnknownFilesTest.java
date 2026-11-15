package dev.podjs.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Published writer matrix and pre-admission support, entirely synthetic and offline. */
public final class UnknownFilesTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static class ReceiptStore implements PodTakeoverSupport.Receipts {
        JSONObject value;boolean failSave;
        public JSONObject read(){return value;}
        public void save(JSONObject r)throws Exception{if(failSave)throw new IOException("fixture");value=r;}
        public void remove(){value=null;}
    }
    static JSONObject receipt()throws Exception{return new JSONObject().put("id","00000000-0000-0000-0000-000000000000").put("code","123456")
        .put("deleteToken","b".repeat(64)).put("createdAt","2026-11-15T00:00:00Z").put("expiresAt","2026-11-16T00:00:00Z").put("fileSize",100).put("charCount",10);}
    static void paths(String matrix)throws Exception {
        int count=0;File source=FileTakeoverTest.fixture(),target=FileTakeoverTest.root();
        try {
            PodFileConverter converter=FileTakeoverTest.files(source,b->{});
            for(String line:Files.readAllLines(Path.of(matrix))) {
                if(line.startsWith("#")||line.isBlank())continue;String[] row=line.split("\t");String scope=row[0],path=row[1],expected=row[2];count++;
                if(scope.equals("file")) {
                    check(converter.classify(path).equals(expected),"classification "+path);
                    if(Arrays.asList("refetchable","incomplete","native-transfer-checkpoint","private-transfer-state","private-platform-state").contains(expected))StoreTest.write(new File(source,path),new byte[]{1,2,3});
                    else if(expected.equals("unknown-user-content")) {
                        File unknown=FileTakeoverTest.fixture(),out=FileTakeoverTest.root();
                        try {
                            StoreTest.write(new File(unknown,path),new byte[]{4,5});Map<String,String> before=StoreTest.digests(unknown);
                            Properties blocked=FileTakeoverTest.run(unknown,out);
                            check(path.startsWith("files/accounts/")?!blocked.getProperty("phase").equals("ACTIVE"):blocked.getProperty("reason").equals("unknown_user_files"),"unknown still blocks "+path);
                            check(!new File(out,"active").exists()&&before.equals(StoreTest.digests(unknown)),"no data loss "+path);
                        }finally{StoreTest.remove(unknown);StoreTest.remove(out);}
                    }
                } else if(scope.equals("store")) {
                    File stores=Files.createTempDirectory("unknown-store").toFile(),g=FileTakeoverTest.root();
                    try {
                        byte[] value=path.endsWith(".xml")?("<map><int name=\""+(expected.equals("known-difference")?"lastVersionCodeUsed":"unknown_key")+"\" value=\"1\"/></map>").getBytes(StandardCharsets.UTF_8):expected.equals("blocked")?StoreTest.pref("unknown_key",StoreTest.concat(StoreTest.var(24),StoreTest.var(1))):new byte[0];
                        StoreTest.write(new File(stores,path),value);Map<String,String> before=StoreTest.digests(stores);
                        PodStoreConverter store=new PodStoreConverter(stores,new StoreTest.Crypto());store.snapshot(g);store.copy(g);
                        JSONObject snapshot=PodStoreConverter.json(new File(g,"stores-snapshot.json"));check(snapshot.getJSONObject("entries").has(path),"store delegated "+path);
                        JSONObject reports=PodStoreConverter.json(new File(g,"stores-report.json"));
                        if(expected.equals("settings")||expected.equals("bili_cache"))check(reports.getJSONObject(expected).getString("activationDisposition").equals("ready"),"known store migrated "+path);
                        else {boolean found=false;for(String key:PodFileConverter.names(reports))if(reports.getJSONObject(key).getString("activationDisposition").equals(expected))found=true;check(found,"store policy "+path);}
                        check(before.equals(StoreTest.digests(stores)),"store source unchanged");
                    }finally{StoreTest.remove(stores);StoreTest.remove(g);}
                } else {
                    // These roots never feed the file classifier; do not claim WebView cookie migration.
                    if(!scope.equals("room"))StoreTest.write(new File(source,path),new byte[]{1,2,3});
                }
            }
            Map<String,String> before=StoreTest.digests(source);
            PodFileConverter files=FileTakeoverTest.files(source,b->{});File snapshot=FileTakeoverTest.root();
            try{files.snapshot(snapshot);JSONObject entries=files.entries(snapshot);
                for(String line:Files.readAllLines(Path.of(matrix)))if(!line.startsWith("#")&&!line.isBlank()){
                    String[] row=line.split("\t");if(Arrays.asList("room","backup","outside").contains(row[0]))check(!entries.has(row[1]),"outside files inventory "+row[1]);
                }
            }finally{StoreTest.remove(snapshot);}
            Properties active=FileTakeoverTest.run(source,target);check(active.getProperty("phase").equals("ACTIVE"),"new known paths ACTIVE "+active);
            File g=FileTakeoverTest.generation(target,active);
            for(String line:Files.readAllLines(Path.of(matrix)))if(!line.startsWith("#")&&!line.isBlank()) {
                String[] row=line.split("\t");if(row[0].equals("file")&&Arrays.asList("refetchable","incomplete","native-transfer-checkpoint","private-transfer-state").contains(row[2]))
                    check(!new File(g,"podjs/"+row[1]).exists(),"no cache/partial import "+row[1]);
            }
            check(before.equals(StoreTest.digests(source)),"all source bytes retained");
            // A spoofed known cache shape still cannot exempt links or special files.
            File outside=Files.createTempFile("unknown-link-target","").toFile();Files.writeString(outside.toPath(),"unchanged");
            File link=new File(source,"files/douyin_unwatched_stock/"+"c".repeat(64)+".mp4");Files.createSymbolicLink(link.toPath(),outside.toPath());
            try{files.snapshot(FileTakeoverTest.root());throw new AssertionError("source link accepted");}catch(IOException expected){}
            check(Files.readString(outside.toPath()).equals("unchanged"),"link target retained");Files.delete(link.toPath());Files.delete(outside.toPath());
        }finally{StoreTest.remove(source);StoreTest.remove(target);}
        System.out.println("UnknownFilesTest path matrix PASS "+count);
    }
    static void support()throws Exception {
        File root=FileTakeoverTest.root();
        try {
            File control=new File(root,"files/package-takeover");Files.createDirectories(control.toPath());Properties journal=new Properties();journal.setProperty("generation","g-test");journal.setProperty("reason","unknown_user_files");PodTakeoverCoordinator.atomic(new File(control,"journal"),journal);
            JSONObject raw=new JSONObject().put("version","watchrss-files-4").put("unknownFiles",24).put("unknownPaths",new JSONArray().put("files/alice/token-BearerSecret123/title.txt")).put("classes",new JSONObject().put("unknown-user-content",24).put("alice-secret",9)).put("secret","do-not-upload").put("degraded",new JSONArray().put("private-data"));
            PodStoreConverter.json(new File(control,"generations/g-test/files-report.json"),raw);
            Map<String,String> before=StoreTest.digests(root);JSONObject summary=PodTakeoverSupport.summary(root);
            check(summary.getString("generation").equals("g-test")&&summary.getJSONObject("classes").getInt("unknown-user-content")==24,"generation and safe classes");
            check(summary.getInt("unknownFileCount")==24&&summary.getBoolean("pathsTruncated"),"count and truncation");
            for(String secret:new String[]{"alice","BearerSecret","title.txt","do-not-upload","private-data"})check(!summary.toString().contains(secret),"closed projection "+secret);
            check(before.equals(StoreTest.digests(root)),"support projection read only");
            KeyPairGenerator keygen=KeyPairGenerator.getInstance("RSA");keygen.initialize(2048);KeyPair pair=keygen.generateKeyPair();String publicKey=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            ReceiptStore store=new ReceiptStore();List<String> requests=new ArrayList<>();
            PodTakeoverSupport service=new PodTakeoverSupport("https://support.invalid/log-uploads",publicKey,(method,url,headers,body)->{
                requests.add(method);
                if(method.equals("POST")) {
                    check(headers.get("x-log-consent-version").equals(PodTakeoverSupport.CONSENT),"existing consent protocol");
                    Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,pair.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
                    byte[] key=rsa.doFinal(Base64.getDecoder().decode(headers.get("x-log-key-envelope")));check(body[0]==12&&key.length==32,"wire envelope");
                    Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(body,1,13)));
                    String text;try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(aes.doFinal(Arrays.copyOfRange(body,13,body.length))))){text=new String(gzip.readAllBytes(),StandardCharsets.UTF_8);}
                    check(Integer.parseInt(headers.get("x-log-char-count"))==text.length(),"char count");
                    check(!text.contains("alice")&&!text.contains("secret")&&!text.contains("raw-source"),"decrypted upload privacy");
                    check(new JSONObject(text).getInt("unknownFileCount")==24,"decrypted actionable summary");return new PodTakeoverSupport.Response(201,receipt().toString().getBytes(StandardCharsets.UTF_8));
                }
                check(method.equals("DELETE")&&headers.get("authorization").equals("Bearer "+"b".repeat(64)),"explicit receipt delete");return new PodTakeoverSupport.Response(204,new byte[0]);
            },store);
            try{service.upload(summary,false);throw new AssertionError("no consent accepted");}catch(IOException expected){}check(requests.isEmpty(),"no-consent zero requests");
            summary.put("raw-source","secret");JSONObject receipt=service.upload(summary,true);check(receipt.getString("code").equals("123456")&&store.value!=null,"receipt persisted");
            try{service.upload(summary,true);throw new AssertionError("duplicate receipt lost");}catch(IOException expected){}check(requests.size()==1,"no automatic repeated upload");
            // Recreated controller can delete the same durable receipt without re-uploading.
            PodTakeoverSupport reopened=new PodTakeoverSupport(service.endpoint,publicKey,service.transport,store);reopened.delete();check(store.value==null&&requests.equals(Arrays.asList("POST","DELETE")),"cold-reopen deletion");
            store.failSave=true;try{service.upload(summary,true);throw new AssertionError("receipt save failure");}catch(IOException expected){check(expected.getMessage().equals("diagnostic_receipt_save_failed_revoked"),"receipt revoked");}
            check(requests.equals(Arrays.asList("POST","DELETE","POST","DELETE")),"save failure rollback");
            ReceiptStore keep=new ReceiptStore();keep.value=receipt();
            PodTakeoverSupport failedDelete=new PodTakeoverSupport(service.endpoint,publicKey,(m,u,h,b)->new PodTakeoverSupport.Response(500,new byte[0]),keep);
            try{failedDelete.delete();throw new AssertionError("failed deletion discarded receipt");}catch(IOException expected){}
            check(keep.value!=null,"failed delete retains token privately");
            PodTakeoverSupport offline=new PodTakeoverSupport(service.endpoint,publicKey,(m,u,h,b)->{throw new IOException("offline fixture");},new ReceiptStore());
            try{offline.upload(summary,true);throw new AssertionError("offline upload succeeded");}catch(IOException expected){}check(offline.current()==null,"offline creates no receipt");
            ReceiptStore unsaved=new ReceiptStore();unsaved.failSave=true;boolean[] allowDelete={false};
            PodTakeoverSupport pending=new PodTakeoverSupport(service.endpoint,publicKey,(m,u,h,b)->new PodTakeoverSupport.Response(m.equals("POST")?201:allowDelete[0]?204:500,receipt().toString().getBytes(StandardCharsets.UTF_8)),unsaved);
            try{pending.upload(summary,true);throw new AssertionError("save/revoke failure hidden");}catch(IOException expected){check(expected.getMessage().equals("diagnostic_receipt_save_and_revoke_failed"),"revoke failed reason");}
            check(pending.current()!=null,"failed rollback keeps temporary self-delete token");allowDelete[0]=true;pending.delete();check(pending.current()==null,"temporary receipt can still self-delete");
            for(Object bad:new Object[]{-1,1.5,"100",9007199254740992L})try{PodTakeoverSupport.receipt(receipt().put("fileSize",bad));throw new AssertionError("bad numeric receipt accepted");}catch(IOException expected){}
            for(int status:new int[]{302,400,500}) {
                PodTakeoverSupport bad=new PodTakeoverSupport(service.endpoint,publicKey,(m,u,h,b)->new PodTakeoverSupport.Response(status,new byte[0]),new ReceiptStore());
                try{bad.upload(summary,true);throw new AssertionError("bad HTTP accepted");}catch(IOException expected){}
            }
            for(String url:new String[]{"http://support.invalid/upload","https://user@support.invalid/upload","https://support.invalid/upload?token=x","https://support.invalid:443/upload"})try{new PodTakeoverSupport(url,publicKey,service.transport,store);throw new AssertionError("unsafe endpoint");}catch(IOException expected){}
            Files.writeString(new File(control,"generations/g-test/files-report.json").toPath(),"bad json");check(!PodTakeoverSupport.summary(root).getBoolean("diagnosticsAvailable"),"broken report safe fallback");
            check(PodFileConverter.redactedPath("files/alice/bob/secret.tmp").equals("files/<id:5>/<id:3>/<id:10>"),"short identifiers hidden");
        }finally{StoreTest.remove(root);}
        System.out.println("UnknownFilesTest support PASS (privacy, decrypt, consent, retry, delete, errors)");
    }
    static void redaction()throws Exception {
        JSONObject fixture=new JSONObject(Files.readString(Path.of("tests/fixtures/feedback-redaction.json")));
        PodFeedbackLogRedactor redactor=new PodFeedbackLogRedactor(fixture.getString("sensitivePattern"),fixture.getString("headerPattern"));
        JSONArray vectors=fixture.getJSONArray("vectors");for(int i=0;i<vectors.length();i++) {
            JSONObject item=vectors.getJSONObject(i);check(redactor.apply(item.getString("raw")).equals(item.getString("expected")),"feedback redactor parity "+i);
        }
        System.out.println("UnknownFilesTest feedback redaction parity PASS "+vectors.length());
    }
    public static void main(String[] args)throws Exception {if(!args[0].equals("--support"))paths(args[0]);support();redaction();}
}
