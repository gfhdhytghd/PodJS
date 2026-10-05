package dev.podjs.runtime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Composite remains incomplete until P2d file conversion and validation exist. */
final class PodTakeoverConverters implements PodTakeoverCoordinator.Converter {
    private final PodTakeoverCoordinator.Converter room,stores;
    PodTakeoverConverters(PodTakeoverCoordinator.Converter room,PodTakeoverCoordinator.Converter stores){this.room=room;this.stores=stores;}
    public String version(){return room.version()+"+"+stores.version();}
    public boolean complete(){return false;}
    public void snapshot(File g)throws Exception{stores.snapshot(g);room.snapshot(g);
        if(!PodStoreConverter.identity(new File(g,"stores-source")).trim().equals(PodTakeoverCoordinator.read(new File(g,"snapshot.properties")).getProperty("identity")))throw new java.io.IOException("identity_snapshot_mismatch");}
    public void copy(File g)throws Exception{room.copy(g);room.verify(g);stores.copy(g);}
    public void resume(File g,PodTakeoverCoordinator.Phase p)throws Exception{room.resume(g,p);stores.resume(g,p);}
    public String verify(File g)throws Exception{return PodRoomV18Converter.hex(MessageDigest.getInstance("SHA-256").digest((room.verify(g)+stores.verify(g)).getBytes(StandardCharsets.UTF_8)));}
}
