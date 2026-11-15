package dev.podjs.runtime;

import okhttp3.*;
import java.io.IOException;

/** Enforced on every network exchange, including redirect follow-ups. */
final class PodMediaRequestPolicy implements Interceptor {
    static void check(HttpUrl url,Headers headers)throws IOException {
        boolean credentials=false;
        for(String name:headers.names())if(name.equalsIgnoreCase("Cookie")||name.equalsIgnoreCase("Authorization")||name.equalsIgnoreCase("Proxy-Authorization"))credentials=true;
        if(credentials&&!(url.isHttps()&&url.port()==443&&url.username().isEmpty()&&url.password().isEmpty()&&
            (url.host().equals("www.douyin.com")||url.host().equals("douyin.com"))))throw new IOException("media_credentials_origin_rejected");
    }
    static final class Binding {
        final String origin;
        Binding(String origin){this.origin=origin;}
        void check(HttpUrl url,Headers headers)throws IOException {
            if(origin==null){PodMediaRequestPolicy.check(url,headers);return;}
            for(String name:headers.names())if(name.equalsIgnoreCase("Cookie")||name.equalsIgnoreCase("Authorization")||name.equalsIgnoreCase("Proxy-Authorization")){
                if(!url.isHttps()||!url.username().isEmpty()||!url.password().isEmpty()||!origin.equals(origin(url)))throw new IOException("media_credentials_origin_rejected");
            }
        }
    }
    private static String origin(HttpUrl url){return url.scheme()+"://"+url.host()+":"+url.port();}
    static Binding bind(HttpUrl url,Headers headers,boolean direct){
        String referer=headers.get("Referer");HttpUrl ref=referer==null?null:HttpUrl.parse(referer);
        boolean douyin=url.host().equals("douyin.com")||url.host().endsWith(".douyin.com")||ref!=null&&(ref.host().equals("douyin.com")||ref.host().endsWith(".douyin.com"));
        // Other platforms may authorize their initial HTTPS origin, never a redirect destination.
        return new Binding(direct&&!douyin?origin(url):null);
    }
    static void check(Request request)throws IOException {
        Binding binding=request.tag(Binding.class);
        if(binding==null)check(request.url(),request.headers());else binding.check(request.url(),request.headers());
    }
    public Response intercept(Chain chain)throws IOException {
        check(chain.request());
        return chain.proceed(chain.request());
    }
}
