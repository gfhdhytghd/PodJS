package dev.podjs.runtime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Only the complete Room/store/file bundle can publish. Individual converters remain non-publishable. */
final class PodTakeoverConverters implements PodTakeoverCoordinator.Converter {
    private final PodTakeoverCoordinator.Converter room,stores,files;
    private boolean verified;
    PodTakeoverConverters(PodTakeoverCoordinator.Converter room,PodTakeoverCoordinator.Converter stores){this(room,stores,null);}
    PodTakeoverConverters(PodTakeoverCoordinator.Converter room,PodTakeoverCoordinator.Converter stores,PodTakeoverCoordinator.Converter files){this.room=room;this.stores=stores;this.files=files;}
    public String version(){return room.version()+"+"+stores.version()+(files==null?"":"+"+files.version());}
    public boolean reclassifies(File g,String previous)throws Exception {
        if(files==null||!files.version().equals("watchrss-files-7"))return false;
        String prefix=room.version()+"+";
        if(!previous.startsWith(prefix))return false;
        String[] old=previous.substring(prefix.length()).split("\\+",-1);
        if(old.length!=2||!java.util.Arrays.asList("watchrss-stores-3","watchrss-stores-4","watchrss-stores-5").contains(old[0].replace("-isolated",""))||
           !java.util.Arrays.asList("watchrss-files-3","watchrss-files-4","watchrss-files-5","watchrss-files-6","watchrss-files-7").contains(old[1]))return false;
        if(old[0].endsWith("-isolated")&&!stores.version().endsWith("-isolated"))return false;
        // Preserve the released store-upgrade recovery path, without bypassing publication evidence.
        if(old[0].equals("watchrss-stores-3")&&old[1].equals("watchrss-files-4")) {
            File diagnostics=PodFileConverter.safe(g,"stores-diagnostics.json");
            if(diagnostics.isFile()&&PodStoreConverter.json(diagnostics).getInt("blockerCount")>0)return true;
        }
        File snapshot=PodFileConverter.safe(g,"stores-snapshot.json"),diagnostics=PodFileConverter.safe(g,"stores-diagnostics.json");
        if(!old[0].equals(stores.version())&&snapshot.isFile()&&diagnostics.isFile()&&PodStoreConverter.json(diagnostics).optInt("blockerCount")>0) {
            org.json.JSONObject captured=PodStoreConverter.json(snapshot);
            if(old[0].equals(captured.optString("version")))for(String path:PodFileConverter.names(captured.getJSONObject("entries")))
                if(!PodStoreConverter.knownFile(path)||PodStoreConverter.retainedKnownFile(path))return true;
        }
        org.json.JSONObject report=PodStoreConverter.json(PodFileConverter.safe(g,"files-report.json"));
        if(!old[1].equals(report.getString("version")))return false;
        org.json.JSONArray blockers=report.getJSONArray("blockers");
        // Re-run every other business check in the fresh candidate; never keep an obsolete unknown-file blocker.
        for(int i=0;i<blockers.length();i++)if("unknown_user_files".equals(blockers.getString(i)))return true;
        return false;
    }
    public boolean complete(){return files!=null&&verified;}
    public void active(File g)throws Exception{room.active(g);stores.active(g);}
    public void activeFast(File g)throws Exception{room.activeFast(g);stores.activeFast(g);}
    public void activeDeep(File g)throws Exception{room.activeDeep(g);stores.activeDeep(g);}
    public void snapshot(File g)throws Exception{PodRoomV18Converter.checkGenerationStorage(g);stores.snapshot(g);PodTakeoverCoordinator.Work.worked("snapshot",1,3);room.snapshot(g);PodTakeoverCoordinator.Work.worked("snapshot",2,3);
        if(!PodStoreConverter.identity(new File(g,"stores-source")).trim().equals(PodTakeoverCoordinator.read(new File(g,"snapshot.properties")).getProperty("identity")))throw new java.io.IOException("identity_snapshot_mismatch");if(files!=null)files.snapshot(g);PodTakeoverCoordinator.Work.worked("snapshot",3,3);}
    public void copy(File g)throws Exception{room.copy(g);room.verify(g);stores.copy(g);if(files!=null)files.copy(g);}
    public void resume(File g,PodTakeoverCoordinator.Phase p)throws Exception{room.resume(g,p);stores.resume(g,p);if(files!=null)files.resume(g,p);}
    public String verify(File g)throws Exception{
        verified=false;
        PodTakeoverCoordinator.Work.worked("bundle",0,3);
        String roomReceipt=room.verify(g);PodTakeoverCoordinator.Work.worked("bundle",1,3);
        String storeReceipt=stores.verify(g);PodTakeoverCoordinator.Work.worked("bundle",2,3);
        String fileReceipt=files==null?"":files.verify(g);PodTakeoverCoordinator.Work.worked("bundle",3,3);
        String receipt=PodRoomV18Converter.hex(MessageDigest.getInstance("SHA-256").digest((roomReceipt+storeReceipt+fileReceipt).getBytes(StandardCharsets.UTF_8)));
        verified=true;return receipt;
    }
    public String blocker(File g)throws Exception {
        if(files==null)return null; // legacy incomplete bundle remains PAUSED
        org.json.JSONObject stores=PodStoreConverter.json(new File(g,"stores-report.json"));
        for(String key:PodFileConverter.names(stores)) {
            String status=stores.getJSONObject(key).getString("status");
            String disposition=stores.getJSONObject(key).optString("activationDisposition","");
            if(status.equals("discarded-unknown")||disposition.equals("known-difference")||disposition.equals("needs-reauth"))continue;
            if(status.equals("needs-reauth"))return "store_needs_reauth_user_confirmation";
            if(!status.equals("migrated")&&!status.equals("absent"))return "store_unsupported_or_failed";
        }
        org.json.JSONArray blockers=PodStoreConverter.json(new File(g,"files-report.json")).getJSONArray("blockers");
        return blockers.length()==0?null:blockers.getString(0);
    }
}
