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
        if(!previous.equals(room.version()+"+"+stores.version()+"+watchrss-files-3")||files==null||!files.version().equals("watchrss-files-4"))return false;
        org.json.JSONObject report=PodStoreConverter.json(PodFileConverter.safe(g,"files-report.json"));
        if(!"watchrss-files-3".equals(report.getString("version")))return false;
        org.json.JSONArray blockers=report.getJSONArray("blockers");
        if(blockers.length()==0)return false;
        for(int i=0;i<blockers.length();i++)if(!"unknown_user_files".equals(blockers.getString(i)))return false;
        return true;
    }
    public boolean complete(){return files!=null&&verified;}
    public void active(File g)throws Exception{room.active(g);stores.active(g);}
    public void activeFast(File g)throws Exception{room.activeFast(g);stores.activeFast(g);}
    public void activeDeep(File g)throws Exception{room.activeDeep(g);stores.activeDeep(g);}
    public void snapshot(File g)throws Exception{stores.snapshot(g);room.snapshot(g);
        if(!PodStoreConverter.identity(new File(g,"stores-source")).trim().equals(PodTakeoverCoordinator.read(new File(g,"snapshot.properties")).getProperty("identity")))throw new java.io.IOException("identity_snapshot_mismatch");if(files!=null)files.snapshot(g);}
    public void copy(File g)throws Exception{room.copy(g);room.verify(g);stores.copy(g);if(files!=null)files.copy(g);}
    public void resume(File g,PodTakeoverCoordinator.Phase p)throws Exception{room.resume(g,p);stores.resume(g,p);if(files!=null)files.resume(g,p);}
    public String verify(File g)throws Exception{
        verified=false;
        String receipt=PodRoomV18Converter.hex(MessageDigest.getInstance("SHA-256").digest((room.verify(g)+stores.verify(g)+(files==null?"":files.verify(g))).getBytes(StandardCharsets.UTF_8)));
        verified=true;return receipt;
    }
    public String blocker(File g)throws Exception {
        if(files==null)return null; // legacy incomplete bundle remains PAUSED
        org.json.JSONObject stores=PodStoreConverter.json(new File(g,"stores-report.json"));
        for(String key:PodFileConverter.names(stores)) {
            String status=stores.getJSONObject(key).getString("status");
            String disposition=stores.getJSONObject(key).optString("activationDisposition","");
            if(disposition.equals("known-difference")||disposition.equals("needs-reauth"))continue;
            if(status.equals("needs-reauth"))return "store_needs_reauth_user_confirmation";
            if(!status.equals("migrated")&&!status.equals("absent"))return "store_unsupported_or_failed";
        }
        org.json.JSONArray blockers=PodStoreConverter.json(new File(g,"files-report.json")).getJSONArray("blockers");
        return blockers.length()==0?null:blockers.getString(0);
    }
}
