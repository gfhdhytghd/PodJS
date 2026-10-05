package dev.podjs.runtime;

import java.io.File;

/** Process-lifetime admission. Only successful terminal resolution is cached.
 * No Context is retained, and the fast path does not call the resolver at all. */
final class PodTakeoverAdmission {
    interface Resolver { Paths resolve() throws Exception; }
    static final class Paths {
        final File data, files, database;
        private Paths(File data, File database) {
            this.data=data; this.files=new File(data,"files"); this.database=database;
        }
        static Paths resolve(PodTakeoverCoordinator coordinator, File legacyData, File legacyDatabase) throws Exception {
            // activeGeneration rejects every state other than NONE/validated ACTIVE.
            File generation=coordinator.activeGeneration(coordinator.run());
            return generation==null ? new Paths(legacyData,legacyDatabase)
                : new Paths(new File(generation,"podjs"),new File(generation,"podjs.sqlite"));
        }
    }
    private volatile Paths admitted;
    Paths paths(Resolver resolver) throws Exception {
        Paths result=admitted;
        if(result!=null)return result;
        synchronized(this) {
            result=admitted;
            if(result==null) {
                result=java.util.Objects.requireNonNull(resolver.resolve());
                admitted=result;
            }
            return result;
        }
    }
}
