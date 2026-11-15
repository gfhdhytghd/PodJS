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
                            check(blocked.getProperty("phase").equals("ACTIVE"),"unknown silently discarded "+path);
                            File active=FileTakeoverTest.generation(out,blocked);check(!new File(active,"podjs/"+path).exists(),"unknown not imported "+path);
                            check(new File(out,"active").exists()&&before.equals(StoreTest.digests(unknown)),"native source retained "+path);
                        }finally{StoreTest.remove(unknown);StoreTest.remove(out);}
                    }
                } else if(scope.equals("store")) {
                    File stores=Files.createTempDirectory("unknown-store").toFile(),g=FileTakeoverTest.root();
                    try {
                        byte[] value=path.endsWith(".xml")?("<map><int name=\""+(expected.equals("known-difference")?"lastVersionCodeUsed":"unknown_key")+"\" value=\"1\"/></map>").getBytes(StandardCharsets.UTF_8):expected.equals("ready")?StoreTest.pref("unknown_key",StoreTest.concat(StoreTest.var(24),StoreTest.var(1))):new byte[0];
                        StoreTest.write(new File(stores,path),value);Map<String,String> before=StoreTest.digests(stores);
                        PodStoreConverter store=new PodStoreConverter(stores,new StoreTest.Crypto());store.snapshot(g);store.copy(g);
                        JSONObject snapshot=PodStoreConverter.json(new File(g,"stores-snapshot.json"));check(snapshot.getJSONObject("entries").has(path),"store delegated "+path);
                        JSONObject reports=PodStoreConverter.json(new File(g,"stores-report.json"));
                        if(expected.equals("settings")||expected.equals("bili_cache"))check(reports.getJSONObject(expected).getString("activationDisposition").equals("ready"),"known store migrated "+path);
                        else if(expected.equals("ready")) {
                            int skipped=0;for(String key:PodFileConverter.names(reports)){JSONObject report=reports.getJSONObject(key);if(report.getString("status").equals("discarded-unknown")){skipped+=report.getInt("unknownFileCount");check(report.getString("activationDisposition").equals("ready"),"unknown store cannot block "+path);}}
                            check(skipped==1,"exact unknown store skipped "+path);
                            check(!PodStoreConverter.json(new File(g,"podjs/podjs-kv.json")).toString().contains("unknown_key"),"unknown store content not imported "+path);
                        } else {boolean found=false;for(String key:PodFileConverter.names(reports))if(reports.getJSONObject(key).getString("activationDisposition").equals(expected))found=true;check(found,"store policy "+path);}
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
    static void coldUpgradeBackupAndRollback()throws Exception {
        File source=FileTakeoverTest.fixture(),control=new File(source,"files/package-takeover");
        String cert="a".repeat(64),old="g-old-unknown";
        try {
            for(String path:new String[]{"files/imports/PRIVATE_TITLE.txt","files/unreviewed.tmp","shared_prefs/PRIVATE_STORE.xml","files/datastore/PRIVATE.preferences_pb"})
                StoreTest.write(new File(source,path),"opaque malformed payload".getBytes(StandardCharsets.UTF_8));
            Map<String,String> nativeBefore=StoreTest.digests(source);File oldCandidate=new File(control,"generations/"+old);
            StoreTest.write(new File(oldCandidate,"retained.part"),new byte[]{7,8,9});
            PodStoreConverter.json(new File(oldCandidate,"files-report.json"),new JSONObject().put("version","watchrss-files-6").put("blockers",new JSONArray().put("unknown_user_files")));
            Properties legacy=new Properties();legacy.setProperty("protocol","1");legacy.setProperty("phase","BLOCKED");legacy.setProperty("reason","unknown_user_files");legacy.setProperty("generation",old);
            legacy.setProperty("converter","watchrss-room-v18-1+watchrss-stores-4+watchrss-files-6");PodTakeoverCoordinator.atomic(new File(control,"journal"),legacy);
            Map<String,String> oldBytes=StoreTest.digests(oldCandidate);
            try {new PodTakeoverCoordinator(control,FileTakeoverTest.probe,FileTakeoverTest.composite(source,b->{},new StoreTest.Crypto()),phase->{if(phase==PodTakeoverCoordinator.Phase.DETECTED)throw new FileTakeoverTest.PowerLoss();}).run();throw new AssertionError("missing restart interruption");}
            catch(FileTakeoverTest.PowerLoss expected){}
            Properties interrupted=PodTakeoverCoordinator.read(new File(control,"journal"));check(!old.equals(interrupted.getProperty("generation")),"upgrade auto-created fresh candidate");
            dev.podjs.takeover.NativeBackup backup=new dev.podjs.takeover.NativeBackup(source);
            List<PodTakeoverCoordinator.Phase> phases=new ArrayList<>();
            PodTakeoverCoordinator.Publisher publisher=new PodTakeoverCoordinator.Publisher(){public void beforePublish(File generation)throws Exception{backup.prepare(generation.getName(),46,cert);}};
            Properties active=new PodTakeoverCoordinator(control,FileTakeoverTest.probe,FileTakeoverTest.composite(source,b->{},new StoreTest.Crypto()),phases::add,publisher).run();
            check(active.getProperty("phase").equals("ACTIVE"),"cold startup continues without retry button");
            check(interrupted.getProperty("generation").equals(active.getProperty("generation")),"interrupted upgrade resumes same new candidate");
            check(!phases.contains(PodTakeoverCoordinator.Phase.BLOCKED),"unknown files never create a blocker");
            File g=FileTakeoverTest.generation(control,active),data=new File(source,dev.podjs.takeover.NativeBackup.BACKUP+"/"+g.getName()+"/data");
            for(Map.Entry<String,String> entry:nativeBefore.entrySet())if(!entry.getKey().startsWith("cache/"))check(entry.getValue().equals(StoreTest.digests(data).get(entry.getKey())),"sealed native bytes including unknown: "+entry.getKey());
            check(oldBytes.equals(StoreTest.digests(oldCandidate)),"old blocked candidate retained");
            JSONObject files=PodStoreConverter.json(new File(g,"files-report.json"));check(files.getInt("unknownFiles")==4&&files.getJSONArray("blockers").length()==0,"only private unknown diagnostics");
            check(!files.toString().contains("PRIVATE"),"no private filename in public file report");
            JSONObject summary=PodTakeoverSupport.summary(source);
            check(summary.getBoolean("diagnosticsAvailable")&&summary.getInt("unknownFileCount")==4&&summary.getString("fileConverter").equals("watchrss-files-7"),"current converter diagnostics remain usable");
            check(summary.getJSONObject("classes").getInt("unknown-store-file")==2&&!summary.toString().contains("PRIVATE"),"store categories and paths are safe in support diagnostics");
            JSONObject kv=PodStoreConverter.json(new File(g,"podjs/podjs-kv.json"));check(!kv.toString().contains("opaque malformed payload"),"unknown stores never imported");
            for(String path:new String[]{"imports/PRIVATE_TITLE.txt","unreviewed.tmp"})check(!new File(g,"podjs/files/"+path).exists(),"unknown file absent from new business files");
            check(Files.readString(new File(g,"podjs/files/article_content/fixture.txt").toPath()).contains("合成正文"),"known article content migrated");
            check(new File(g,"podjs/files/notes/assets/shared.png").isFile()&&new File(g,"podjs/files/reader_assets/fonts/font.ttf").isFile(),"known notes/reader assets migrated");
            String seal=backup.verify(g.getName());File body=new File(g,"podjs/files/article_content/fixture.txt");Files.writeString(body.toPath(),"new user edit");
            Properties cold=new PodTakeoverCoordinator(control,()->{throw new AssertionError("ACTIVE must not reimport");},FileTakeoverTest.composite(source,b->{},new StoreTest.Crypto())).run();
            check(cold.getProperty("phase").equals("ACTIVE")&&Files.readString(body.toPath()).equals("new user edit"),"cold reopen preserves new edits");
            backup.restore(g.getName(),cert);check(backup.verify(g.getName()).equals(seal),"rollback keeps original backup seal");
            Map<String,String> restored=StoreTest.digests(source);for(Map.Entry<String,String> entry:nativeBefore.entrySet())check(entry.getValue().equals(restored.get(entry.getKey())),"rollback restored known and unknown bytes");
        }finally{StoreTest.remove(source);}
        System.out.println("UnknownFilesTest cold upgrade/kill/resume/native backup/rollback PASS");
    }
    static void oldUnknownStoreAndMixedBlockers()throws Exception {
        for(String mode:new String[]{"unknown-store","mixed-stale","mixed-business-error"}) {
            File source=FileTakeoverTest.fixture(),control=new File(source,"files/package-takeover");
            try {
                String path="shared_prefs/PRIVATE_VENDOR.xml",old="g-old-"+mode;
                StoreTest.write(new File(source,path),"malformed opaque unknown preference".getBytes(StandardCharsets.UTF_8));
                if(mode.equals("mixed-business-error"))Files.delete(new File(source,"files/article_content/fixture.txt").toPath());
                Map<String,String> before=StoreTest.digests(source);File g=new File(control,"generations/"+old);
                JSONArray blockers=new JSONArray();if(!mode.equals("unknown-store"))blockers.put("unknown_user_files").put("article_body_missing");
                PodStoreConverter.json(new File(g,"files-report.json"),new JSONObject().put("version","watchrss-files-5").put("blockers",blockers));
                PodStoreConverter.json(new File(g,"stores-snapshot.json"),new JSONObject().put("version","watchrss-stores-4").put("entries",new JSONObject().put(path,"a".repeat(64))));
                PodStoreConverter.json(new File(g,"stores-diagnostics.json"),new JSONObject().put("blockerCount",1));
                Properties legacy=new Properties();legacy.setProperty("protocol","1");legacy.setProperty("phase","BLOCKED");legacy.setProperty("reason",mode.equals("unknown-store")?"store_unsupported_or_failed":"unknown_user_files");legacy.setProperty("generation",old);
                legacy.setProperty("converter","watchrss-room-v18-1+watchrss-stores-4+watchrss-files-5");PodTakeoverCoordinator.atomic(new File(control,"journal"),legacy);
                Properties result=FileTakeoverTest.run(source,control);
                check(!old.equals(result.getProperty("generation")),"reclassifies actual files instead of old blocker labels "+mode);
                if(mode.equals("mixed-business-error"))check(result.getProperty("phase").equals("BLOCKED")&&result.getProperty("reason").equals("article_body_missing"),"known business failure remains actionable");
                else check(result.getProperty("phase").equals("ACTIVE"),"legacy unknown store automatically continues "+mode);
                Map<String,String> after=StoreTest.digests(source);for(Map.Entry<String,String> e:before.entrySet())check(e.getValue().equals(after.get(e.getKey())),"all old sources retained "+mode);
                JSONObject report=PodStoreConverter.json(new File(FileTakeoverTest.generation(control,result),"files-report.json"));check(report.getInt("unknownFiles")==1,"unknown store counted");
                check(!report.toString().contains("PRIVATE_VENDOR"),"unknown store diagnostics redacted");
            }finally{StoreTest.remove(source);}
        }
        System.out.println("UnknownFilesTest old unknown stores/mixed blockers PASS 3");
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
    public static void main(String[] args)throws Exception {if(!args[0].equals("--support")){paths(args[0]);coldUpgradeBackupAndRollback();oldUnknownStoreAndMixedBlockers();}support();redaction();}
}
