package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.net.Uri;
import androidx.media3.datasource.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodDouyinRequestTest {
 static final Map<String,String> HEADERS=new HashMap<>();static{HEADERS.put("Referer","https://www.douyin.com/");HEADERS.put("Cookie","PRIVATE_COOKIE");}
 static class Source implements DataSource {
  IOException openFailure,readFailure;int closed;DataSpec last;
  public long open(DataSpec spec)throws IOException{last=spec;if(openFailure!=null)throw openFailure;return 3;}
  public int read(byte[] b,int o,int n)throws IOException{if(readFailure!=null)throw readFailure;return -1;}
  public void close(){closed++;}public Uri getUri(){return last==null?null:last.uri;}
  public void addTransferListener(TransferListener l){}public Map<String,List<String>> getResponseHeaders(){return Collections.singletonMap("Retry-After",Arrays.asList("13"));}
 }
 DataSpec spec(){return new DataSpec(Uri.parse("https://fixture.invalid/PRIVATE_ID?token=PRIVATE_TOKEN"));}
 String line(){assertEquals(1,ShadowLog.getLogsForTag("DYBENCH").size());String line=ShadowLog.getLogsForTag("DYBENCH").get(0).msg;assertFalse(line.contains("PRIVATE"));return line;}
 @Test public void successAndRepeatedCloseEmitOnceWithoutChangingRequest()throws Exception{
  ShadowLog.clear();Source source=new Source();DataSource wrapper=new PodDouyinRequest(source,HEADERS);DataSpec spec=spec();assertEquals(3,wrapper.open(spec));assertSame(spec,source.last);wrapper.read(new byte[1],0,1);wrapper.close();wrapper.close();assertTrue(line().contains("error_code=success"));assertTrue(line().contains("retry_after_ms=13000"));assertTrue(line().contains("cookie_present=true"));
 }
 @Test public void forbiddenOpenKeepsNumericStatusAndRetryAfter()throws Exception{
  ShadowLog.clear();Source source=new Source();source.openFailure=new HttpDataSource.InvalidResponseCodeException(403,"PRIVATE",null,Collections.singletonMap("Retry-After",Arrays.asList("17")),spec(),new byte[0]);DataSource wrapper=new PodDouyinRequest(source,HEADERS);try{wrapper.open(spec());fail();}catch(IOException expected){assertSame(source.openFailure,expected);}wrapper.close();assertTrue(line().contains("http_status=403"));assertTrue(line().contains("retry_after_ms=17000"));assertTrue(line().contains("error_code=forbidden"));
 }
 @Test public void readTimeoutIsNotSuccess()throws Exception{
  ShadowLog.clear();Source source=new Source();source.readFailure=new java.net.SocketTimeoutException("PRIVATE");DataSource wrapper=new PodDouyinRequest(source,HEADERS);wrapper.open(spec());try{wrapper.read(new byte[1],0,1);fail();}catch(IOException expected){}wrapper.close();assertTrue(line().contains("error_code=timeout"));
 }
 @Test public void otherPlatformProducesNoDouyinDiagnostic()throws Exception{
  ShadowLog.clear();DataSource wrapper=new PodDouyinRequest(new Source(),Collections.singletonMap("Referer","https://www.bilibili.com/"));wrapper.open(spec());wrapper.close();assertEquals(0,ShadowLog.getLogsForTag("DYBENCH").size());
 }
}
