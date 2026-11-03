package dev.podjs.runtime;
import java.util.*;
public final class CloudCpuBudgetTest {
    public static void main(String[] args){
        long previous=0;
        for(int round=1;round<=31;round++){
            long delay=CloudCpuBudget.delayMillis(0,round);
            if(delay<previous||delay>900000)throw new AssertionError("idle backoff");previous=delay;
        }
        for(long cpu:new long[]{0,1,100,1000,45000,300000,600000}){
            long delay=CloudCpuBudget.delayMillis(cpu,1);
            if(cpu>0&&cpu*1.0/(cpu+delay)>=0.05)throw new AssertionError("CPU share above budget");
        }
        if(CloudCpuBudget.delayMillis(Long.MAX_VALUE,1)!=Long.MAX_VALUE)throw new AssertionError("overflow");
        ServiceWorkStats stats=new ServiceWorkStats();List<String> lines=new ArrayList<>();
        stats.add("secure.get",5,10,3,20,1,true);stats.add("private-key-value",1,1,1,1,1,true);
        stats.add("sql.transaction",7,11,4,0,50,false);stats.emit(31_000_000_000L,false,true,lines::add);
        if(lines.size()!=2||lines.toString().contains("private-key-value")||!lines.get(0).contains("cpuNs=3")||!lines.get(1).contains("units=50"))throw new AssertionError("span/privacy mismatch");
        stats.emit(32_000_000_000L,true,true,lines::add);if(lines.size()!=2)throw new AssertionError("duplicate counts");
        System.out.println("CPU_BUDGET_PASS: 20x CPU cooldown, bounded idle backoff, numeric host phase accounting");
    }
}
