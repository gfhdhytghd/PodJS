package dev.podjs.takeover;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Shared with native recovery packages. No Android, storage decoder, or Keystore APIs.
 * Call under the package admission lock before opening any native/PodJS store.
 * Private manifests contain filenames/digests: never log them. */
public final class NativeBackup {
    public static final String BACKUP = "files/.podjs-takeover-backup";
    public static final String RECOVERED = "files/.podjs-recovered";
    public interface Hook { void at(String boundary) throws IOException; }
    public interface Space { long available(File sandbox); }
    private final File sandbox;
    private final Hook hook;
    private final Space space;
    public NativeBackup(File sandbox) throws IOException { this(sandbox, b -> {}, File::getUsableSpace); }
    public NativeBackup(File sandbox, Hook hook, Space space) throws IOException {
        this.sandbox=sandbox.getCanonicalFile(); this.hook=hook; this.space=space;
    }
    private static void require(boolean condition) throws IOException {
        if(!condition) throw new IOException("backup_invalid");
    }
    private static void id(String g) throws IOException { require(g!=null&&g.matches("g-[a-zA-Z0-9-]+")); }
    private File path(String relative) throws IOException {
        require(relative!=null&&!relative.isEmpty()&&!relative.startsWith("/")&&!relative.contains("\\"));
        for(String part:relative.split("/",-1))require(!part.isEmpty()&&!part.equals(".")&&!part.equals(".."));
        File file=new File(sandbox,relative);
        require(file.getCanonicalFile().equals(file.getAbsoluteFile()));
        for(Path p=file.toPath();p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));
        return file;
    }
    private static boolean exists(File f) { return Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS); }
    private static File[] children(File f) throws IOException {
        File[] entries=f.listFiles();require(entries!=null);
        Arrays.sort(entries,Comparator.comparing(File::getName));return entries;
    }
    private static boolean owned(String p) {
        String n=p.substring(p.indexOf('/')+1);
        if(p.startsWith("files/"))return n.equals("podjs")||n.equals("package-takeover")||n.startsWith(".podjs-");
        if(p.startsWith("databases/"))return n.equals("podjs.sqlite")||n.startsWith("podjs.sqlite-");
        return p.startsWith("no_backup/")&&n.startsWith("podjs-");
    }
    private List<String> nativeRoots() throws IOException {
        List<String> roots=new ArrayList<>();
        for(String parent:new String[]{"databases","shared_prefs","files","no_backup"}) {
            File directory=path(parent);if(!exists(directory))continue;
            require(directory.isDirectory());
            for(File child:children(directory)) {
                String p=parent+"/"+child.getName();if(!owned(p))roots.add(p);
            }
        }
        return roots;
    }
    private void inventory(File file,String relative,Properties p,List<String> entries) throws IOException {
        path(sandbox.toPath().relativize(file.toPath()).toString());
        require(exists(file));
        int i=entries.size();entries.add(relative);
        p.setProperty("entry."+i+".path",relative);
        p.setProperty("entry."+i+".originalRelativePath",relative);
        p.setProperty("entry."+i+".backupRelativePath","data/"+relative);
        if(Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            p.setProperty("entry."+i+".type","directory");
            for(File child:children(file))inventory(child,relative+"/"+child.getName(),p,entries);
        } else {
            require(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS));
            p.setProperty("entry."+i+".type","file");
            p.setProperty("entry."+i+".size",Long.toString(file.length()));
            p.setProperty("entry."+i+".sha256",hash(file));
        }
    }
    private static String hex(byte[] b) {
        StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();
    }
    public static String hash(File file) throws IOException {
        try {
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);
            }return hex(d.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IOException("sha_unavailable"); }
    }
    private static String seal(Properties p) throws IOException {
        try {
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            for(String k:new TreeSet<>(p.stringPropertyNames())) {
                byte[] key=k.getBytes(java.nio.charset.StandardCharsets.UTF_8),value=p.getProperty(k).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                d.update(java.nio.ByteBuffer.allocate(4).putInt(key.length).array());d.update(key);
                d.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());d.update(value);
            }return hex(d.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IOException("sha_unavailable"); }
    }
    private static void sync(File directory) throws IOException {
        try(FileChannel c=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){c.force(true);}
    }
    private void mkdir(File directory) throws IOException {
        path(sandbox.toPath().relativize(directory.toPath()).toString());
        if(exists(directory)){require(directory.isDirectory());return;}
        File parent=directory.getParentFile();if(!parent.equals(sandbox))mkdir(parent);
        Files.createDirectory(directory.toPath());sync(parent);
    }
    private Properties read(File f) throws IOException {
        path(sandbox.toPath().relativize(f.toPath()).toString());
        Properties p=new Properties();if(exists(f))try(InputStream in=Files.newInputStream(f.toPath(),LinkOption.NOFOLLOW_LINKS)){p.load(in);}return p;
    }
    private void atomic(File f,Properties p) throws IOException {
        mkdir(f.getParentFile());File tmp=path(sandbox.toPath().relativize(f.toPath()).toString()+".tmp");
        try(FileChannel c=FileChannel.open(tmp.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            OutputStream out=Channels.newOutputStream(c);p.store(out,null);out.flush();c.force(true);
        }
        Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(f.getParentFile());
    }
    private void move(File from,File to) throws IOException {
        require(exists(from)&&!exists(to));mkdir(to.getParentFile());
        // No copy fallback: both paths are in the same sandbox. EXDEV or metadata ENOSPC
        // pauses admission, retaining the plan and every unmoved source.
        Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);
        sync(from.getParentFile());sync(to.getParentFile());
    }
    private int count(Properties p,String key) throws IOException {
        try { int n=Integer.parseInt(p.getProperty(key));require(n>=0&&n<=1000000);return n; }
        catch(RuntimeException e){throw new IOException("backup_invalid");}
    }
    private String root(Properties p,int i) throws IOException {
        String r=p.getProperty("root."+i);path(r);require(r.split("/",-1).length==2);
        require(r.startsWith("databases/")||r.startsWith("shared_prefs/")||r.startsWith("files/")||r.startsWith("no_backup/"));
        require(!owned(r));return r;
    }
    private Properties manifest(String g) throws IOException {
        id(g);Properties p=read(path(BACKUP+"/"+g+"/manifest"));
        require("1".equals(p.getProperty("protocol"))&&g.equals(p.getProperty("generation")));
        require(p.getProperty("certificateSha256","").matches("[a-f0-9]{64}"));
        try { require(Integer.parseInt(p.getProperty("nativeVersionCode"))>0); }
        catch(RuntimeException e){throw new IOException("backup_invalid");}
        Properties s=read(path(BACKUP+"/"+g+"/seal"));require(seal(p).equals(s.getProperty("sha256")));
        Set<String> roots=new HashSet<>();for(int i=0;i<count(p,"roots");i++)require(roots.add(root(p,i)));
        Set<String> entries=new HashSet<>();
        for(int i=0;i<count(p,"entries");i++) {
            String r=p.getProperty("entry."+i+".path");path(r);require(entries.add(r));
            require(r.equals(p.getProperty("entry."+i+".originalRelativePath"))&&("data/"+r).equals(p.getProperty("entry."+i+".backupRelativePath")));
            String[] parts=r.split("/");require(parts.length>=2&&roots.contains(parts[0]+"/"+parts[1]));
            String type=p.getProperty("entry."+i+".type");require("file".equals(type)||"directory".equals(type));
            if(type.equals("file")) {
                require(p.getProperty("entry."+i+".sha256","").matches("[a-f0-9]{64}"));
                try { require(Long.parseLong(p.getProperty("entry."+i+".size"))>=0); }
                catch(RuntimeException e){throw new IOException("backup_invalid");}
            }
        }
        require(entries.containsAll(roots));return p;
    }
    private void checkRoot(Properties p,String r,File actual) throws IOException {
        Properties found=new Properties();List<String> entries=new ArrayList<>();inventory(actual,r,found,entries);
        Map<String,String> expected=new TreeMap<>(),observed=new TreeMap<>();
        for(int i=0;i<count(p,"entries");i++) {
            String ep=p.getProperty("entry."+i+".path");
            if(ep.equals(r)||ep.startsWith(r+"/"))expected.put(ep,description(p,i));
        }
        for(int i=0;i<entries.size();i++)observed.put(found.getProperty("entry."+i+".path"),description(found,i));
        require(expected.equals(observed));
    }
    private static String description(Properties p,int i) {
        String k="entry."+i+".";return p.getProperty(k+"type")+":"+p.getProperty(k+"size","")+":"+p.getProperty(k+"sha256","");
    }
    /** Called only after complete converter verification, before the active pointer. */
    private static IOException spaceFailure(IOException error) {
        if(error instanceof FileSystemException) {
            String reason=((FileSystemException)error).getReason();
            if(reason!=null&&(reason.contains("ENOSPC")||reason.toLowerCase(Locale.ROOT).contains("no space")))return new IOException("space_insufficient");
        }
        return error;
    }
    public String prepare(String g,int nativeVersionCode,String certificateSha256) throws IOException {
        try{return prepareImpl(g,nativeVersionCode,certificateSha256);}catch(IOException error){throw spaceFailure(error);}
    }
    private String prepareImpl(String g,int nativeVersionCode,String certificateSha256) throws IOException {
        id(g);require(nativeVersionCode>0&&certificateSha256!=null&&certificateSha256.matches("[a-f0-9]{64}"));
        File mf=path(BACKUP+"/"+g+"/manifest");
        if(!exists(mf)) {
            Properties p=new Properties();List<String> roots=nativeRoots(),entries=new ArrayList<>();
            p.setProperty("protocol","1");p.setProperty("generation",g);
            p.setProperty("nativeVersionCode",Integer.toString(nativeVersionCode));
            p.setProperty("nativeVersionProvenance","app-declared-source-version");
            p.setProperty("certificateSha256",certificateSha256);p.setProperty("roots",Integer.toString(roots.size()));
            for(int i=0;i<roots.size();i++) {String r=roots.get(i);p.setProperty("root."+i,r);inventory(path(r),r,p,entries);}
            p.setProperty("entries",Integer.toString(entries.size()));
            if(space.available(sandbox)<Math.addExact(1048576L,entries.size()*2048L))throw new IOException("space_insufficient");
            // Seal first: no source moves until both immutable plan and seal are durable.
            Properties s=new Properties();s.setProperty("sha256",seal(p));atomic(path(BACKUP+"/"+g+"/seal"),s);atomic(mf,p);hook.at("planned");
        }
        Properties p=manifest(g);require(Integer.toString(nativeVersionCode).equals(p.getProperty("nativeVersionCode"))&&certificateSha256.equals(p.getProperty("certificateSha256")));
        for(int i=0;i<count(p,"roots");i++) {
            String r=root(p,i);File source=path(r),backup=path(BACKUP+"/"+g+"/data/"+r);
            require(exists(source)!=exists(backup));checkRoot(p,r,exists(backup)?backup:source);
            if(!exists(backup))move(source,backup);hook.at("backup-move-"+i);
        }
        verify(g);Properties ready=new Properties();ready.setProperty("sha256",seal(p));atomic(path(BACKUP+"/"+g+"/ready"),ready);hook.at("ready");return seal(p);
    }
    public String verify(String g) throws IOException {
        Properties p=manifest(g);for(int i=0;i<count(p,"roots");i++) {
            String r=root(p,i);checkRoot(p,r,path(BACKUP+"/"+g+"/data/"+r));
        }return seal(p);
    }
    private List<String> archives(Properties p) throws IOException {
        Set<String> all=new TreeSet<>();for(int i=0;i<count(p,"roots");i++)all.add(root(p,i));
        for(String parent:new String[]{"files","databases","shared_prefs","no_backup"}) {
            File dir=path(parent);if(!exists(dir))continue;
            for(File child:children(dir)) {
                String r=parent+"/"+child.getName();
                if(!r.startsWith(BACKUP+"/")&&!r.equals(BACKUP)&&!r.equals(RECOVERED)
                    &&!r.equals("files/.podjs-restore-stage")&&!r.equals("files/.podjs-recovery-state")&&!r.equals("files/.podjs-recovery-lock"))all.add(r);
            }
        }return new ArrayList<>(all);
    }
    private void copyTree(File from,File to) throws IOException {
        path(sandbox.toPath().relativize(from.toPath()).toString());require(exists(from));
        if(from.isDirectory()) {mkdir(to);for(File child:children(from))copyTree(child,new File(to,child.getName()));sync(to);}
        else {
            require(Files.isRegularFile(from.toPath(),LinkOption.NOFOLLOW_LINKS));
            mkdir(to.getParentFile());path(sandbox.toPath().relativize(to.toPath()).toString());
            if(exists(to))Files.setPosixFilePermissions(to.toPath(),java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            try(InputStream in=Files.newInputStream(from.toPath(),LinkOption.NOFOLLOW_LINKS);
                FileChannel out=FileChannel.open(to.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(b,0,n);while(buffer.hasRemaining())out.write(buffer);}out.force(true);
            }Files.setPosixFilePermissions(to.toPath(),Files.getPosixFilePermissions(from.toPath(),LinkOption.NOFOLLOW_LINKS));
            Files.setLastModifiedTime(to.toPath(),Files.getLastModifiedTime(from.toPath(),LinkOption.NOFOLLOW_LINKS));
            sync(to.getParentFile());hook.at("restore-copy");
        }
    }
    /** Recovery keeps backup payload intact. All native APIs/providers must remain unopened. */
    public void restore(String g,String expectedCertificate) throws IOException {
        try{restoreImpl(g,expectedCertificate);}catch(IOException error){throw spaceFailure(error);}
    }
    private void restoreImpl(String g,String expectedCertificate) throws IOException {
        id(g);Properties p=manifest(g);require(expectedCertificate.equals(p.getProperty("certificateSha256")));
        String state="files/.podjs-recovery-state/"+g;
        Properties done=read(path(state+"/complete"));
        if(!done.isEmpty()){require(seal(p).equals(done.getProperty("sha256")));return;}
        if(!exists(path(BACKUP+"/"+g+"/ready")))prepare(g,Integer.parseInt(p.getProperty("nativeVersionCode")),expectedCertificate);
        require(seal(p).equals(read(path(BACKUP+"/"+g+"/ready")).getProperty("sha256")));verify(g);
        Properties journal=read(path(state+"/journal"));
        if(journal.isEmpty()) {
            journal.setProperty("sha256",seal(p));List<String> a=archives(p);journal.setProperty("archives",Integer.toString(a.size()));
            for(int i=0;i<a.size();i++)journal.setProperty("archive."+i,a.get(i));atomic(path(state+"/journal"),journal);hook.at("restore-planned");
        }
        require(seal(p).equals(journal.getProperty("sha256")));
        String stage="files/.podjs-restore-stage/"+g;
        if(!"true".equals(journal.getProperty("archived"))) {
            Set<String> staged=new HashSet<>();
            for(int i=0;i<count(p,"roots");i++){String r=root(p,i);if(exists(path(stage+"/"+r)))try{checkRoot(p,r,path(stage+"/"+r));staged.add(r);}catch(IOException incomplete){}}
            long bytes=0;for(int i=0;i<count(p,"entries");i++)if("file".equals(p.getProperty("entry."+i+".type"))) {
                String ep=p.getProperty("entry."+i+".path");String[] parts=ep.split("/");
                if(!staged.contains(parts[0]+"/"+parts[1]))bytes=Math.addExact(bytes,Long.parseLong(p.getProperty("entry."+i+".size")));
            }
            if(space.available(sandbox)<Math.addExact(bytes,1048576L))throw new IOException("space_insufficient");
            for(int i=0;i<count(p,"roots");i++) {String r=root(p,i);if(!staged.contains(r))copyTree(path(BACKUP+"/"+g+"/data/"+r),path(stage+"/"+r));checkRoot(p,r,path(stage+"/"+r));}
            hook.at("restore-staged");
            for(int i=0;i<count(journal,"archives");i++) {
                String r=journal.getProperty("archive."+i);File from=path(r),to=path(RECOVERED+"/"+g+"/"+r);
                require(!r.equals(BACKUP)&&!r.equals(RECOVERED)&&!r.startsWith("files/.podjs-recovery-state")&&!r.startsWith("files/.podjs-restore-stage")&&!r.equals("files/.podjs-recovery-lock"));
                require(r.split("/",-1).length==2&&(r.startsWith("files/")||r.startsWith("databases/")||r.startsWith("shared_prefs/")||r.startsWith("no_backup/")));
                require(!(exists(from)&&exists(to)));if(exists(from))move(from,to);hook.at("archive-"+i);
            }
            journal.setProperty("archived","true");atomic(path(state+"/journal"),journal);hook.at("archived");
        }
        for(int i=0;i<count(p,"roots");i++) {
            String r=root(p,i);File from=path(stage+"/"+r),to=path(r);
            require(exists(from)!=exists(to));checkRoot(p,r,exists(from)?from:to);
            if(exists(from))move(from,to);hook.at("restore-move-"+i);
        }
        // Check every restored byte before recording completion; later native writes are allowed.
        for(int i=0;i<count(p,"roots");i++){String r=root(p,i);checkRoot(p,r,path(r));}
        Properties complete=new Properties();complete.setProperty("sha256",seal(p));Properties last=new Properties();last.setProperty("generation",g);atomic(path("files/.podjs-recovery-state/last"),last);
        atomic(path(state+"/complete"),complete);hook.at("complete");
    }
    /** Current journal identifies a new/partially sealed generation even when older
     * completed recoveries retain their backups. Never guess between unowned plans. */
    public String recoveryGeneration() throws IOException {
        File root=path(BACKUP);if(!exists(root))return null;
        Properties journal=read(path("files/package-takeover/journal"));
        Properties active=read(path("files/package-takeover/active"));
        String selected=active.getProperty("generation",journal.getProperty("generation"));
        if(selected!=null) {id(selected);require(exists(path(BACKUP+"/"+selected)));manifest(selected);return selected;}
        String last=read(path("files/.podjs-recovery-state/last")).getProperty("generation");
        if(last!=null){id(last);if(exists(path(BACKUP+"/"+last))){manifest(last);return last;}}
        String found=null;
        for(File child:children(root))if(child.getName().startsWith("g-")) {
            require(found==null);String g=child.getName();manifest(g);found=g;
        }return found;
    }
    /** Includes interrupted purge trash, for resumption only under an explicit policy. */
    public List<String> retainedGenerations() throws IOException {
        SortedSet<String> result=new TreeSet<>();File root=path(BACKUP);if(!exists(root))return new ArrayList<>();
        for(File child:children(root)) {
            String name=child.getName();
            if(name.startsWith("g-")){id(name);result.add(name);}
            else if(name.startsWith(".purging-g-")){String g=name.substring(".purging-".length());id(g);result.add(g);}
        }return new ArrayList<>(result);
    }
    /** Host/app explicit policy only. Atomic removal from the recovery namespace, then
     * interruptible deletion. Interrupted trash can only be resumed by another authorized call. */
    public void purge(String g) throws IOException {
        id(g);File original=path(BACKUP+"/"+g),trash=path(BACKUP+"/.purging-"+g);
        File ticket=path(BACKUP+"/.purge-"+g);
        if(exists(original)) {
            require(!exists(trash));String digest=verify(g);
            require(digest.equals(read(path(BACKUP+"/"+g+"/ready")).getProperty("sha256")));
            Properties t=new Properties();t.setProperty("generation",g);t.setProperty("sha256",digest);atomic(ticket,t);
            move(original,trash);hook.at("purge-renamed");
        }
        if(exists(trash)) {
            Properties t=read(ticket);require(g.equals(t.getProperty("generation"))&&t.getProperty("sha256","").matches("[a-f0-9]{64}"));
            deleteTree(trash);hook.at("purged");
        }
        if(exists(ticket)){Files.delete(ticket.toPath());sync(ticket.getParentFile());}
    }
    /** Explicit cleanup of converter-only duplicate source bytes. Retains active stores,
     * admission metadata and reports. ACTIVE must already be validated by the caller. */
    public void purgeImportSnapshots(String g) throws IOException {
        id(g);String base="files/package-takeover/generations/"+g;
        File trash=path(BACKUP+"/.purging-import-"+g);
        for(String name:new String[]{"room.sqlite","room.sqlite-wal","room.sqlite-shm","room-progress.sqlite","room-progress.sqlite-journal","stores-source","store-results","files-source"}) {
            File from=path(base+"/"+name),to=path(BACKUP+"/.purging-import-"+g+"/"+name);
            require(!(exists(from)&&exists(to)));if(exists(from))move(from,to);hook.at("purge-import-move");
        }
        if(exists(trash))deleteTree(trash);
    }
    private void deleteTree(File f) throws IOException {
        path(sandbox.toPath().relativize(f.toPath()).toString());
        if(f.isDirectory())for(File child:children(f))deleteTree(child);
        Files.delete(f.toPath());sync(f.getParentFile());hook.at("purge-delete");
    }
}
