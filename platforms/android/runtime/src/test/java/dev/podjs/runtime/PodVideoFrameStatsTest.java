package dev.podjs.runtime;
import org.junit.Test;
import static org.junit.Assert.*;
public class PodVideoFrameStatsTest {
 @Test public void countsFirstPlayingGapAndSubsequentGaps(){PodVideoFrameStats s=new PodVideoFrameStats();s.playing(true,1_000_000L);s.updated(1_401_000_000L);s.updated(1_435_000_000L);assertEquals(2,s.updates);assertEquals(1_400_000_000L,s.maxGapNs);assertEquals(1,s.gapsOver500Ms);}
 @Test public void pausedAndBufferingTransitionsDoNotCreateTextureGap(){PodVideoFrameStats s=new PodVideoFrameStats();s.playing(true,1);s.updated(20_000_001);s.playing(false,30_000_001);s.updated(9_000_000_001L);s.playing(true,10_000_000_001L);s.updated(10_020_000_001L);assertEquals(20_000_000L,s.maxGapNs);assertEquals(2,s.updates);}
 @Test public void reportsOpenAndClosedRebufferIntervals(){PodVideoFrameStats s=new PodVideoFrameStats();s.buffering(true,100);s.buffering(true,200);assertEquals(1,s.bufferingCount);assertTrue(s.summary(1_000_100).contains("rebufferMs=1.0"));s.buffering(false,2_000_100);assertEquals(2_000_000,s.bufferingNs);assertTrue(s.summary(4_000_100).contains("rebufferMs=2.0"));}
}
