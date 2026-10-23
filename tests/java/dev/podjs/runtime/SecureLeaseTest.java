package dev.podjs.runtime;

import java.io.*;
import java.nio.file.Files;

public final class SecureLeaseTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static Process child(String mode,File path)throws Exception {
        return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),SecureLeaseTest.class.getName(),mode,path.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0){
            try(PodSecureLease lease=new PodSecureLease(new File(args[1]))){
                boolean acquired=lease.acquire("account");
                if("probe".equals(args[0])){System.exit(acquired?0:2);return;}
                check(acquired);System.out.println("ready");System.out.flush();Thread.sleep(30000);
            }return;
        }
        File root=Files.createTempDirectory("pod-secure-lease-").toFile();
        try(PodSecureLease first=new PodSecureLease(root);PodSecureLease second=new PodSecureLease(root)){
            check(first.acquire("account"));check(!first.acquire("account"));check(!second.acquire("account"));
            second.release("account");check(!second.acquire("account")); // Non-owner cannot unlock.
            check(second.acquire("unrelated"));check(child("probe",root).waitFor()==2);
            first.release("account");check(second.acquire("account"));second.close();
            check(child("probe",root).waitFor()==0);
            Process holder=child("hold",root);
            try{check("ready".equals(new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine()));check(!first.acquire("account"));}
            finally{holder.destroyForcibly();holder.waitFor();}
            check(first.acquire("account")); // OS releases a killed guest/process's lease.
            try{first.acquire("");throw new AssertionError();}catch(IllegalArgumentException expected){}
        }finally{for(File file:root.listFiles())check(file.delete());check(root.delete());}
        System.out.println("SecureLeaseTest: same guest, separate guests/processes, non-owner release and crash recovery passed");
    }
}
