package dev.podjs.runtime;
import java.io.File;
import java.util.Map;
/** Select only after the live source+WAL have been copied to a private snapshot.
 * Preserve the published v18 receipt version so interrupted v18 captures remain resumable.
 * Each source is still checked against its own exact schema and identity contract. */
final class PodRoomVersionsConverter extends PodRoomV18Converter {
    PodRoomVersionsConverter(File source,Factory factory,SnapshotIdentitySource identity,ProgressHook progress){super(source,factory,identity,progress);}
    @Override protected String sourceContract(Db db)throws Exception {return number(scalar(db,"PRAGMA user_version"))==19?"room-v19.json":"room-v18.json";}
    @Override protected void validateSource(Db db)throws Exception {
        if(number(scalar(db,"PRAGMA user_version"))==19)PodRoomV19Converter.validateRoom19(db);
        else super.validateSource(db);
    }
    @Override protected void migrateReadingSource(Db db,Map<String,Object> row)throws Exception {
        if(row.containsKey("readingStateSourceDeviceId"))PodRoomV19Converter.migrateSource(db,row);
    }
}
