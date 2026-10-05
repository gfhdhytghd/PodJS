package dev.podjs.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** WatchRSS-only, host-side converter. Source writers must already be stopped by
 * admission. The source is never opened by SQLite: DB/WAL are recovered only in
 * a private copy. Every source byte is checked before and after copying.
 * Reader catalogs are staged separately; this converter cannot activate stores. */
public final class PodRoomV18Converter implements PodTakeoverCoordinator.Converter {
    public interface Db extends AutoCloseable {
        List<Map<String,Object>> query(String sql, Object... args) throws Exception;
        void exec(String sql, Object... args) throws Exception;
        void close() throws Exception;
    }
    public interface Factory { Db open(File path) throws Exception; }
    public interface BatchHook { void checkpoint(boolean committed) throws Exception; }
    private final File source;
    private final Factory factory;
    public interface IdentitySource { String read() throws Exception; }
    public interface SnapshotIdentitySource { String read(File generation) throws Exception; }
    private final SnapshotIdentitySource identity;
    private final int batchSize;
    private final BatchHook hook;
    private static final String[] TABLES={"rss_channels","rss_items","saved_sync_states","saved_entries","offline_media","rss_source_sync_states","sync_change_log","sync_peer_state","watch_note_folders","watch_notes","llm_token_usage","reader_presets","reader_font_assets","reader_background_assets","reader_deletions"};
    public PodRoomV18Converter(File source, Factory factory, String identity) { this(source,factory,identity,128,c -> {}); }
    public PodRoomV18Converter(File source, Factory factory, IdentitySource identity) { this(source,factory,identity,128,c -> {}); }
    public PodRoomV18Converter(File source, Factory factory, String identity,int batchSize,BatchHook hook) {
        this(source,factory,()->identity,batchSize,hook);
    }
    private PodRoomV18Converter(File source, Factory factory, IdentitySource identity,int batchSize,BatchHook hook) {
        if(batchSize<1)throw new IllegalArgumentException();
        this.source=source;this.factory=factory;this.identity=g -> identity.read();this.batchSize=batchSize;this.hook=hook;
    }
    public PodRoomV18Converter(File source, Factory factory, SnapshotIdentitySource identity) {
        this.source=source;this.factory=factory;this.identity=identity;this.batchSize=128;this.hook=c -> {};
    }
    public void active(File g)throws Exception {
        File file=new File(g,"podjs.sqlite");if(file.length()<100)throw new IOException("active_database_invalid");
        try(Db db=factory.open(file)) {
            checks(db);
            for(String table:new String[]{"articles","channels","memo_notes","sync_local_identity"})
                if(scalar(db,"SELECT name FROM sqlite_master WHERE type='table' AND name=?",table)==null)throw new IOException("active_schema_missing");
            if(!nonempty(scalar(db,"SELECT device_id FROM sync_local_identity WHERE id=1")))throw new IOException("active_identity_missing");
        }
    }
    public String version(){return "watchrss-room-v18-1";}
    public boolean complete(){return false;}
    static String resource(String name) throws Exception {
        try(InputStream in=PodRoomV18Converter.class.getResourceAsStream(name)) {
            if(in==null)throw new IOException("schema_resource_missing");
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static String hash(File file) throws Exception {
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("source_symlink");
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        return hex(d.digest());
    }
    /** POSIX byte ranges used by SQLite's unix VFS. Shared locks prohibit
     * rollback writers and WAL writers/checkpointers without writing source files.
     * Read/hash/copy use the SAME descriptors: closing a second descriptor would
     * release process-scoped POSIX locks. Fail closed if locks cannot be acquired. */
    private static final class Capture implements AutoCloseable {
        final File source;
        FileInputStream main,wal,shm;
        FileLock mainLock,walLock;
        Capture(File source)throws Exception {
            this.source=source;
            try {
                for(String suffix:new String[]{"","-wal","-shm"})if(Files.isSymbolicLink(new File(source+suffix).toPath()))throw new IOException("source_symlink");
                main=new FileInputStream(source);
                mainLock=main.getChannel().tryLock(0x40000000L,512,true);
                if(mainLock==null)throw new IOException("source_busy");
                File w=new File(source+"-wal"),m=new File(source+"-shm");
                if(m.exists()) {
                    shm=new FileInputStream(m);walLock=shm.getChannel().tryLock(120,8,true);
                    if(walLock==null)throw new IOException("source_busy");
                }
                if(w.exists()) {
                    if(!m.exists()&&w.length()>0)throw new IOException("source_wal_unlocked");
                    wal=new FileInputStream(w);
                }
            }catch(Exception e){close();throw e;}
        }
        static String digest(FileInputStream in)throws Exception {
            if(in==null)return "absent";
            in.getChannel().position(0);MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)d.update(b,0,n);return hex(d.digest());
        }
        String digest()throws Exception {
            if(new File(source+"-wal").exists()!=(wal!=null))throw new IOException("source_changed");
            return digest(main)+":"+digest(wal);
        }
        static void copy(FileInputStream in,File to)throws Exception {
            if(in==null)return;in.getChannel().position(0);
            try(FileOutputStream out=new FileOutputStream(to)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}
        }
        public void close()throws IOException {
            try{if(walLock!=null)walLock.release();}finally{try{if(mainLock!=null)mainLock.release();}finally{try{if(shm!=null)shm.close();}finally{try{if(wal!=null)wal.close();}finally{if(main!=null)main.close();}}}}
        }
    }
    public void snapshot(File g) throws Exception {
        File receipt=new File(g,"snapshot.properties");
        if(receipt.exists()){resume(g,PodTakeoverCoordinator.Phase.SNAPSHOTTED);return;}
        String localIdentity=identity.read(g);
        if(localIdentity==null || localIdentity.trim().isEmpty())throw new IOException("identity_missing");
        // New scratch directory per attempt; interrupted candidates remain private.
        File scratch=new File(g,"capture-"+UUID.randomUUID());Files.createDirectories(scratch.toPath());
        File raw=new File(scratch,"room.sqlite");
        try(Capture capture=new Capture(source)) {
            String before=capture.digest();Capture.copy(capture.main,raw);Capture.copy(capture.wal,new File(raw+"-wal"));
            if(!before.equals(capture.digest()))throw new IOException("source_changed");
        }
        try(Db db=factory.open(raw)) {
            validateRoom(db);
            // Only the private DB is checkpointed. Never copy source SHM (locks).
            db.query("PRAGMA wal_checkpoint(TRUNCATE)");
            db.query("PRAGMA journal_mode=DELETE");
        }
        File fixed=new File(g,"room.sqlite");Files.move(raw.toPath(),fixed.toPath(),StandardCopyOption.REPLACE_EXISTING);
        try(RandomAccessFile f=new RandomAccessFile(fixed,"rw")){f.getFD().sync();}
        Properties p=new Properties();p.setProperty("snapshot",hash(fixed));p.setProperty("version",version());
        // Identity remains private in candidate metadata, never the public journal.
        p.setProperty("identity",localIdentity.trim());p.setProperty("schema",hex(MessageDigest.getInstance("SHA-256").digest(resource("watchrss-schema.json").getBytes(StandardCharsets.UTF_8))));
        PodTakeoverCoordinator.atomic(receipt,p);
    }
    static Object scalar(Db db,String sql,Object... args)throws Exception {
        List<Map<String,Object>> rows=db.query(sql,args);return rows.isEmpty()?null:rows.get(0).values().iterator().next();
    }
    static long number(Object o){return o==null?0:((Number)o).longValue();}
    static void checks(Db db)throws Exception {
        if(!"ok".equals(scalar(db,"PRAGMA quick_check")))throw new IOException("quick_check_failed");
        if(!db.query("PRAGMA foreign_key_check").isEmpty())throw new IOException("foreign_key_check_failed");
    }
    static void validateRoom(Db db)throws Exception {
        JSONObject contract=new JSONObject(resource("room-v18.json"));
        if(number(scalar(db,"PRAGMA user_version"))!=18 || !contract.getString("identity").equals(scalar(db,"SELECT identity_hash FROM room_master_table WHERE id=42")))throw new IOException("room_identity_invalid");
        JSONObject expected=contract.getJSONObject("tables");Set<String> names=new HashSet<>();
        for(Map<String,Object> r:db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'"))names.add((String)r.get("name"));
        Set<String> expectedNames=new HashSet<>();for(Iterator<String> it=expected.keys();it.hasNext();)expectedNames.add(it.next());
        if(!names.equals(expectedNames))throw new IOException("room_tables_invalid");
        for(String table:names){
            JSONArray columns=expected.getJSONArray(table);List<Map<String,Object>> actual=db.query("PRAGMA table_info("+q(table)+")");
            if(columns.length()!=actual.size())throw new IOException("room_columns_invalid");
            for(int i=0;i<columns.length();i++)for(String key:new String[]{"name","type","notnull","pk"})
                if(!String.valueOf(columns.getJSONObject(i).get(key)).equals(String.valueOf(actual.get(i).get(key))))throw new IOException("room_columns_invalid");
        }
        // saved_sync_states intentionally permits orphan item references.
        checks(db);
    }
    static String q(String identifier){return "\""+identifier.replace("\"","\"\"")+"\"";}
    private Db target(File g)throws Exception {
        Db d=factory.open(new File(g,"podjs.sqlite"));
        d.query("PRAGMA journal_mode=DELETE");d.exec("PRAGMA synchronous=FULL");d.exec("PRAGMA foreign_keys=ON");
        d.exec("ATTACH DATABASE ? AS migration",new File(g,"room-progress.sqlite").getAbsolutePath());
        d.query("PRAGMA migration.journal_mode=DELETE");d.exec("PRAGMA migration.synchronous=FULL");
        d.exec("CREATE TABLE IF NOT EXISTS migration.cursor(table_name TEXT PRIMARY KEY,key_json TEXT NOT NULL,rows_done INTEGER NOT NULL)");
        d.exec("CREATE TABLE IF NOT EXISTS migration.catalog(table_name TEXT NOT NULL,key_json TEXT NOT NULL,value_json TEXT NOT NULL,PRIMARY KEY(table_name,key_json))");
        d.exec("CREATE TABLE IF NOT EXISTS migration.content_reference(item_id INTEGER NOT NULL,kind TEXT NOT NULL,relative_path TEXT NOT NULL,PRIMARY KEY(item_id,kind))");
        d.exec("CREATE TABLE IF NOT EXISTS migration.receipt(id INTEGER PRIMARY KEY,digest TEXT NOT NULL)");
        return d;
    }
    public void resume(File g,PodTakeoverCoordinator.Phase phase)throws Exception {
        Properties p=PodTakeoverCoordinator.read(new File(g,"snapshot.properties"));
        if(!version().equals(p.getProperty("version")) || !hash(new File(g,"room.sqlite")).equals(p.getProperty("snapshot")) || !hex(MessageDigest.getInstance("SHA-256").digest(resource("watchrss-schema.json").getBytes(StandardCharsets.UTF_8))).equals(p.getProperty("schema")))throw new IOException("snapshot_changed");
        try(Db s=factory.open(new File(g,"room.sqlite"))){validateRoom(s);}
        if(new File(g,"podjs.sqlite").exists())try(Db d=target(g)){
            Object digest=scalar(d,"SELECT digest FROM migration.receipt WHERE id=1");
            if(digest!=null&&!digest.equals(fingerprint(d)))throw new IOException("candidate_changed");
        }
    }
    private void initialize(Db d,File g)throws Exception {
        if(scalar(d,"SELECT digest FROM migration.receipt WHERE id=1")!=null)return;
        d.exec("BEGIN IMMEDIATE");try {
            JSONArray schema=new JSONArray(resource("watchrss-schema.json"));for(int i=0;i<schema.length();i++)d.exec(schema.getString(i));
            String id=PodTakeoverCoordinator.read(new File(g,"snapshot.properties")).getProperty("identity");
            insert(d,"sync_local_identity","id,device_id,device_order",1,id,deviceOrder(id));
            seal(d);d.exec("COMMIT");
        }catch(Exception e){d.exec("ROLLBACK");throw e;}
    }
    static List<String> keys(Db s,String table)throws Exception {
        TreeMap<Long,String> keys=new TreeMap<>();for(Map<String,Object> r:s.query("PRAGMA table_info("+q(table)+")"))if(number(r.get("pk"))>0)keys.put(number(r.get("pk")),(String)r.get("name"));
        if(keys.isEmpty())throw new IOException("primary_key_missing");return new ArrayList<>(keys.values());
    }
    private List<Map<String,Object>> page(Db s,String table,List<String> keys,JSONArray after)throws Exception {
        List<Object> args=new ArrayList<>();StringBuilder sql=new StringBuilder("SELECT * FROM "+q(table));
        if(after!=null){sql.append(" WHERE ");for(int i=0;i<keys.size();i++){
            if(i>0)sql.append(" OR ");sql.append('(');
            for(int j=0;j<i;j++){sql.append(q(keys.get(j))).append("=? AND ");args.add(after.get(j));}
            sql.append(q(keys.get(i))).append(">?)");args.add(after.get(i));
        }}
        sql.append(" ORDER BY ");for(int i=0;i<keys.size();i++){if(i>0)sql.append(',');sql.append(q(keys.get(i)));}
        sql.append(" LIMIT ?");args.add(batchSize);return s.query(sql.toString(),args.toArray());
    }
    public void copy(File g)throws Exception {
        resume(g,PodTakeoverCoordinator.Phase.COPYING);
        try(Db s=factory.open(new File(g,"room.sqlite"));Db d=target(g)){
            initialize(d,g);
            for(String table:TABLES){
                List<String> keys=keys(s,table);
                Object cursor=scalar(d,"SELECT key_json FROM migration.cursor WHERE table_name=?",table);
                JSONArray after=cursor==null?null:new JSONArray((String)cursor);
                long count=number(scalar(d,"SELECT rows_done FROM migration.cursor WHERE table_name=?",table));
                while(true){
                    List<Map<String,Object>> rows=page(s,table,keys,after);if(rows.isEmpty())break;
                    d.exec("BEGIN IMMEDIATE");try {
                        for(Map<String,Object> row:rows)convert(s,d,table,row);
                        after=new JSONArray();Map<String,Object> last=rows.get(rows.size()-1);for(String k:keys)after.put(last.get(k));
                        count+=rows.size();d.exec("INSERT OR REPLACE INTO migration.cursor VALUES(?,?,?)",table,after.toString(),count);
                        seal(d);hook.checkpoint(false);d.exec("COMMIT");
                    }catch(Exception e){d.exec("ROLLBACK");throw e;}
                    hook.checkpoint(true);
                }
            }
        }
    }
    static void insert(Db d,String table,String columns,Object... values)throws Exception {
        String marks=String.join(",",Collections.nCopies(values.length,"?"));d.exec("INSERT INTO "+table+"("+columns+") VALUES("+marks+")",values);
    }
    static void map(Db d,String table,String columns,Map<String,Object> row,String source)throws Exception {
        String[] names=source.split(",");Object[] values=new Object[names.length];for(int i=0;i<names.length;i++)values[i]=row.get(names[i]);insert(d,table,columns,values);
    }
    static String deviceOrder(String s){StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++)b.append(String.format(Locale.ROOT,"%04x",(int)s.charAt(i)));return b.toString();}
    static String saveType(Object o)throws Exception {if("FAVORITE".equals(o))return "favorite";if("WATCH_LATER".equals(o))return "watchLater";throw new IOException("save_type_invalid");}
    static boolean nonempty(Object o){return o!=null&&!o.toString().isEmpty();}
    static String relative(Object o)throws Exception {
        if(o==null)return null;String p=o.toString();int i=p.indexOf("/files/");if(i<0)throw new IOException("offline_path_invalid");
        p=p.substring(i+7);String[] bits=p.split("/",-1);
        if(bits.length!=4||!bits[0].equals("offline")||!bits[1].equals("rss")||p.contains("\\")||p.indexOf('\0')>=0)throw new IOException("offline_path_invalid");
        for(String bit:bits)if(bit.isEmpty()||bit.equals(".")||bit.equals(".."))throw new IOException("offline_path_invalid");return p;
    }
    private void convert(Db s,Db d,String t,Map<String,Object> r)throws Exception {
        Object id=r.get("id");
        switch(t){
        case "rss_channels":
            map(d,"channels","id,url,title,description,image_url,last_fetched_at,created_at,sort_order,is_pinned",r,"id,url,title,description,imageUrl,lastFetchedAt,createdAt,sortOrder,isPinned");
            insert(d,"source_preferences","channel_id,use_original_content,read_preset",id,r.get("useOriginalContent"),"default");
            insert(d,"source_media_preferences","channel_id,continue_in_background",id,r.get("continuePlaybackInBackground"));break;
        case "rss_items":
            for(String field:new String[]{"content","originalContent"}) {
                Object value=r.get(field);
                if(value instanceof String && ((String)value).startsWith("watchrss-watch-content:")) {
                    String name=((String)value).substring("watchrss-watch-content:".length());
                    if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.indexOf('\0')>=0)throw new IOException("content_reference_invalid");
                    d.exec("INSERT INTO migration.content_reference VALUES(?,?,?)",id,field,"article_content/"+name);
                }
            }
            Map<String,Object> article=new HashMap<>(r);article.put("saved",number(scalar(s,"SELECT count(*) FROM saved_entries WHERE itemId=? AND saveType='FAVORITE'",id))>0?1:0);
            map(d,"articles","id,channel_id,title,description,content,link,guid,pub_date,image_url,audio_url,video_url,is_read,is_saved,reading_progress,dedup_key,fetched_at",article,"id,channelId,title,description,content,link,guid,pubDate,imageUrl,audioUrl,videoUrl,isRead,saved,readingProgress,dedupKey,fetchedAt");
            if(r.get("summary")!=null||r.get("previewImageUrl")!=null)insert(d,"article_previews","article_id,summary,image_url",id,r.get("summary"),r.get("previewImageUrl"));
            if(nonempty(r.get("originalContent"))){insert(d,"article_original_chunks","article_id,chunk_index,body",id,0,r.get("originalContent"));insert(d,"article_original_meta","article_id,source_url,fetched_at",id,r.get("link")==null?"":r.get("link"),r.get("fetchedAt"));}
            if(nonempty(r.get("syncBodyHash")))insert(d,"article_native_sync_hash","item_id,body_hash",id,r.get("syncBodyHash"));
            if(((Number)r.get("readingProgress")).doubleValue()!=0||number(r.get("readingPositionChangedAt"))!=0)map(d,"article_reading_state","article_id,progress,changed_at,position_bytes,position_hash",r,"id,readingProgress,readingPositionChangedAt,readingPositionBytes,readingPositionContentHash");break;
        case "saved_sync_states":
            String device=(String)r.get("sourceDeviceId");Object aid=r.get("articleId");
            if("ARTICLE_DELETE".equals(r.get("saveType"))){insert(d,"sync_article_deletions","article_id,url,changed_at,source_device_id,source_order",aid,r.get("url"),r.get("changedAt"),device,deviceOrder(device));break;}
            String type=saveType(r.get("saveType"));Object item=r.get("itemId");if(scalar(d,"SELECT id FROM articles WHERE id=?",item)==null)item=null;
            if(item!=null){
                List<Map<String,Object>> existing=d.query("SELECT item_id,url FROM sync_article_ids WHERE article_id=?",aid);
                if(existing.isEmpty()) {insert(d,"sync_article_ids","article_id,item_id,url",aid,item,r.get("url"));insert(d,"sync_article_aliases","item_id,article_id",item,aid);}
                else if(number(existing.get(0).get("item_id"))!=number(item)||!Objects.equals(existing.get(0).get("url"),r.get("url")))throw new IOException("article_identity_conflict");
                insert(d,"article_saved_states","article_id,save_type,saved,changed_at,sort_order",item,type,r.get("saved"),r.get("changedAt"),Math.negateExact(number(r.get("sortOrder"))));
            }
            insert(d,"sync_saved_states","article_id,save_type,item_id,url,saved,changed_at,sort_order,source_device_id,source_order",aid,type,item,r.get("url"),r.get("saved"),r.get("changedAt"),r.get("sortOrder"),device,deviceOrder(device));break;
        case "saved_entries":
            String st=saveType(r.get("saveType"));d.exec("INSERT INTO article_saved_states(article_id,save_type,saved,changed_at,sort_order) VALUES(?,?,1,?,?) ON CONFLICT(article_id,save_type) DO UPDATE SET saved=1,sort_order=excluded.sort_order",r.get("itemId"),st,r.get("createdAt"),Math.negateExact(number(r.get("sortOrder"))));
            insert(d,"article_saved_entry_times","article_id,save_type,created_at",r.get("itemId"),st,r.get("createdAt"));break;
        case "offline_media":insert(d,t,"id,item_id,media_type,origin_url,relative_path,created_at",id,r.get("itemId"),r.get("mediaType"),r.get("originUrl"),relative(r.get("localPath")),r.get("createdAt"));break;
        case "rss_source_sync_states":map(d,"sync_source_states","url,source_device_id,title,description,site_url,image_url,created_at,updated_at,sort_order,is_pinned,deleted,deleted_at",r,"url,sourceDeviceId,title,description,siteUrl,imageUrl,createdAt,updatedAt,sortOrder,isPinned,deleted,deletedAt");break;
        case "sync_change_log":map(d,t,"seq,kind,entity_id,changed_at,origin_device_id,reason,created_at",r,"seq,kind,entityId,changedAt,originDeviceId,reason,createdAt");break;
        case "sync_peer_state":map(d,t,"peer_device_id,last_local_seq_acked,last_remote_seq_applied,last_full_sync_at,last_protocol_version,updated_at",r,"peerDeviceId,lastLocalSeqAckedByPeer,lastRemoteSeqApplied,lastFullSyncAt,lastProtocolVersion,updatedAt");break;
        case "watch_note_folders":map(d,"memo_folders","folder_id,name,sort_order,updated_at,modified_by,deleted,deleted_at",r,"folderId,name,sortOrder,updatedAt,modifiedBy,deleted,deletedAt");break;
        case "watch_notes":map(d,"memo_notes","note_id,folder_id,title,markdown,plain_text,content_hash,base_content_hash,base_markdown,pinned,created_at,updated_at,modified_by,deleted,deleted_at",r,"noteId,folderId,title,markdown,plainText,contentHash,baseContentHash,baseMarkdown,pinned,createdAt,updatedAt,modifiedBy,deleted,deletedAt");break;
        case "llm_token_usage":
            JSONObject usage=new JSONObject();String[] src={"promptTokens","completionTokens","totalTokens","reasoningTokens","inputTokens","outputTokens","promptTokenCount","candidatesTokenCount","totalTokenCount"};String[] dst={"prompt_tokens","completion_tokens","total_tokens","reasoning_tokens","input_tokens","output_tokens","promptTokenCount","candidatesTokenCount","totalTokenCount"};
            for(int i=0;i<src.length;i++)if(r.get(src[i])!=null)usage.put(dst[i],r.get(src[i]));if(r.get("cachedPromptTokens")!=null)usage.put("prompt_tokens_details",new JSONObject().put("cached_tokens",r.get("cachedPromptTokens")));
            insert(d,t,"id,provider,model,request_id,usage_json,created_at",id,r.get("provider"),r.get("model"),r.get("requestId"),usage.toString(),r.get("createdAt"));break;
        default:
            JSONObject row=new JSONObject();for(String k:r.keySet())row.put(k,r.get(k)==null?JSONObject.NULL:r.get(k));
            if(r.containsKey("deleted"))row.put("deleted",number(r.get("deleted"))!=0);
            if(t.equals("reader_background_assets")&&!nonempty(r.get("posterAssetId")))row.put("posterAssetId","");
            JSONArray key=new JSONArray();for(String k:keys(s,t))key.put(r.get(k));
            d.exec("INSERT INTO migration.catalog VALUES(?,?,?)",t,key.toString(),row.toString());
        }
    }
    static String tableDigest(Db d,String table)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        List<Map<String,Object>> cols=d.query("PRAGMA "+(table.startsWith("migration.")?"migration.":"")+"table_info("+q(table.replace("migration.",""))+")");
        List<String> order=new ArrayList<>();TreeMap<Long,String> primary=new TreeMap<>();
        for(Map<String,Object> c:cols){order.add(q((String)c.get("name")));if(number(c.get("pk"))>0)primary.put(number(c.get("pk")),q((String)c.get("name")));}
        if(!primary.isEmpty())order=new ArrayList<>(primary.values());
        // Readback includes keys and all nullable values; length framing avoids ambiguity.
        for(long offset=0;;offset+=128) {
            List<Map<String,Object>> page=d.query("SELECT * FROM "+table+" ORDER BY "+String.join(",",order)+" LIMIT 128 OFFSET ?",offset);
            if(page.isEmpty())break;
            for(Map<String,Object> r:page)for(Object v:r.values()){
                String val=v==null?"N":v instanceof Number?"#"+v.toString():"S"+v.toString();byte[] bytes=val.getBytes(StandardCharsets.UTF_8);hash.update((bytes.length+":").getBytes(StandardCharsets.US_ASCII));hash.update(bytes);
            }
        }
        return hex(hash.digest());
    }
    static String fingerprint(Db d)throws Exception {
        MessageDigest h=MessageDigest.getInstance("SHA-256");
        for(Map<String,Object> row:d.query("SELECT type,name,sql FROM sqlite_master ORDER BY name"))h.update(new JSONObject(row).toString().getBytes(StandardCharsets.UTF_8));
        for(Map<String,Object> r:d.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")){String t=(String)r.get("name");h.update((t+tableDigest(d,t)).getBytes(StandardCharsets.UTF_8));}
        for(String t:new String[]{"migration.cursor","migration.catalog","migration.content_reference"})h.update(tableDigest(d,t).getBytes(StandardCharsets.UTF_8));return hex(h.digest());
    }
    static void seal(Db d)throws Exception{d.exec("INSERT OR REPLACE INTO migration.receipt VALUES(1,?)",fingerprint(d));}
    public String verify(File g)throws Exception {
        resume(g,PodTakeoverCoordinator.Phase.VERIFYING);
        Properties receipt=new Properties();
        File audit=new File(g,"verify-"+UUID.randomUUID());Files.createDirectories(audit.toPath());
        Files.copy(new File(g,"snapshot.properties").toPath(),new File(audit,"snapshot.properties").toPath());
        try(Db sourceDb=factory.open(new File(g,"room.sqlite"));Db expected=target(audit)) {
            initialize(expected,audit);
            for(String table:TABLES) {
                List<String> primary=keys(sourceDb,table);JSONArray after=null;
                while(true) {
                    List<Map<String,Object>> rows=page(sourceDb,table,primary,after);if(rows.isEmpty())break;
                    expected.exec("BEGIN IMMEDIATE");try {
                        for(Map<String,Object> row:rows)convert(sourceDb,expected,table,row);
                        expected.exec("COMMIT");
                    }catch(Exception e){expected.exec("ROLLBACK");throw e;}
                    after=new JSONArray();for(String k:primary)after.put(rows.get(rows.size()-1).get(k));
                }
            }
            checks(expected);
        }
        try(Db expected=target(audit);Db s=factory.open(new File(g,"room.sqlite"));Db d=target(g)){
            checks(d);
            for(Map<String,Object> row:d.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                String table=(String)row.get("name");
                if(!tableDigest(expected,table).equals(tableDigest(d,table)))throw new IOException("readback_mismatch");
            }
            for(String table:new String[]{"migration.catalog","migration.content_reference"})if(!tableDigest(expected,table).equals(tableDigest(d,table)))throw new IOException("catalog_readback_mismatch");
            for(String t:TABLES){long expectedCount=number(scalar(s,"SELECT count(*) FROM "+q(t)));if(expectedCount!=number(scalar(d,"SELECT rows_done FROM migration.cursor WHERE table_name=?",t)))throw new IOException("source_count_mismatch");receipt.setProperty("source."+t+".count",Long.toString(expectedCount));receipt.setProperty("source."+t+".digest",tableDigest(s,t));}
            for(Map<String,Object> r:d.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")){String t=(String)r.get("name");receipt.setProperty("target."+t+".count",String.valueOf(scalar(d,"SELECT count(*) FROM "+q(t))));receipt.setProperty("target."+t+".digest",tableDigest(d,t));}
            receipt.setProperty("orphan.saved",String.valueOf(scalar(s,"SELECT count(*) FROM saved_sync_states WHERE itemId IS NOT NULL AND itemId NOT IN (SELECT id FROM rss_items)")));
            receipt.setProperty("orphan.folders",String.valueOf(scalar(s,"SELECT count(*) FROM watch_notes WHERE folderId IS NOT NULL AND folderId NOT IN (SELECT folderId FROM watch_note_folders)")));
            receipt.setProperty("deferred.contentReferences",String.valueOf(scalar(s,"SELECT count(*) FROM rss_items WHERE content LIKE 'watchrss-watch-content:%' OR originalContent LIKE 'watchrss-watch-content:%'")));
            JSONObject catalogs=new JSONObject().put("version",1);JSONObject assets=new JSONObject();
            for(String t:new String[]{"reader_presets","reader_font_assets","reader_background_assets","reader_deletions"}){
                JSONArray rows=new JSONArray();for(Map<String,Object> r:d.query("SELECT value_json FROM migration.catalog WHERE table_name=? ORDER BY key_json",t))rows.put(new JSONObject((String)r.get("value_json")));
                if(t.equals("reader_presets"))catalogs.put("records",rows);else assets.put(t.equals("reader_font_assets")?"fonts":t.equals("reader_background_assets")?"backgrounds":"deletions",rows);
            }
            catalogs.put("assets",assets);File file=new File(g,"reader-catalog.json");try(FileOutputStream out=new FileOutputStream(file)){out.write(catalogs.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            receipt.setProperty("catalog.digest",hash(file));receipt.setProperty("activationReady","false");receipt.setProperty("digest",fingerprint(d));
        }
        PodTakeoverCoordinator.atomic(new File(g,"room-verification.properties"),receipt);
        // Only scratch created by this verification invocation is removed.
        File[] scratch=audit.listFiles();if(scratch!=null)for(File f:scratch)Files.delete(f.toPath());Files.delete(audit.toPath());
        return receipt.getProperty("digest");
    }
}
