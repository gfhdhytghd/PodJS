package dev.podjs.runtime;
import java.io.*;
import java.nio.file.*;
import org.json.*;
public final class RoomV19Test {
    static void rejects(PodRoomV18Converter.Db db,boolean v19)throws Exception {
        try {if(v19)PodRoomV19Converter.validateRoom19(db);else PodRoomV18Converter.validateRoom(db);throw new AssertionError("accepted wrong contract");}catch(IOException expected){}
    }
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]);Files.createDirectories(root.toPath());
        File source=new File(root,"native19.sqlite");
        try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(source)) {
            RoomV18Test.fixture(db,true);
            db.exec("ALTER TABLE rss_items ADD COLUMN readingStateSourceDeviceId TEXT NOT NULL DEFAULT ''");
            JSONObject contract=new JSONObject(PodRoomV18Converter.resource("room-v19.json"));
            db.exec("PRAGMA user_version=19");db.exec("UPDATE room_master_table SET identity_hash=? WHERE id=42",contract.getString("identity"));
            db.exec("UPDATE rss_items SET readingStateSourceDeviceId='设备😀' WHERE id=1");
            PodRoomV19Converter.validateRoom19(db);rejects(db,false);
            // Every column/type/PK is still checked even with the right identity.
            db.exec("ALTER TABLE rss_items ADD COLUMN unexpected TEXT");rejects(db,true);db.exec("ALTER TABLE rss_items DROP COLUMN unexpected");
            db.exec("UPDATE room_master_table SET identity_hash='bad' WHERE id=42");rejects(db,true);db.exec("UPDATE room_master_table SET identity_hash=? WHERE id=42",contract.getString("identity"));
        }
        String before=PodRoomV18Converter.hash(source);
        PodRoomV18Converter converter=new PodRoomVersionsConverter(source,RoomV18Test.Jdbc::new,g->"fixture-设备😀",n->{});
        File g=new File(root,"generation");g.mkdirs();converter.snapshot(g);converter.copy(g);converter.verify(g);
        try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(new File(g,"podjs.sqlite"))) {
            RoomV18Test.require(PodRoomV18Converter.number(PodRoomV18Converter.scalar(db,"SELECT COUNT(*) FROM offline_media_jobs"))==0,"authoritative migration queued downloads");
            RoomV18Test.require("设备😀".equals(PodRoomV18Converter.scalar(db,"SELECT source_device_id FROM native_reading_sources WHERE item_id=1")),"reading provenance lost");
            RoomV18Test.require(PodRoomV18Converter.number(PodRoomV18Converter.scalar(db,"SELECT COUNT(*) FROM articles"))==3,"rows lost");
            RoomV18Test.require(PodRoomV18Converter.number(PodRoomV18Converter.scalar(db,"SELECT COUNT(*) FROM native_reading_sources"))==3,"empty source rows lost");
        }
        try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(new File(g,"podjs.sqlite"))){db.exec("UPDATE native_reading_sources SET source_device_id='tamper' WHERE item_id=1");}
        try{converter.resume(g,PodTakeoverCoordinator.Phase.VERIFYING);throw new AssertionError("provenance tamper accepted");}catch(IOException expected){}
        RoomV18Test.require(before.equals(PodRoomV18Converter.hash(source)),"source changed");
        // Fresh v19 SQL from native KSP agrees with migrated-column fixture.
        try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(new File(root,"fresh19.sqlite"))){JSONArray sql=new JSONObject(PodRoomV18Converter.resource("room-v19.json")).getJSONArray("sql");for(int i=0;i<sql.length();i++)db.exec(sql.getString(i));db.exec("PRAGMA user_version=19");PodRoomV19Converter.validateRoom19(db);rejects(db,false);}
        System.out.println("Room19: fresh/migrated exact contracts, identity/column rejection, source integrity and provenance OK");
    }
}
