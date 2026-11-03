package dev.podjs.runtime;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=34)
public class PodVideoDecoderPeakTest {
 @Test public void transientLeaseBorrowsThirdSlotAndForegroundWaitsForActualCodecRelease() {
  PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
  assertTrue(budget.acquire());assertTrue(budget.acquire());assertTrue(budget.acquireSpare());
  assertFalse(budget.acquire());assertFalse(budget.acquireSpare());
  boolean[] ready={false};budget.preemptSpare(()->ready[0]=true);
  assertTrue(budget.spareCancelled());assertFalse(ready[0]);assertEquals(3,budget.inUse());
  budget.releaseSpare();assertTrue(ready[0]);assertFalse(budget.acquireSpare());
  assertTrue(budget.acquire());budget.foregroundAdmitted();assertEquals(3,budget.peakInUse());
  budget.release();budget.release();budget.release();
  budget.restrictTo(1);assertFalse(budget.acquireSpare());assertTrue(budget.acquire());budget.release();
 }
 @Test public void peakIncludesShortLivedLeasesAndSurvivesCancellation() {
  PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
  assertTrue(budget.acquire());assertTrue(budget.acquire());assertTrue(budget.acquire());
  assertFalse(budget.acquire());assertEquals(3,budget.peakInUse());
  budget.release();budget.release();budget.release();
  assertEquals(0,budget.inUse());assertEquals(3,budget.peakInUse());
  for(int i=0;i<50;i++){assertTrue(budget.acquire());budget.release();}
  assertEquals(3,budget.peakInUse());
 }
}
