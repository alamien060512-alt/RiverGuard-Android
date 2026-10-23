package com.riverguard.v2;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static com.riverguard.v2.PhoneValidation.*;

/** Deliberately small HTTP/1.1 subset for the existing ESP32 HTTPClient contract. */
public final class PhoneReceiver {
    public interface Backend {
        String authenticate(String authorization);
        JSONObject configuration(String device);
        JSONObject ingest(String authenticatedDevice,JSONObject packet);
    }
    private final Backend backend;
    private final int requestedPort;
    private volatile ServerSocket listener;
    private volatile ThreadPoolExecutor workers;
    private final Set<Socket> clients=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    private volatile String error;
    private long rateWindow;private int globalRequests;
    private final Map<String,int[]> requestRates=new java.util.LinkedHashMap<String,int[]>(128,.75f,true){private static final long serialVersionUID=1L;@Override protected boolean removeEldestEntry(Map.Entry<String,int[]> e){return size()>128;}};
    private synchronized void limit(Socket client){long window=System.nanoTime()/1000000000L;if(window!=rateWindow){rateWindow=window;globalRequests=0;requestRates.clear();}String key=client.getInetAddress().getHostAddress();int[] requests=requestRates.get(key);if(requests==null){requests=new int[]{0};requestRates.put(key,requests);}if(++globalRequests>64||++requests[0]>12)throw new Problem(429,"Receiver is busy. Retry the unchanged sample after one second.");}
    public PhoneReceiver(Backend backend,int port) { this.backend=backend;this.requestedPort=port; }

