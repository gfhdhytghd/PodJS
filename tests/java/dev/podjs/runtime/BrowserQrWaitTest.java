package dev.podjs.runtime;
import org.json.JSONObject;
import java.util.ArrayList;
public final class BrowserQrWaitTest {
 public static void main(String[] args) throws Exception {
  ArrayList<JSONObject> events=new ArrayList<>();
  PodBrowserAuthStatus.Stream legacy=new PodBrowserAuthStatus.Stream(events::add);
  assert !legacy.qrWaitStatus().getBoolean("visible");
  PodBrowserAuthStatus.Stream stream=new PodBrowserAuthStatus.Stream(events::add,new JSONObject().put("message","Waiting"));
  assert stream.qrWaitStatus().getBoolean("visible");
  stream.emit(PodBrowserAuthStatus.event("pageState").put("loading",false));assert stream.qrWaitStatus().getBoolean("visible");
  stream.accept(PodBrowserAuthStatus.parse("{\"loading\":false,\"qr\":{\"kind\":\"login\",\"mime\":\"image/png\",\"imageBase64\":\"AAAA\"}}"));assert !stream.qrWaitStatus().getBoolean("visible");
  stream.accept(PodBrowserAuthStatus.parse("null"));assert stream.qrWaitStatus().getBoolean("visible");
  stream.accept(PodBrowserAuthStatus.parse("{\"verifying\":true}"));assert !stream.qrWaitStatus().getBoolean("visible");
  stream.emit(PodBrowserAuthStatus.event("qrCleared"));assert !stream.qrWaitStatus().getBoolean("visible");
  stream.accept(PodBrowserAuthStatus.parse("null"));assert stream.qrWaitStatus().getBoolean("visible");
  assert stream.qrWaitStatus().getString("message").equals("Waiting");
  stream.end(false);assert !stream.qrWaitStatus().getBoolean("visible");int count=events.size();
  stream.emit(PodBrowserAuthStatus.event("qrCleared"));assert events.size()==count;
  PodBrowserAuthStatus.Stream cancelled=new PodBrowserAuthStatus.Stream(events::add,new JSONObject().put("message","Wait"));
  cancelled.end(true);assert !cancelled.qrWaitStatus().getBoolean("visible");assert events.get(events.size()-1).getString("type").equals("cancelled");
  int[] changes={0};PodBrowserAuthStatus.Stream observed=new PodBrowserAuthStatus.Stream(events::add,new JSONObject().put("message","W"));observed.changed=()->changes[0]++;
  observed.emit(PodBrowserAuthStatus.event("pageState").put("loading",true));observed.emit(PodBrowserAuthStatus.event("pageState").put("loading",true));assert changes[0]==2;
  observed.end(false);assert changes[0]==3;observed.end(false);assert changes[0]==3;
  System.out.println("Browser QR waiting state passed (legacy, loading=false, QR clear, verification, completion, cancellation)");
 }
}
