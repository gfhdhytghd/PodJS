package dev.podjs.runtime;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=34)
public class PodVideoDecoderPeakTest {
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
