package dev.podjs.runtime;
import java.util.*;
public final class ProcessDataSignalsTest {
 public static void main(String[] args){
  List<String> received=new ArrayList<>();Runnable off=ProcessDataSignals.subscribe("app",received::add),other=ProcessDataSignals.subscribe("other",s->{throw new AssertionError("cross app");});
  Runnable broken=ProcessDataSignals.subscribe("app",s->{throw new IllegalStateException();});
  ProcessDataSignals.publish("app","library");if(!received.equals(List.of("library")))throw new AssertionError();off.run();off.run();ProcessDataSignals.publish("app","library");if(received.size()!=1)throw new AssertionError();
  for(String bad:new String[]{"", "../bad", "UPPER", "a".repeat(65)}){boolean rejected=false;try{ProcessDataSignals.publish("app",bad);}catch(IllegalArgumentException expected){rejected=true;}if(!rejected)throw new AssertionError();}
  broken.run();other.run();System.out.println("PROCESS_DATA_SIGNALS_PASS owner isolation, unsubscribe, broken listener, invalid topic");
 }
}
