package dev.podjs.runtime;
import java.util.ArrayList;
public class DeviceServiceTraceTest {
 public static void main(String[] args){
  ArrayList<String> lines=new ArrayList<>();DeviceServiceTrace off=new DeviceServiceTrace(false,lines::add);
  if(off.begin()!=0)throw new AssertionError("off path clock");off.end("secure.key",0,3);if(!lines.isEmpty())throw new AssertionError("off emitted");
  DeviceServiceTrace on=new DeviceServiceTrace(true,lines::add);long began=on.begin();on.end("cookie_value",began,7);if(!lines.isEmpty())throw new AssertionError("unallowlisted phase");
  for(int i=0;i<4100;i++)on.end("secure.key",began,7);if(lines.size()!=4096)throw new AssertionError("cap");
  if(!lines.get(0).matches("host phase=secure.key id=7 ns=[0-9]+"))throw new AssertionError("numeric metadata");
  new DeviceServiceTrace(true,line->{throw new IllegalStateException();}).end("secure.total",began,4);
  System.out.println("DeviceServiceTrace PASS: off, allowlist, cap, numeric metadata, output failure");
 }
}
