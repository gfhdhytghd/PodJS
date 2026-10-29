package dev.podjs.runtime;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.junit.*;import static org.junit.Assert.*;
public class PodAppendLogTest {
 private File root;
 @Before public void setup()throws Exception{root=Files.createTempDirectory("pod-log-test-").toFile();}
 @After public void cleanup()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(p);}}
 @Test public void appendKeepsUtf8AndEveryConcurrentLineExactlyOnce()throws Exception{File f=new File(root,"app_log.txt");List<Thread> threads=new ArrayList<>();for(int i=0;i<40;i++){final int n=i;threads.add(new Thread(()->{try{PodAppendLog.append(f,"诊断-"+n+"\n",8192);}catch(Exception error){throw new RuntimeException(error);}}));}for(Thread t:threads)t.start();for(Thread t:threads)t.join();List<String> lines=Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);assertEquals(40,lines.size());assertEquals(40,new HashSet<>(lines).size());}
 @Test public void rotationBoundsFileAndKeepsTheNewestLine()throws Exception{File f=new File(root,"app_log.txt");for(int i=0;i<80;i++)PodAppendLog.append(f,"row"+i+" "+new String(new char[400]).replace('\0','x')+"\n",8192);assertTrue(f.length()<=8192);assertTrue(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8).contains("row79 "));}
 @Test public void oversizeIsRejectedBeforeWriting()throws Exception{File f=new File(root,"app_log.txt");assertThrows(IllegalArgumentException.class,()->PodAppendLog.append(f,new String(new char[8193]).replace('\0','x'),8192));assertFalse(f.exists());}
}
