package com.riverguard.v2;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PhoneReceiverTest {
    private PhoneReceiver receiver;
    private final AtomicInteger writes=new AtomicInteger();
    @Before public void start()throws Exception {
        receiver=new PhoneReceiver(new PhoneReceiver.Backend(){
            public String authenticate(String header){if(!"Bearer allowed".equals(header))throw new PhoneValidation.Problem(401,"Unauthorized");return "one";}
            public JSONObject configuration(String device){return PhoneValidation.json("device_id",device,"revision",0,"protocol","remote-v1");}
            public JSONObject ingest(String device,JSONObject packet){if(!device.equals(packet.optString("device_id")))throw new PhoneValidation.Problem(403,"Wrong station");writes.incrementAndGet();return PhoneValidation.json("stored",true);}
        },0);receiver.start();
    }
    @After public void stop(){receiver.stop();}
    private String request(String raw)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",receiver.port())) {
            socket.setSoTimeout(4000);socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;InputStream input=socket.getInputStream();while((n=input.read(buffer))!=-1)result.write(buffer,0,n);return new String(result.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private String post(String headers,String body){return "POST /api/v1/readings HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"+headers+"Content-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+body;}
    @Test public void healthIsPublicButWritesRequireCredential()throws Exception {assertTrue(request("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 200"));assertTrue(request(post("","{\"device_id\":\"one\"}")).startsWith("HTTP/1.1 401"));assertEquals(0,writes.get());}
    @Test public void stationMayOnlyReadOwnConfiguration()throws Exception {assertTrue(request("GET /api/v1/device-config?device_id=two HTTP/1.1\r\nAuthorization: Bearer allowed\r\n\r\n").startsWith("HTTP/1.1 403"));assertTrue(request("GET /api/v1/device-config?device_id=one HTTP/1.1\r\nAuthorization: Bearer allowed\r\n\r\n").contains("remote-v1"));}
    @Test public void boundedBodyAndMalformedJsonNeverWrite()throws Exception {assertTrue(request("POST /api/v1/readings HTTP/1.1\r\nAuthorization: Bearer allowed\r\nContent-Type: application/json\r\nContent-Length: 32769\r\n\r\n").startsWith("HTTP/1.1 413"));assertTrue(request(post("Authorization: Bearer allowed\r\n","{broken")).startsWith("HTTP/1.1 422"));assertEquals(0,writes.get());}
    @Test public void transferEncodingAndDuplicateHeadersAreRejected()throws Exception {assertTrue(request(post("Authorization: Bearer allowed\r\nTransfer-Encoding: chunked\r\n","{}")).startsWith("HTTP/1.1 400"));assertTrue(request(post("Authorization: Bearer allowed\r\nAuthorization: Bearer allowed\r\n","{}")).startsWith("HTTP/1.1 400"));assertEquals(0,writes.get());}
    @Test public void validWriteAndCrossStationScope()throws Exception {assertTrue(request(post("Authorization: Bearer allowed\r\n","{\"device_id\":\"one\"}")).startsWith("HTTP/1.1 201"));assertTrue(request(post("Authorization: Bearer allowed\r\n","{\"device_id\":\"two\"}")).startsWith("HTTP/1.1 403"));assertEquals(1,writes.get());}
    @Test public void pauseClosesListenerAndActiveClients()throws Exception {Socket stalled=new Socket("127.0.0.1",receiver.port());stalled.setSoTimeout(1000);stalled.getOutputStream().write('G');Thread.sleep(50);receiver.stop();assertFalse(receiver.isRunning());assertEquals(-1,stalled.getInputStream().read());stalled.close();}
    @Test public void burstIsRateLimitedWithRetryWithoutWriting()throws Exception {boolean limited=false;for(int i=0;i<48;i++){String response=request("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n");if(response.startsWith("HTTP/1.1 429")){assertTrue(response.contains("Retry-After: 1"));limited=true;break;}}assertTrue(limited);assertEquals(0,writes.get());}
}
