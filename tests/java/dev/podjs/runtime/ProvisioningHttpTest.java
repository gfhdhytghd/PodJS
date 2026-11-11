package dev.podjs.runtime;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs the production URL exchange against in-memory connections. No network access. */
public final class ProvisioningHttpTest {
    static final List<Connection> connections=Collections.synchronizedList(new ArrayList<>());
    static final class Connection extends HttpURLConnection {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        Connection(URL url){super(url);connections.add(this);}
        public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
        public OutputStream getOutputStream(){return output;}
        public int getResponseCode(){return 200;}
        public InputStream getInputStream(){
            String body="{}";
            if(getURL().getPath().equals(OnlineProvisioningSession.SESSIONS)){
                org.json.JSONObject request=new org.json.JSONObject(output.toString(StandardCharsets.UTF_8));
                body=new org.json.JSONObject().put("sessionId",request.getString("sessionId")).put("watchToken","a".repeat(64)).put("claimToken","b".repeat(64)).put("expiresAt",100).toString();
            }
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
    }
    static void install(){
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL url){return new Connection(url);}
        }:null);
    }
    public static void main(String[] args)throws Exception {
        install();
        OnlineProvisioningSession.Http http=(OnlineProvisioningSession.Http)OnlineProvisioningSession.UrlHttp.class.getDeclaredConstructor(String.class).newInstance("watch-fixture+28");
        String base=OnlineProvisioningSession.SESSIONS;
        for(String route:new String[]{"","/id/request","/id/response","/id/ownership/challenge","/id/ownership/release","/id/close"}){
            String method=route.endsWith("/request")?"GET":"POST";
            http.exchange(method,base+route,route.isEmpty()?null:"fixture-token",method.equals("GET")?null:"{\"sessionId\":\"id\"}".getBytes(StandardCharsets.UTF_8));
            Connection c=connections.get(connections.size()-1);
            ProvisioningTest.check("watch-fixture+28".equals(c.getRequestProperty("X-WatchRSS-App-Version")));
            ProvisioningTest.check("application/json".equals(c.getRequestProperty("Accept")));
            ProvisioningTest.check(!c.getInstanceFollowRedirects());
            ProvisioningTest.check(c.getURL().getHost().equals("sly-data-plane.watchrss.cn"));
        }
        http.abort();
        http.exchange("POST",base+"/id/close","fixture-token","{}".getBytes(StandardCharsets.UTF_8));
        ProvisioningTest.check("watch-fixture+28".equals(connections.get(connections.size()-1).getRequestProperty("X-WatchRSS-App-Version")));
        System.out.println("PASS: production HTTP app version on all 6 pairing routes and close after abort; no network access");
    }
}
