package dev.podjs.runtime;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Real P2a + P2b + P2c composition against a synthetic Room v18 database. */
public final class StoreRoomTest {
    public static void main(String[] args)throws Exception {
        File source=StoreTest.fixture(),database=new File(source,"databases/watchrss.db");database.getParentFile().mkdirs();
        try(RoomV18Test.Jdbc d=new RoomV18Test.Jdbc(database)){RoomV18Test.fixture(d);}
        Map<String,String> before=StoreTest.digests(source);File root=Files.createTempDirectory("pod-composite").toFile();
        StoreTest.Crypto crypto=new StoreTest.Crypto();boolean[] interrupted={false};
        PodStoreConverter stores=new PodStoreConverter(source,crypto,(store,boundary)->{if(store.equals("watchrss_account_state")&&boundary.equals("files")&&!interrupted[0]){interrupted[0]=true;throw new IOException("power_loss");}});
        PodRoomV18Converter room=new PodRoomV18Converter(database,RoomV18Test.Jdbc::new,(File g)->PodStoreConverter.identity(new File(g,"stores-source")));
        PodTakeoverConverters composite=new PodTakeoverConverters(room,stores);
        PodTakeoverCoordinator coordinator=new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(true,null,18,0,Long.MAX_VALUE),composite);
        Properties first=coordinator.run();StoreTest.check(interrupted[0]&&first.getProperty("phase").equals("PAUSED")&&first.getProperty("resumePhase").equals("COPYING"));StoreTest.check(!new File(root,"active").exists());
        Properties finalState=coordinator.run();StoreTest.check(finalState.getProperty("phase").equals("PAUSED")&&finalState.getProperty("reason").equals("converters_incomplete")&&finalState.getProperty("resumePhase").equals("VERIFYING"));
        File candidate=new File(root,"generations/"+finalState.getProperty("generation"));JSONObject reader=PodStoreConverter.json(new File(candidate,"podjs/podjs-kv.json")).getJSONObject("watchrss.reader-presets");StoreTest.check(reader.getJSONArray("records").length()==3&&reader.getJSONObject("assets").getJSONArray("fonts").length()==3&&reader.getJSONObject("selection").getString("mode").equals("SYSTEM"));
        try(RoomV18Test.Jdbc d=new RoomV18Test.Jdbc(new File(candidate,"podjs.sqlite"))){StoreTest.check(PodRoomV18Converter.scalar(d,"SELECT device_id FROM sync_local_identity").equals("synthetic-device"));}
        String receipt=composite.verify(candidate);coordinator.run();StoreTest.check(receipt.equals(composite.verify(candidate)));StoreTest.check(!new File(root,"active").exists());StoreTest.check(before.equals(StoreTest.digests(source)));
        StoreTest.remove(root);StoreTest.remove(source);System.out.println("StoreRoomTest PASS: fixed identity, reader catalog merge, interrupted composite resume, PAUSED converters_incomplete, no activation/source writes");
    }
}
