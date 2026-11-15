package dev.podjs.runtime;
import java.io.*;
import java.util.*;
import org.json.*;
public final class ReviewTakeoverProbe {
  public static void main(String[] args)throws Exception {
    for(String reason:List.of("article_body_missing","reader_font_missing","note_asset_path_invalid")) {
      String safe=PodTakeoverSupport.reason(reason);
      if(!safe.equals(reason))throw new AssertionError("fixed business reason lost");
      System.out.println("reason: "+reason+" -> "+safe);
    }
    for(String unsafe:List.of("java.io.IOException: article_body_missing", "files/article_body_missing", "article_body_missing\nPRIVATE", "private_custom_code"))if(!PodTakeoverSupport.reason(unsafe).equals("migration_unavailable"))throw new AssertionError("raw reason leaked");
    String fileSource=java.nio.file.Files.readString(java.nio.file.Path.of("platforms/android/runtime/src/main/java/dev/podjs/runtime/PodFileConverter.java"));
    java.util.regex.Matcher codes=java.util.regex.Pattern.compile("block\\(r,\"([a-z_]+)\"\\)").matcher(fileSource);int fixed=0;
    while(codes.find()){String code=codes.group(1);if(!PodTakeoverSupport.reason(code).equals(code))throw new AssertionError("unlisted public blocker "+code);fixed++;}
    if(fixed<16)throw new AssertionError("business blocker scan unexpectedly empty");
    Map<String,Object> input=Map.of("chapter_"+"a".repeat(64),"https://fixture.invalid/chapter/2");
    Set<String> unused=new TreeSet<>(input.keySet());JSONObject kv=new JSONObject(),secret=new JSONObject(),r=new JSONObject();
    PodStoreMappings.convert("novel_reading_history",input,unused,kv,secret,r);
    PodStorePolicy.retainKnown("novel_reading_history",input,unused,r);
    if(kv.length()!=0||secret.length()!=0||!unused.isEmpty()||r.getInt("retainedKnownKeyCount")!=1)throw new AssertionError("novel mapping changed");
    System.out.println("novel_reading_history: kv=0 secret=0 retainedKnown=1 retention="+r.getString("retention"));
    File secretSource=FileTakeoverTest.fixture(),secretControl=new File(secretSource,"files/package-takeover");
    try {
      String secretPath="shared_prefs/read_aloud_secure_prefs.xml";
      StoreTest.write(new File(secretSource,secretPath),"<map><string name=\"synthetic_encrypted_key\">synthetic_ciphertext</string></map>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Map<String,String> before=StoreTest.digests(secretSource);
      Properties state=FileTakeoverTest.run(secretSource,secretControl);
      File g=FileTakeoverTest.generation(secretControl,state);
      JSONObject reports=PodStoreConverter.json(new File(g,"stores-report.json"));
      JSONObject legacySecret=reports.getJSONObject("read_aloud_secure_prefs");
      if(!state.getProperty("phase").equals("ACTIVE")||!PodStoreConverter.knownFile(secretPath)||!legacySecret.getString("status").equals("retained-known"))throw new AssertionError("legacy secret classification changed");
      if(!before.get(secretPath).equals(StoreTest.digests(secretSource).get(secretPath)))throw new AssertionError("source changed");
      if(!legacySecret.getString("activationDisposition").equals("known-difference")||!legacySecret.getString("retention").equals("native-backup-no-import"))throw new AssertionError("retention policy lost");
      if(PodStoreConverter.knownFile("shared_prefs/account_"+"a".repeat(64)+"_read_aloud_secure_prefs.xml")||PodStoreConverter.knownFile("shared_prefs/vendor/read_aloud_secure_prefs.xml"))throw new AssertionError("retirement must match full path");
      if(PodStoreConverter.json(new File(g,"files-report.json")).getInt("unknownFiles")!=0)throw new AssertionError("known credential counted unknown");
      if(PodStoreConverter.json(new File(g,"podjs/podjs-kv.json")).toString().contains("synthetic_ciphertext")||new StoreTest.Crypto().open(java.nio.file.Files.readAllBytes(new File(g,"podjs/secure.store").toPath())).toString().contains("synthetic_ciphertext"))throw new AssertionError("retired secret imported");
      System.out.println("released legacy secret: knownFile=true status="+legacySecret.getString("status")+" phase="+state.getProperty("phase")+" originalBytesEqual=true");
    } finally {StoreTest.remove(secretSource);}
    File legacySource=FileTakeoverTest.fixture(),legacyControl=new File(legacySource,"files/package-takeover");
    try {
      String path="shared_prefs/read_aloud_secure_prefs.xml";StoreTest.write(new File(legacySource,path),new byte[]{1,2,3});File old=new File(legacyControl,"generations/g-review-retired");
      PodStoreConverter.json(new File(old,"stores-snapshot.json"),new JSONObject().put("version","watchrss-stores-4").put("entries",new JSONObject().put(path,"a".repeat(64))));
      PodStoreConverter.json(new File(old,"stores-diagnostics.json"),new JSONObject().put("blockerCount",1));
      PodStoreConverter.json(new File(old,"files-report.json"),new JSONObject().put("version","watchrss-files-5").put("blockers",new JSONArray()));
      Properties journal=new Properties();journal.setProperty("protocol","1");journal.setProperty("phase","BLOCKED");journal.setProperty("reason","store_unsupported_or_failed");journal.setProperty("generation","g-review-retired");journal.setProperty("converter","watchrss-room-v18-1+watchrss-stores-4+watchrss-files-5");PodTakeoverCoordinator.atomic(new File(legacyControl,"journal"),journal);
      Properties continued=FileTakeoverTest.run(legacySource,legacyControl);if(!continued.getProperty("phase").equals("ACTIVE")||continued.getProperty("generation").equals("g-review-retired"))throw new AssertionError("legacy retired credential blocker did not automatically continue");
      System.out.println("retired known store: legacy BLOCKED to ACTIVE without retry");
    }finally{StoreTest.remove(legacySource);}
    File source=FileTakeoverTest.fixture(),control=new File(source,"files/package-takeover");
    try {
      File old=new File(control,"generations/g-old-review");StoreTest.write(new File(old,"retained.part"),new byte[]{7,8});
      Properties active=FileTakeoverTest.run(source,control);
      if(!active.getProperty("phase").equals("ACTIVE")||!old.isDirectory())throw new AssertionError("fixture not active");
      PodInactiveGenerationCollector.Result collected=PodInactiveGenerationCollector.collect(source,List.of("inactive"),128);
      if(old.exists())throw new AssertionError("old candidate still present");
      System.out.println("inactive GC: old candidate retained before GC=true after GC=false deletedEntries="+collected.deletedEntries);
    } finally {StoreTest.remove(source);}
  }
}
