package dev.podjs.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Properties;

/** Positive native evidence only. Shared Android/WebView prefs and PodJS's own
 * files do not prove a WatchRSS source exists. Unknown stores are inventoried by
 * converters only after native evidence has admitted the migration. Read-only. */
final class PodTakeoverSource {
    /** Resolve only the trusted Context sandbox boundary, never an untrusted child path. */
    static File canonicalSandbox(File raw)throws IOException { return raw.getCanonicalFile(); }
    static final String[] DATASTORES={"settings.preferences_pb","settings.preferences_pb.preferences_pb","bili_cache.preferences_pb","bili_cache.preferences_pb.preferences_pb"};
    static final String[] PREFS={"reader_preset_state","home_entry_placement","app_resume_state","douyin_watch_history","douyin_recent_window","douyin_feed_cache","watchrss_announcement","watchrss_push","watch_device_identity","watchrss_installation_identity","watchrss_daily_telemetry","watchrss_download_telemetry","watchrss_cloud_state","watchrss_account_state","llm_secure_prefs","tts_secure_prefs","watchrss_cloud_keys","bili_account_store","douyin_account_store"};
    static final String[] FILES={"article_content","offline/rss","offline/bili","reader_assets","notes","watch-cloud-cache"};
    static boolean exists(File root,String path){return Files.exists(new File(root,path).toPath(),LinkOption.NOFOLLOW_LINKS);}
    static boolean inventory(File sandbox,Properties inventory) {
        boolean database=exists(sandbox,"databases/watchrss.db"),wal=exists(sandbox,"databases/watchrss.db-wal");
        inventory.setProperty("source.database",Boolean.toString(database));inventory.setProperty("source.wal",Boolean.toString(wal));
        boolean present=database||wal;
        for(String name:DATASTORES){boolean found=exists(sandbox,"files/datastore/"+name);inventory.setProperty("source.store."+name,Boolean.toString(found));present|=found;}
        for(String name:PREFS){boolean found=exists(sandbox,"shared_prefs/"+name+".xml")||exists(sandbox,"shared_prefs/"+name+".xml.bak");inventory.setProperty("source.store."+name,Boolean.toString(found));present|=found;}
        for(String path:FILES){boolean found=exists(sandbox,"files/"+path);inventory.setProperty("source.files."+path.replace('/','.'),Boolean.toString(found));present|=found;}
        return present;
    }
    static PodTakeoverCoordinator.Source detect(File sandbox,boolean unlocked)throws IOException {
        Properties inventory=new Properties();
        if(!inventory(sandbox,inventory))return new PodTakeoverCoordinator.Source(false,null,0,0,0);
        File files=new File(sandbox,"files"),db=new File(sandbox,"databases/watchrss.db");
        String block=null;int schema=18;
        if(!db.isFile()){block="source_database_missing";schema=-1;}
        if(new File(sandbox,"databases/podjs.sqlite").exists()||nonempty(new File(files,"podjs")))block="target_conflict";
        File backup=new File(sandbox,"no_backup");
        if(backup.exists())for(File child:children(backup))if(child.getName().startsWith("podjs-")&&nonempty(child))block="target_conflict";
        if(!unlocked)block="user_locked";
        long bytes=Math.addExact(Math.addExact(size(db),size(new File(db+"-wal"))),size(new File(sandbox,"shared_prefs")));
        for(File parent:new File[]{files,new File(sandbox,"cache")})if(parent.exists())for(File child:children(parent))
            if(!child.getName().equals("package-takeover")&&!child.getName().equals("podjs"))bytes=Math.addExact(bytes,size(child));
        long required=Math.addExact(Math.multiplyExact(bytes,3),32L*1024*1024);
        PodTakeoverCoordinator.Source result=new PodTakeoverCoordinator.Source(true,block,schema,required,sandbox.getUsableSpace());
        result.inventory.putAll(inventory);return result;
    }
    private static boolean nonempty(File f)throws IOException {
        return f.exists()&&(!f.isDirectory()||children(f).length>0);
    }
    private static File[] children(File directory)throws IOException {
        File[] children=directory.listFiles();if(children==null)throw new IOException("source_unreadable");return children;
    }
    private static long size(File file)throws IOException {
        if(Files.isSymbolicLink(file.toPath())||!file.getCanonicalFile().equals(file.getAbsoluteFile()))throw new IOException("source_symlink");
        if(!file.exists())return 0;
        if(!file.isDirectory())return file.length();
        long total=0;for(File child:children(file))total=Math.addExact(total,size(child));return total;
    }

}
