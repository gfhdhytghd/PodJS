package dev.podjs.runtime;

import static org.junit.Assert.*;
import org.junit.Test;

public class PodProcessMemoryReaderTest {
    static final class Source implements PodProcessMemoryReader.Source {
        PodProcessMemoryReader.Reading local=new PodProcessMemoryReader.Reading(90000,6400);
        PodProcessMemoryReader.Reading system=new PodProcessMemoryReader.Reading(138000,48000);
        int queries;boolean unavailable;
        public PodProcessMemoryReader.Reading local(){return local;}
        public PodProcessMemoryReader.Reading system(){queries++;if(unavailable)throw new IllegalStateException("unavailable");return system;}
    }
    @Test public void protectedGraphicsCanMoveAnOtherwisePassingProbeOverTheGate(){
        Source source=new Source();PodProcessMemoryReader.Snapshot snapshot=new PodProcessMemoryReader(source,()->0L).snapshot();
        assertEquals(131600,snapshot.totalKiB);assertEquals(41600,snapshot.correctionKiB);assertTrue(snapshot.totalKiB>125000);
    }
    @Test public void reportedGraphicsAreNotChargedTwiceOrReplacedByAnOlderLowerReading(){
        Source source=new Source();source.local=new PodProcessMemoryReader.Reading(120000,48000);
        PodProcessMemoryReader reader=new PodProcessMemoryReader(source,()->0L);
        assertEquals(120000,reader.snapshot().totalKiB);
        source.local=new PodProcessMemoryReader.Reading(127000,56000);
        assertEquals(127000,reader.snapshot().totalKiB);assertEquals(0,reader.snapshot().correctionKiB);
    }
    @Test public void currentCpuMovesWhileSystemGraphicsQueryIsCoalesced(){
        Source source=new Source();long[] now={0};PodProcessMemoryReader reader=new PodProcessMemoryReader(source,()->now[0]);
        assertEquals(131600,reader.snapshot().totalKiB);
        source.local=new PodProcessMemoryReader.Reading(80000,6400);now[0]=4999;
        PodProcessMemoryReader.Snapshot lower=reader.snapshot();assertEquals(121600,lower.totalKiB);assertEquals(1,source.queries);assertEquals(4999,lower.protectedQueryAgeMs);
        source.system=new PodProcessMemoryReader.Reading(130000,56000);now[0]=5000;
        assertEquals(129600,reader.snapshot().totalKiB);assertEquals(2,source.queries);
    }
    @Test public void failedProtectedRefreshNeverPublishesAnUncorrectedSuccessfulSample(){
        Source source=new Source();long[] now={0};PodProcessMemoryReader reader=new PodProcessMemoryReader(source,()->now[0]);reader.snapshot();
        source.unavailable=true;now[0]=5000;
        try{reader.snapshot();fail("missing protected coverage accepted");}catch(IllegalStateException expected){}
        source.unavailable=false;source.local=new PodProcessMemoryReader.Reading(80000,6400);now[0]=5001;
        assertEquals(121600,reader.snapshot().totalKiB);assertEquals(3,source.queries);
    }
    @Test public void oww242BudgetKeepsLocalPssAndBoundsStaleGraphicsAfterRetirement(){
        Source source=new Source();source.local=new PodProcessMemoryReader.Reading(73000,7000);
        PodProcessMemoryReader.Snapshot snapshot=new PodProcessMemoryReader(source,()->0L).snapshot();
        assertEquals(114000,snapshot.totalKiB);
        assertEquals(94096,snapshot.oww242BudgetKiB(0));
        assertEquals(114000,snapshot.oww242BudgetKiB(1));
        assertEquals(114000,snapshot.oww242BudgetKiB(2));
        source.local=new PodProcessMemoryReader.Reading(125000,54000);
        snapshot=new PodProcessMemoryReader(source,()->0L).snapshot();
        assertEquals(125000,snapshot.oww242BudgetKiB(0));
    }
    @Test public void oww242BudgetAccountsForActiveGraphicsBeforeTheSystemReadingCatchesUp(){
        Source source=new Source();source.local=new PodProcessMemoryReader.Reading(85000,7000);
        source.system=new PodProcessMemoryReader.Reading(74000,24000);
        PodProcessMemoryReader.Snapshot snapshot=new PodProcessMemoryReader(source,()->0L).snapshot();
        assertEquals(102000,snapshot.totalKiB);
        assertEquals(122000,snapshot.oww242BudgetKiB(1));
        assertEquals(126000,snapshot.oww242BudgetKiB(2));
    }
    @Test public void invalidMeasurementsCannotWrapTheProcessBudget(){
        for(long[] value:new long[][]{{0,0},{100,-1},{100,101}}){
            try{new PodProcessMemoryReader.Reading(value[0],value[1]);fail("invalid memory accepted");}catch(IllegalArgumentException expected){}
        }
        try{new PodProcessMemoryReader.Snapshot(new PodProcessMemoryReader.Reading(Long.MAX_VALUE,0),new PodProcessMemoryReader.Reading(1,1),0);fail("overflow accepted");}catch(ArithmeticException expected){}
    }
}
