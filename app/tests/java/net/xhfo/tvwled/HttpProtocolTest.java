package net.xhfo.tvwled;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
public final class HttpProtocolTest {
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    private static int passed;
    private static HttpProtocol.Request parse(String raw)throws Exception{return HttpProtocol.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);passed++;}
    private static void fail(int status,String raw)throws Exception{try{parse(raw);throw new AssertionError("accepted malformed request");}catch(HttpProtocol.Failure result){check(result.status==status,"status "+status+" got "+result.status);}}
    private static void authFail(int status,String raw)throws Exception{try{HttpProtocol.authorize(parse(raw),TOKEN,"192.168.1.80",8768);throw new AssertionError("authorized invalid request");}catch(HttpProtocol.Failure result){check(result.status==status,"auth status");}}
    public static void main(String[]args)throws Exception{
        String host="Host: 192.168.1.80:8768\r\n",auth="X-TVWLED-Token: "+TOKEN+"\r\n";
        HttpProtocol.authorize(parse("GET /api/status HTTP/1.1\r\n"+host+auth+"\r\n"),TOKEN,"192.168.1.80",8768);passed++;
        HttpProtocol.authorize(parse("POST /api/start HTTP/1.1\r\n"+host+auth+"Origin: http://192.168.1.80:8768\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"),TOKEN,"192.168.1.80",8768);passed++;
        authFail(401,"GET /api/status HTTP/1.1\r\n"+host+"\r\n");
        authFail(401,"GET /api/status HTTP/1.1\r\n"+host+"X-TVWLED-Token: wrong\r\n\r\n");
        authFail(403,"GET /api/status HTTP/1.1\r\n"+host+auth+"Origin: https://foreign.example\r\n\r\n");
        authFail(403,"GET /api/status HTTP/1.1\r\nHost: foreign.example\r\n"+auth+"\r\n");
        authFail(403,"GET /api/status HTTP/1.1\r\n"+host+auth+"Origin: null\r\n\r\n");
        fail(404,"GET /../private HTTP/1.1\r\n"+host+"\r\n");
        fail(404,"GET /%2e%2e/private HTTP/1.1\r\n"+host+"\r\n");
        fail(413,"POST /api/config HTTP/1.1\r\n"+host+"Content-Type: application/json\r\nContent-Length: 131073\r\n\r\n");
        fail(400,"POST /api/config HTTP/1.1\r\n"+host+"Content-Length: 2\r\nContent-Length: 2\r\nContent-Type: application/json\r\n\r\n{}");
        fail(400,"POST /api/config HTTP/1.1\r\n"+host+"Content-Length: 2\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n{}");
        fail(415,"POST /api/start HTTP/1.1\r\n"+host+"Content-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}");
        fail(411,"POST /api/start HTTP/1.1\r\n"+host+"Content-Type: application/json\r\n\r\n");
        fail(405,"DELETE /api/config HTTP/1.1\r\n"+host+"\r\n");
        fail(400,"GET http://foreign.example/api/status HTTP/1.1\r\n"+host+"\r\n");
        // Actual loopback socket confirms a partial request obeys the socket read timeout.
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Thread test=new Thread(()->{try(Socket server=listener.accept()){server.setSoTimeout(150);try{HttpProtocol.read(server.getInputStream());throw new AssertionError("read should time out");}catch(SocketTimeoutException expected){passed++;}}catch(Exception bad){throw new RuntimeException(bad);}});test.start();
            try(Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){client.getOutputStream().write("GET /api/status HTTP/1.1\r\nHost:".getBytes(StandardCharsets.US_ASCII));test.join(1500);check(!test.isAlive(),"read timeout");}
        }
        check(parse("GET /?token="+TOKEN+" HTTP/1.1\r\n"+host+"\r\n").path.equals("/"),"QR query path");
        System.out.println("HTTP protocol checks passed: "+passed);
    }
}
