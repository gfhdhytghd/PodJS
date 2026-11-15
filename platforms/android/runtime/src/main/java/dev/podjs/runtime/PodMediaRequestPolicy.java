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
    public Response intercept(Chain chain)throws IOException {
        check(chain.request().url(),chain.request().headers());
        return chain.proceed(chain.request());
    }
}
