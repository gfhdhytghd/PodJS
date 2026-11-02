package dev.podjs.runtime;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class PodVideoMemoryBudgetTest {
 private static final long M=1024L*1024;
 private static final long WATCH_AVAILABLE=920*M, WATCH_THRESHOLD=216*M;
 private static final long WORST_SLOT=PodVideoMemoryBudget.decoderBytes(1080,1920);
 private static int watch(PodVideoMemoryBudget budget,int resident) {
  return budget.limit(WATCH_AVAILABLE-WATCH_THRESHOLD,false,WORST_SLOT,resident);
 }
 @Test public void watchSystemMemoryAdmitsThreeWorstCaseSlotsFromColdThroughResidentPool() {
  PodVideoMemoryBudget budget=new PodVideoMemoryBudget();
  for(int resident=0;resident<=3;resident++)assertEquals(3,watch(budget,resident));
  assertEquals(WORST_SLOT,PodVideoMemoryBudget.decoderBytes(0,0));
 }
 @Test public void nativeHeadroomAndDimensionsBoundSlotsButKeepForeground() {
  PodVideoMemoryBudget budget=new PodVideoMemoryBudget();long small=budget.decoderBytes(640,360);
  assertEquals(2,budget.limit(budget.RESERVE_BYTES+2*WORST_SLOT,false,WORST_SLOT,0));
  assertEquals(1,budget.limit(budget.RESERVE_BYTES+WORST_SLOT,false,WORST_SLOT,0));
  assertEquals(1,budget.limit(budget.RESERVE_BYTES,false,small,3));
  assertEquals(1,budget.limit(0,false,small,3));
  assertEquals(1,budget.limit(-1,false,small,3));
  assertEquals(1,budget.limit(200*M,false,budget.decoderBytes(3840,2160),0));
  assertEquals(3,budget.limit(200*M,false,small,0));
  assertEquals(1,budget.limit(WATCH_AVAILABLE-WATCH_THRESHOLD,true,small,3));
  assertEquals(3,watch(budget,0)); // Clearing lowMemory is not a permanent latch.
  assertEquals(3,budget.limit(Long.MAX_VALUE,false,small,Integer.MAX_VALUE));
 }
 @Test public void pendingPreparesDoNotSpendTheSameNativeHeadroomTwice() {
  PodVideoMemoryBudget budget=new PodVideoMemoryBudget();long two=budget.RESERVE_BYTES+2*WORST_SLOT;
  assertEquals(2,budget.limit(two,false,WORST_SLOT,0,0));
  assertEquals(2,budget.limit(two,false,WORST_SLOT,1,1));
  assertEquals(2,budget.limit(two,false,WORST_SLOT,2,2));
  assertEquals(2,budget.limit(two-WORST_SLOT,false,WORST_SLOT,2,1));
  assertEquals(2,budget.limit(two-2*WORST_SLOT+1,false,WORST_SLOT,2,0));
 }
 @Test public void lowerTrimReportsRecoverCapacityAndUiHiddenDoesNotClearPressure() {
  AtomicLong now=new AtomicLong();PodVideoMemoryBudget budget=new PodVideoMemoryBudget(now::get);
  budget.trim(10);assertEquals(2,watch(budget,3));
  budget.trim(15);assertEquals(1,watch(budget,3));
  budget.trim(20);assertEquals(1,watch(budget,3));
  budget.trim(10);assertEquals(2,watch(budget,1));
  budget.trim(0);assertEquals(3,watch(budget,1));
 }
 @Test public void quietRecoveryRequiresRecoveredNativeMemoryAndFreshPressureRestartsTheClock() {
  AtomicLong now=new AtomicLong(100);PodVideoMemoryBudget budget=new PodVideoMemoryBudget(now::get);
  budget.trim(15);now.addAndGet(budget.RECOVERY_QUIET_MS-1);assertEquals(1,watch(budget,1));
  budget.trim(15);now.addAndGet(budget.RECOVERY_QUIET_MS-1);assertEquals(1,watch(budget,1));
  now.incrementAndGet();assertEquals(1,budget.limit(budget.RESERVE_BYTES+1,false,WORST_SLOT,1));
  assertEquals(1,budget.limit(WATCH_AVAILABLE-WATCH_THRESHOLD,true,WORST_SLOT,1));
  assertEquals(3,watch(budget,1));
  // Recovery is not tied to recreating this budget or releasing the foreground.
  budget.trim(10);assertEquals(2,watch(budget,1));now.addAndGet(budget.RECOVERY_QUIET_MS);assertEquals(3,watch(budget,1));
 }
 @Test public void completeTrimStaysSingleSlotUntilServiceRebuild() {
  AtomicLong now=new AtomicLong();PodVideoMemoryBudget budget=new PodVideoMemoryBudget(now::get);
  budget.trim(80);now.addAndGet(10*budget.RECOVERY_QUIET_MS);budget.trim(0);assertEquals(1,watch(budget,0));
  assertEquals(3,watch(new PodVideoMemoryBudget(now::get),0));
 }
 /** Run this same device profile in an actual 128 MiB managed-heap process. */
 public static void main(String[] args) {
  long heap=Runtime.getRuntime().maxMemory();
  if(heap!=128*M)throw new AssertionError("expected -Xmx128m, got "+heap);
  int slots=watch(new PodVideoMemoryBudget(),0);
  if(slots!=3)throw new AssertionError("watch native memory must admit three slots");
  System.out.println("DYFIX2_WATCH_HEAP maxHeapMiB="+heap/M+" availMiB=920 thresholdMiB=216 width=1080 height=1920 slots="+slots);
 }
}
