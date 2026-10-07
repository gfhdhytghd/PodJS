package dev.podjs.runtime;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Host-only fixed file snapshot. No links, source mutations, or whole-file media buffers.
 * Paths and content hashes live only in private receipts, never the public journal. */
public final class PodFileConverter implements PodTakeoverCoordinator.Converter {
    public interface Hook { void saved(String boundary) throws Exception; }
    public interface ProgressHook { void copied(long bytes); }
    public interface FontReader { void load(File file, int faces) throws Exception; }
    private final File sandbox;
    private final PodRoomV18Converter.Factory factory;
    private final FontReader fonts;
    private final Hook hook;
    private final ProgressHook progress;
    private final Set<File> dirtyDirectories=new HashSet<>();
    public PodFileConverter(File sandbox,PodRoomV18Converter.Factory factory,FontReader fonts) {
        this(sandbox,factory,fonts,b -> {},bytes -> {});
    }
    PodFileConverter(File sandbox,PodRoomV18Converter.Factory factory,FontReader fonts,Hook hook) {
        this(sandbox,factory,fonts,hook,bytes -> {});
    }
    PodFileConverter(File sandbox,PodRoomV18Converter.Factory factory,FontReader fonts,Hook hook,ProgressHook progress) {
        this.sandbox=sandbox;this.factory=factory;this.fonts=fonts;this.hook=hook;this.progress=progress;
    }
    public String version(){return "watchrss-files-2";}
    // A file converter alone does not constitute a publishable storage bundle.
    public boolean complete(){return false;}
    static SortedSet<String> names(JSONObject object) {
        SortedSet<String> names=new TreeSet<>();for(Iterator<String> it=object.keys();it.hasNext();)names.add(it.next());return names;
    }
    static File safe(File root,String relative)throws IOException {
        if(relative.isEmpty()||relative.startsWith("/")||relative.contains("\\")||relative.indexOf('\0')>=0)throw new IOException("file_path_invalid");
        for(String bit:relative.split("/",-1))if(bit.isEmpty()||bit.equals(".")||bit.equals(".."))throw new IOException("file_path_invalid");
        File f=new File(root,relative).getAbsoluteFile();
        if(!f.getCanonicalFile().equals(f))throw new IOException("file_symlink");
        // canonicalFile does not reject a dangling symlink.
        for(Path p=f.toPath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("file_symlink");
        return f;
    }
    static JSONObject measure(File f)throws Exception {
        safe(f.getParentFile(),f.getName());
        BasicFileAttributes before=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile())throw new IOException("file_not_regular");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(FileChannel in=FileChannel.open(f.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer b=ByteBuffer.allocate(64*1024);int n;
            while((n=in.read(b))!=-1){if(n==0)continue;digest.update(b.array(),0,n);count=Math.addExact(count,n);b.clear();}
        }
        BasicFileAttributes after=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(count!=before.size()||count!=after.size()||!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("file_changed");
        return new JSONObject().put("size",count).put("sha256",PodRoomV18Converter.hex(digest.digest()));
    }
    static boolean same(JSONObject a,JSONObject b)throws Exception{return a.getLong("size")==b.getLong("size")&&a.getString("sha256").equals(b.getString("sha256"));}
    private void copyFile(File source,File target,JSONObject expected,String stage)throws Exception {
        safe(source.getParentFile(),source.getName());safe(target.getParentFile(),target.getName());
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isSameFile(source.toPath(),target.toPath()))throw new IOException("file_target_linked_to_source");
            if(!same(measure(target),expected))throw new IOException("file_target_conflict");dirtyDirectories.add(target.getParentFile());if(stage.equals("copy"))progress.copied(expected.getLong("size"));return;
        }
        Files.createDirectories(target.getParentFile().toPath());
        File part=safe(target.getParentFile(),".takeover-"+PodStoreConverter.slot(target.getName())+".part");
        if(Files.exists(part.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(part.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("part_not_regular");
        // Unlink stale private partials instead of truncating an existing inode.
        Files.deleteIfExists(part.toPath());
        BasicFileAttributes before=Files.readAttributes(source.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(FileChannel in=FileChannel.open(source.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            FileChannel out=FileChannel.open(part.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer b=ByteBuffer.allocateDirect(1024*1024);int n;
            while((n=in.read(b))!=-1){if(n==0)continue;b.flip();digest.update(b.asReadOnlyBuffer());while(b.hasRemaining())out.write(b);count=Math.addExact(count,n);b.clear();}
            out.force(false);
        }
        hook.saved(stage+".part");
        BasicFileAttributes after=Files.readAttributes(source.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        JSONObject copied=new JSONObject().put("size",count).put("sha256",PodRoomV18Converter.hex(digest.digest()));
        if(!before.isRegularFile()||count!=before.size()||count!=after.size()||!Objects.equals(before.fileKey(),after.fileKey())||
                !before.lastModifiedTime().equals(after.lastModifiedTime())||!same(copied,expected)||part.length()!=count)
            throw new IOException("file_copy_mismatch");
        safe(target.getParentFile(),target.getName());
        if(target.exists())throw new IOException("file_target_conflict");
        Files.move(part.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        dirtyDirectories.add(target.getParentFile());hook.saved(stage+".rename");
        if(stage.equals("copy"))progress.copied(count);
    }
    private void syncDirtyDirectories()throws Exception {
        List<File> directories=new ArrayList<>(dirtyDirectories);
        directories.sort(Comparator.comparingInt((File f)->f.toPath().getNameCount()).reversed());
        for(File directory:directories)PodTakeoverCoordinator.syncDirectory(directory);
        dirtyDirectories.clear();
    }
    static String category(String p) {
        if(p.endsWith(".part")||p.endsWith(".part.meta")||p.endsWith(".tmp"))return "incomplete";
        if(p.matches("files/article_content/[^/]+")||p.matches("files/offline/rss/[^/]+/[^/]+")||p.matches("files/reader_assets/(fonts|backgrounds|variants)/[^/]+")||p.matches("files/notes/assets/[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))return "asset";
        if(p.startsWith("files/watch-cloud-cache/"))return "cloud-unverified";
        if(p.equals("files/app_log.txt"))return "private-log";
        // Native TestAppContainer.createTestSettingsRepository: exact public prefixes + UUID.
        // Preserve these isolated synthetic stores privately; never merge their settings.
        if(p.matches("files/(add-rss-activity|bili-channel-info|bili-smoke|common-smoke|douyin-channel-info|douyin-smoke|home-feed-list-activity|oobe-activity|settings-activity)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.preferences_pb"))return "private-test-state";
        if(p.equals("files/profileinstaller_profileWrittenFor_lastUpdateTime.dat"))return "platform-metadata";
        if(p.startsWith("files/offline/bili/")||p.matches("cache/(douyin_preload|bili_stream_cache|rss_images|douyin_preview_snapshots|updates)/.+")||p.matches("cache/tts_[0-9]+\\.mp3"))return "refetchable";
        return "unknown-user-content";
    }
    private SortedMap<String,JSONObject> inventory()throws Exception {
        SortedMap<String,JSONObject> out=new TreeMap<>();
        for(String top:new String[]{"files","cache"})walk(top,out);
        return out;
    }
    private void walk(String path,SortedMap<String,JSONObject> out)throws Exception {
        File file=safe(sandbox,path);if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            File[] children=file.listFiles();if(children==null)throw new IOException("inventory_unreadable");
            for(File child:children){String p=path+"/"+child.getName();
                if(p.equals("files/package-takeover")||p.equals("files/podjs")||p.equals("files/datastore")||p.startsWith("files/.podjs-"))continue;
                walk(p,out);
            }
        }else out.put(path,measure(file).put("category",category(path)));
    }
    public void snapshot(File g)throws Exception {
        File manifest=new File(g,"files-snapshot.json");
        if(manifest.exists()){resume(g,PodTakeoverCoordinator.Phase.SNAPSHOTTED);return;}
        SortedMap<String,JSONObject> entries=inventory();
        for(Map.Entry<String,JSONObject> e:entries.entrySet())copyFile(safe(sandbox,e.getKey()),safe(new File(g,"files-source"),e.getKey()),e.getValue(),"snapshot");
        syncDirtyDirectories();
        SortedMap<String,JSONObject> after=inventory();
        if(!entries.keySet().equals(after.keySet()))throw new IOException("file_inventory_changed");
        for(String p:entries.keySet())if(!same(entries.get(p),after.get(p)))throw new IOException("file_source_changed");
        PodStoreConverter.json(manifest,new JSONObject().put("version",version()).put("entries",new JSONObject(entries)));
        hook.saved("snapshot");
    }
    JSONObject entries(File g)throws Exception {
        JSONObject m=PodStoreConverter.json(new File(g,"files-snapshot.json"));
        if(!version().equals(m.getString("version")))throw new IOException("files_version_changed");return m.getJSONObject("entries");
    }
    public void resume(File g,PodTakeoverCoordinator.Phase phase)throws Exception {
        JSONObject entries=entries(g);
        for(String p:names(entries))if(!category(p).equals(entries.getJSONObject(p).getString("category"))||!same(measure(safe(new File(g,"files-source"),p)),entries.getJSONObject(p)))throw new IOException("file_snapshot_changed");
    }
    public void copy(File g)throws Exception {
        resume(g,PodTakeoverCoordinator.Phase.COPYING);
        Files.createDirectories(safe(g,"podjs/files").toPath());JSONObject entries=entries(g);
        for(String p:names(entries))if(category(p).equals("asset")){
            copyFile(safe(new File(g,"files-source"),p),safe(new File(g,"podjs/files"),p.substring(6)),entries.getJSONObject(p),"copy");
            JSONObject receipt=new JSONObject().put("path",p).put("size",entries.getJSONObject(p).getLong("size")).put("sha256",entries.getJSONObject(p).getString("sha256")).put("complete",true);
            File record=safe(new File(g,"files-completed"),PodStoreConverter.slot(p)+".json");
            if(record.exists()) {if(!receipt.toString().equals(PodStoreConverter.json(record).toString()))throw new IOException("file_receipt_changed");}
            else PodStoreConverter.json(record,receipt);
            hook.saved("file");
        }
        syncDirtyDirectories();
        PodStoreConverter.json(new File(g,"files-report.json"),report(g));hook.saved("report");
    }
    private static String referenceId(JSONObject object,String key) {
        Object value=object.opt(key);return value instanceof String&&!((String)value).trim().isEmpty()?(String)value:"";
    }
    private static void block(JSONObject r,String reason)throws Exception{r.getJSONArray("blockers").put(reason);}
    private static void degraded(JSONObject r,String kind,String path)throws Exception{r.getJSONArray("degraded").put(new JSONObject().put("kind",kind).put("path",path));}
    private boolean exists(File g,String p)throws Exception{return safe(new File(g,"podjs/files"),p).isFile();}
    private boolean asset(File g,String dir,JSONObject row,String name)throws Exception {
        String path=dir+"/"+row.getString(name);File f=safe(new File(g,"podjs/files"),path);
        if(!f.exists())return false;
        JSONObject actual=measure(f);if(actual.getLong("size")!=row.getLong("byteCount")||!actual.getString("sha256").equals(row.getString("sha256").toLowerCase(Locale.ROOT)))throw new IOException("asset_catalog_mismatch");return true;
    }
    private JSONObject report(File g)throws Exception {
        JSONObject entries=entries(g),r=new JSONObject().put("version",version()).put("blockers",new JSONArray()).put("degraded",new JSONArray()).put("derivedSync","empty-rebuild-before-export");
        JSONObject classes=new JSONObject();
        for(String p:names(entries)) {
            String c=category(p);classes.put(c,classes.optInt(c)+1);
            if(c.equals("asset")){
                JSONObject receipt=PodStoreConverter.json(safe(new File(g,"files-completed"),PodStoreConverter.slot(p)+".json"));
                if(!receipt.getBoolean("complete")||!receipt.getString("path").equals(p)||!same(receipt,entries.getJSONObject(p)))throw new IOException("file_receipt_changed");
                if(!same(measure(safe(new File(g,"podjs/files"),p.substring(6))),entries.getJSONObject(p)))throw new IOException("file_readback_mismatch");
            }
            else if(c.equals("private-test-state"))PodPreferenceReader.protobuf(safe(new File(g,"files-source"),p));
            else if(c.equals("platform-metadata")&&entries.getJSONObject(p).getLong("size")!=8)block(r,"platform_metadata_invalid");
            else if(c.equals("unknown-user-content"))block(r,"unknown_user_files");
            else if(c.equals("cloud-unverified"))block(r,"cloud_cache_binding_unverified");
        }
        r.put("classes",classes);
        try(PodRoomV18Converter.Db refs=factory.open(new File(g,"room-progress.sqlite"))) {
            for(Map<String,Object> row:refs.query("SELECT * FROM content_reference ORDER BY item_id,kind")) {
                String path=(String)row.get("relative_path");
                String name=path.startsWith("article_content/")?path.substring(16):"";
                if(name.contains("..")||!name.matches("(?:v2-[0-9a-f]{64}-[0-9a-f]{64}|[A-Za-z0-9._-]{1,96})\\.txt"))block(r,"article_body_marker_unsupported");
                else if(!exists(g,path))block(r,"article_body_missing");
                else {
                    if(name.startsWith("v2-")&&name.matches("v2-[0-9a-f]{64}-[0-9a-f]{64}\\.txt")&&!measure(safe(new File(g,"podjs/files"),path)).getString("sha256").equals(name.substring(68,132)))throw new IOException("article_body_digest_mismatch");
                    // Strict UTF-8 streaming readback, matching text-reader encoding without buffering media.
                    try(Reader in=new InputStreamReader(Files.newInputStream(safe(new File(g,"podjs/files"),path).toPath(),LinkOption.NOFOLLOW_LINKS),java.nio.charset.StandardCharsets.UTF_8.newDecoder())){char[] b=new char[8192];while(in.read(b)!=-1){}}
                }
            }
        }
        try(PodRoomV18Converter.Db db=factory.open(new File(g,"podjs.sqlite"));PodRoomV18Converter.Db source=factory.open(new File(g,"room.sqlite"))) {
            for(Map<String,Object> row:source.query("SELECT localPath FROM offline_media WHERE localPath IS NOT NULL")) {
                String path=(String)row.get("localPath"),relative=PodRoomV18Converter.relative(path);
                String sourceRoot=new File(sandbox,"files").getAbsolutePath()+"/";
                // Android's two canonical app-sandbox spellings are equivalent; arbitrary /files/ prefixes are not.
                boolean own=path.equals(sourceRoot+relative);
                for(String prefix:new String[]{"/data/user/0/com.lightningstudio.watchrss/files/","/data/data/com.lightningstudio.watchrss/files/"})own|=path.equals(prefix+relative);
                if(!own)block(r,"offline_source_root_invalid");
            }
            for(Map<String,Object> row:db.query("SELECT relative_path FROM offline_media WHERE relative_path IS NOT NULL")) {
                String p=(String)row.get("relative_path");if(!exists(g,p))degraded(r,"offline_missing_retry",p);
            }
            for(Map<String,Object> row:db.query("SELECT markdown,base_markdown FROM memo_notes ORDER BY note_id"))for(Object value:row.values())if(value instanceof String){
                Matcher m=Pattern.compile("(?:^|[\\s(\"'])assets/([^\\s)\"'<>]+)").matcher((String)value);
                while(m.find()){String key=m.group(1);if(!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")){block(r,"note_asset_path_invalid");continue;}String p="notes/assets/"+key;if(!exists(g,p))degraded(r,"note_image_missing",p);}
            }
            // These are new-schema derived caches. The production LocalManifestBuilder hydrates
            // source text, constructs the complete manifest and checks revision before returning it.
            for(String table:new String[]{"article_sync_content","article_sync_encoding_cache"})if(((Number)PodRoomV18Converter.scalar(db,"SELECT count(*) FROM "+table)).longValue()!=0)throw new IOException("derived_cache_not_empty");
        }
        JSONObject catalog=PodStoreConverter.json(new File(g,"reader-catalog.json")),assets=catalog.getJSONObject("assets");
        JSONArray records=catalog.getJSONArray("records");for(int i=0;i<records.length();i++) {
            JSONObject row=records.getJSONObject(i);if(row.optBoolean("deleted"))continue;
            try{JSONObject payload=new JSONObject(row.getString("payloadJson"));
                if(!(payload.opt("id") instanceof String)||payload.getString("id").isEmpty()||!(payload.opt("name") instanceof String)||payload.optDouble("schemaVersion",1)>=3)block(r,"reader_preset_undecodable");
                for(String role:new String[]{"body","title","subtitle","quote","code","link"}) {
                    JSONObject style=payload.optJSONObject(role);if(style==null)continue;
                    String font=referenceId(style,"fontAssetId");if(font.isEmpty())continue;
                    JSONObject found=null;JSONArray candidates=assets.getJSONArray("fonts");for(int j=0;j<candidates.length();j++)if(candidates.getJSONObject(j).getString("id").equals(font)&&!candidates.getJSONObject(j).optBoolean("deleted"))found=candidates.getJSONObject(j);
                    if(found==null||style.optInt("fontFaceIndex",0)<0||style.optInt("fontFaceIndex",0)>=found.getInt("faceCount"))block(r,"reader_font_reference_invalid");
                }
                JSONObject bg=payload.optJSONObject("background");if(bg!=null)for(String key:new String[]{"assetId","posterAssetId"}) {
                    String id=referenceId(bg,key);if(id.isEmpty())continue;boolean found=false;
                    JSONArray candidates=assets.getJSONArray("backgrounds");for(int j=0;j<candidates.length();j++)if(candidates.getJSONObject(j).getString("id").equals(id)&&!candidates.getJSONObject(j).optBoolean("deleted"))found=true;
                    if(!found)block(r,"reader_background_reference_invalid");
                }
            }catch(JSONException e){block(r,"reader_preset_undecodable");}
        }
        JSONArray list=assets.getJSONArray("fonts");for(int i=0;i<list.length();i++) {
            JSONObject row=list.getJSONObject(i);if(row.optBoolean("deleted"))continue;
            if(!asset(g,"reader_assets/fonts",row,"fileName")){block(r,"reader_font_missing");continue;}
            int faces=row.getInt("faceCount");if(faces<=0||faces>1024){block(r,"reader_font_faces_invalid");continue;}
            try{fonts.load(safe(new File(g,"podjs/files/reader_assets/fonts"),row.getString("fileName")),faces);}catch(Exception e){block(r,"reader_font_unloadable");}
        }
        list=assets.getJSONArray("backgrounds");for(int i=0;i<list.length();i++) {
            JSONObject row=list.getJSONObject(i);if(row.optBoolean("deleted"))continue;
            String poster=referenceId(row,"posterAssetId");
            if(!poster.isEmpty()) {
                boolean found=false;for(int j=0;j<list.length();j++)if(list.getJSONObject(j).getString("id").equals(poster)&&!list.getJSONObject(j).optBoolean("deleted"))found=true;
                if(!found)block(r,"reader_poster_reference_invalid");
            }
            boolean master=asset(g,"reader_assets/backgrounds",row,"masterFileName"),watch=false;
            JSONObject variants;try{variants=new JSONObject(row.getString("variantsJson"));}catch(JSONException e){block(r,"reader_variants_invalid");continue;}
            for(String key:names(variants)){
                boolean valid=asset(g,"reader_assets/variants",variants.getJSONObject(key),"fileName");
                if(!valid)block(r,"reader_variant_missing");if(key.equals("watch"))watch=valid;
            }
            if(!master){degraded(r,"reader_master_missing",row.getString("masterFileName"));if(!watch)block(r,"reader_background_unavailable");}
        }
        return r;
    }
    public String verify(File g)throws Exception {
        resume(g,PodTakeoverCoordinator.Phase.VERIFYING);JSONObject actual=report(g),saved=PodStoreConverter.json(new File(g,"files-report.json"));
        if(!actual.toString().equals(saved.toString()))throw new IOException("files_report_changed");
        return PodStoreConverter.slot(PodStoreConverter.hash(new File(g,"files-snapshot.json"))+PodStoreConverter.hash(new File(g,"files-report.json")));
    }
}
