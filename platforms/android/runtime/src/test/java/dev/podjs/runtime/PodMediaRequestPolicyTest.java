package dev.podjs.runtime;

import okhttp3.*;
import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class PodMediaRequestPolicyTest {
 @Test public void credentialsRequireExactHttpsDouyinOriginOnEveryExchange()throws Exception {
  List<Request> captured=new ArrayList<>();PodMediaRequestPolicy policy=new PodMediaRequestPolicy();
  for(String url:List.of("https://www.douyin.com/main","https://douyin.com/audio","https://www.douyin.com/mirror"))exchange(policy,url,true,captured);
  assertEquals(3,captured.size());
  for(String url:List.of("http://www.douyin.com/plain","https://www.douyin.com.evil.invalid/x","https://fixture.invalid/redirect","https://www.douyin.com:8443/x","https://user@www.douyin.com/x")){
   try{exchange(policy,url,true,captured);fail(url);}catch(IOException expected){assertEquals("media_credentials_origin_rejected",expected.getMessage());}
  }
  assertEquals(3,captured.size());
  // Simulated redirect follow-up retains a Cookie header; no request reaches transport.
  try{exchange(policy,"http://douyin.com/redirect",true,captured);fail();}catch(IOException expected){}assertEquals(3,captured.size());
 }
 @Test public void publicMainAudioAndMirrorNeedNoCredential()throws Exception {
  List<Request> captured=new ArrayList<>();for(String kind:List.of("main","audio","mirror"))exchange(new PodMediaRequestPolicy(),"https://fixture.invalid/"+kind,false,captured);
  assertEquals(3,captured.size());for(Request r:captured)assertNull(r.header("Cookie"));
 }
 @Test public void directOtherPlatformCredentialsStayOnTheirInitialHttpsOrigin()throws Exception {
  HttpUrl initial=HttpUrl.get("https://api.bilibili.com/media");Headers headers=Headers.of("Cookie","offline-fixture","Referer","https://www.bilibili.com/");
  PodMediaRequestPolicy.Binding binding=PodMediaRequestPolicy.bind(initial,headers,true);
  binding.check(initial,headers);binding.check(HttpUrl.get("https://api.bilibili.com/audio"),headers);
  for(String target:List.of("http://api.bilibili.com/media","https://other.invalid/media","https://api.bilibili.com:8443/media"))try{binding.check(HttpUrl.get(target),headers);fail(target);}catch(IOException expected){}
  PodMediaRequestPolicy.Binding douyin=PodMediaRequestPolicy.bind(HttpUrl.get("https://fixture.invalid/media"),Headers.of("Cookie","fixture","Referer","https://www.douyin.com/"),true);
  try{douyin.check(HttpUrl.get("https://fixture.invalid/media"),headers);fail();}catch(IOException expected){}
 }
 static void exchange(PodMediaRequestPolicy policy,String url,boolean cookie,List<Request> captured)throws Exception {
  Request.Builder builder=new Request.Builder().url(url);if(cookie)builder.header("cOoKiE","offline-fixture");Request request=builder.build();
  Interceptor.Chain chain=(Interceptor.Chain)Proxy.newProxyInstance(Interceptor.Chain.class.getClassLoader(),new Class[]{Interceptor.Chain.class},(proxy,method,args)->{
   if(method.getName().equals("request"))return request;
   if(method.getName().equals("proceed")){captured.add((Request)args[0]);return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(ResponseBody.create(new byte[0],null)).build();}
   throw new UnsupportedOperationException(method.getName());
  });policy.intercept(chain).close();
 }
}