    public synchronized void start() throws IOException {
        if(isRunning())return;
        ServerSocket socket=new ServerSocket();socket.setReuseAddress(true);
        try {socket.bind(new InetSocketAddress(requestedPort),8);}
        catch(IOException failure){close(socket);error="Cannot open port8000. Another app may be using it. Disable and retry the receiver.";throw failure;}
        ThreadPoolExecutor pool=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"riverguard-sensor-request");t.setDaemon(true);return t;});
        listener=socket;workers=pool;error=null;
        Thread acceptor=new Thread(()->accept(socket,pool),"riverguard-sensor-listener");acceptor.setDaemon(true);acceptor.start();
    }
    public synchronized void stop() {
        ServerSocket socket=listener;listener=null;close(socket);
        for(Socket client:clients)close(client);clients.clear();
        ThreadPoolExecutor pool=workers;workers=null;if(pool!=null)pool.shutdownNow();
    }
    public boolean isRunning() {ServerSocket socket=listener;return socket!=null&&!socket.isClosed();}
    public int port() {ServerSocket socket=listener;return socket==null?requestedPort:socket.getLocalPort();}
    public JSONObject info(boolean enabled) {
        org.json.JSONArray addresses=new org.json.JSONArray();
        try {
            for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!network.isUp()||network.isLoopback())continue;
                for(InetAddress address:Collections.list(network.getInetAddresses()))if(address instanceof Inet4Address&&address.isSiteLocalAddress())addresses.put(address.getHostAddress());
            }
        }catch(Exception ignored){/* No network credentials or personal data are logged. */}
        return json("running",isRunning(),"enabled",enabled,"port",port(),"addresses",addresses,"error",error);
    }
    private void accept(ServerSocket socket,ThreadPoolExecutor pool) {
        while(listener==socket&&!socket.isClosed()) {
            Socket client=null;
            try {
                client=socket.accept();client.setSoTimeout(3000);clients.add(client);final Socket accepted=client;
                if(listener!=socket){clients.remove(client);close(client);break;}
                pool.execute(()->serve(accepted,socket));
            }catch(RejectedExecutionException busy){if(client!=null){clients.remove(client);close(client);}}
            catch(IOException stopped){if(client!=null){clients.remove(client);close(client);}if(listener==socket){error="Receiver connection interrupted. Disable and enable it to retry.";stop();}break;}
        }
    }
    private void serve(Socket client,ServerSocket generation) {
        try {
            limit(client);
            InputStream input=client.getInputStream();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            int[] headerBytes={0};String first=line(input,deadline,headerBytes,2048);
            String[] request=first.split(" ",-1);
            if(request.length!=3||!(request[2].equals("HTTP/1.1")||request[2].equals("HTTP/1.0"))||!request[1].startsWith("/")||request[1].startsWith("//"))throw new Problem(400,"Invalid HTTP request.");
            Map<String,String> headers=new HashMap<>();
            for(;;){String line=line(input,deadline,headerBytes,2048);if(line.isEmpty())break;int colon=line.indexOf(':');if(colon<1||!line.substring(0,colon).matches("[A-Za-z0-9-]+"))throw new Problem(400,"Invalid request headers.");String key=line.substring(0,colon).toLowerCase(java.util.Locale.ROOT);String value=line.substring(colon+1).trim();if(headers.put(key,value)!=null)throw new Problem(400,"Duplicate request header.");}
            if(headers.containsKey("transfer-encoding"))throw new Problem(400,"Use Content-Length for one JSON sample.");
            String method=request[0],target=request[1],path=target.split("\\?",2)[0];
            JSONObject response;
            if(method.equals("GET")&&target.equals("/health")) response=json("status","ok","service","riverguard-phone-receiver");
            else if(method.equals("GET")&&path.equals("/api/v1/device-config")) {
                String authenticated=backend.authenticate(headers.get("authorization"));String device=queryDevice(target);
                if(!authenticated.equals(device))throw new Problem(403,"A station can retrieve only its own configuration.");
                if(listener!=generation)throw new Problem(503,"Receiver is paused.");response=backend.configuration(device);
            } else if(method.equals("POST")&&target.equals("/api/v1/readings")) {
                String authenticated=backend.authenticate(headers.get("authorization"));String lengthText=headers.get("content-length");
                if(lengthText==null)throw new Problem(411,"Include Content-Length.");
                if(!lengthText.matches("[0-9]{1,9}"))throw new Problem(400,"Invalid content length.");int length=Integer.parseInt(lengthText);
                if(length>BODY_LIMIT)throw new Problem(413,"Send one sensor sample no larger than32KiB.");
                String type=headers.get("content-type");if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new Problem(415,"Send application/json.");
                byte[] body=new byte[length];int offset=0;
                while(offset<length){checkDeadline(deadline);int n=input.read(body,offset,length-offset);if(n<0)throw new Problem(400,"Incomplete sensor sample.");offset+=n;}
                String text;
                try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();}
                catch(CharacterCodingException invalid){throw new Problem(400,"Send UTF-8 JSON.");}
                if(listener!=generation||Thread.currentThread().isInterrupted())throw new Problem(503,"Receiver is paused.");
                response=backend.ingest(authenticated,object(text));respond(client,201,response);return;
            } else throw new Problem(404,"Unknown receiver endpoint.");
            respond(client,200,response);
        } catch(Problem problem){try{respond(client,problem.status,json("detail",problem.getMessage()));}catch(IOException ignored){}}
        catch(SocketTimeoutException timeout){try{respond(client,408,json("detail","Sensor request timed out. Retry the unchanged sample."));}catch(IOException ignored){}}
        catch(Exception ignored){try{respond(client,500,json("detail","The phone could not store this sample. Retry it unchanged."));}catch(IOException disconnected){}}
        finally{clients.remove(client);close(client);}
    }
    private static String queryDevice(String target) {
        int mark=target.indexOf('?');if(mark<0)throw new Problem(422,"Specify device_id.");String device=null;
        for(String part:target.substring(mark+1).split("&",-1)) {
            String[] pair=part.split("=",2);if(pair.length!=2||!pair[0].equals("device_id")||device!=null)throw new Problem(422,"Specify one device_id.");
            try{device=URLDecoder.decode(pair[1],"UTF-8");}catch(Exception invalid){throw new Problem(422,"Invalid station ID.");}
        }return station(device);
    }
    private static void checkDeadline(long deadline) throws SocketTimeoutException {if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new SocketTimeoutException();}
    private static String line(InputStream input,long deadline,int[] count,int limit)throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();int previous=-1;
        while(true){checkDeadline(deadline);int next=input.read();if(next<0)throw new Problem(400,"Incomplete HTTP request.");if(++count[0]>8192||result.size()>limit)throw new Problem(431,"Request headers are too large.");if(previous=='\r'&&next=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}if(next<32&&next!='\r'&&next!='\n'&&next!='\t')throw new Problem(400,"Invalid request header.");if(next>127)throw new Problem(400,"Use ASCII request headers.");result.write(next);previous=next;}
    }
    private static void respond(Socket client,int status,JSONObject payload)throws IOException {
        byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);String header="HTTP/1.1 "+status+" "+(status<400?"OK":"Error")+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n"+(status==429?"Retry-After: 1\r\n":"")+"X-Content-Type-Options: nosniff\r\n\r\n";
        OutputStream output=client.getOutputStream();output.write(header.getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
    }
    private static void close(java.io.Closeable value){if(value!=null)try{value.close();}catch(IOException ignored){}}
}
