package dev.podjs.runtime;
import org.json.*;import java.util.*;
public class IdleDiagnosticsTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] a)throws Exception{
  JSONObject row=new JSONObject().put("version",1).put("elapsedMs",30000).put("counts",new JSONArray("[[1,1800],[35,90]]")).put("private","cookie secret https://private.invalid");
  String line=IdleDiagnostics.format(row,1800);check(line.equals("metric=js windowMs=30000 nativeFrames=1800 counts=1:1800,35:90"));check(!line.contains("private"));
  row.put("version",2);check(IdleDiagnostics.format(row,1).contains("schema=2"));
  for(String bad:new String[]{"[[128,1]]","[[1,1],[1,2]]","[[1,-1]]","[[1,1.5]]","[[1,\"secret\"]]"}){row.put("counts",new JSONArray(bad));check(IdleDiagnostics.format(row,1)==null);}
  ServiceWorkStats stats=new ServiceWorkStats();stats.sqlSite(3,12,7,1);stats.sqlSite(3,13,8,2);stats.sqlSite(999,1,1,1);List<String> lines=new ArrayList<>();stats.emit(30_000_000_001L,true,false,lines::add);check(lines.stream().anyMatch(v->v.contains("site=3 ")&&v.contains("count=2 units=3 workNs=25 cpuNs=15")));check(lines.stream().anyMatch(v->v.contains("site=0 ")));lines.clear();stats.emit(60_000_000_001L,true,false,lines::add);check(lines.isEmpty());System.out.println("IDLE_DIAGNOSTICS_PASS closed numeric slots, malformed rejection, SQL aggregation and drain");
 }
}
