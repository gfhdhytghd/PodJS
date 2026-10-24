package dev.podjs.runtime;
import java.io.File;
import java.util.Map;
/** Independent Room19 contract. V18 never accepts this identity or its extra column. */
public final class PodRoomV19Converter extends PodRoomV18Converter {
    public PodRoomV19Converter(File source,Factory factory,String identity){super(source,factory,identity);}
    PodRoomV19Converter(File source,Factory factory,SnapshotIdentitySource identity,ProgressHook progress){super(source,factory,identity,progress);}
    @Override public String version(){return "watchrss-room-v19-1";}
    @Override protected String sourceContract(Db db){return "room-v19.json";}
    @Override protected void validateSource(Db db)throws Exception {validateRoom19(db);}
    static void validateRoom19(Db db)throws Exception {validateContract(db,19,"room-v19.json");}
    @Override protected void migrateReadingSource(Db db,Map<String,Object> row)throws Exception {
        migrateSource(db,row);
    }
    static void migrateSource(Db db,Map<String,Object> row)throws Exception {
        // Keep provenance independently of optional sync IDs, including unread/no-position rows.
        db.exec("CREATE TABLE IF NOT EXISTS native_reading_sources(item_id INTEGER PRIMARY KEY REFERENCES articles(id) ON DELETE CASCADE, source_device_id TEXT NOT NULL,changed_at INTEGER NOT NULL)");
        db.exec("INSERT INTO native_reading_sources(item_id,source_device_id,changed_at) VALUES(?,?,?)",row.get("id"),row.get("readingStateSourceDeviceId"),row.get("readingPositionChangedAt"));
    }
}
