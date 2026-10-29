package net.xhfo.tvwled;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small strict HTTP/1 parser shared by the Android server and host-side protocol tests. */
final class HttpProtocol {
    static final int MAX_BODY=131072,MAX_HEADERS=16384;
    static final class Failure extends IOException {
        final int status;
        Failure(int status,String message){super(message);this.status=status;}
    }
    static final class Request {
        final String method,path;
        final Map<String,String> headers;
        final byte[] body;
        Request(String method,String path,Map<String,String> headers,byte[] body){this.method=method;this.path=path;this.headers=headers;this.body=body;}
    }
    private static final class Lines {
        final InputStream in;int total;
        Lines(InputStream in){this.in=in;}
        String next() throws IOException {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            while(true){
                int c=in.read();if(c<0)throw new Failure(400,"Incomplete request");
                if(++total>MAX_HEADERS||out.size()>4096)throw new Failure(431,"Request headers too large");
                if(c=='\r'){if(in.read()!='\n')throw new Failure(400,"CRLF required");total++;return new String(out.toByteArray(),StandardCharsets.US_ASCII);}
                if((c<32&&c!='\t')||c>126)throw new Failure(400,"Invalid header character");
                out.write(c);
            }
        }
    }
    static Request read(InputStream in) throws IOException {
        Lines lines=new Lines(in);String first=lines.next();String[] parts=first.split(" ",-1);
        if(parts.length!=3||!(parts[2].equals("HTTP/1.1")||parts[2].equals("HTTP/1.0")))throw new Failure(400,"Invalid request line");
        String method=parts[0],target=parts[1];
        if(!method.equals("GET")&&!method.equals("POST"))throw new Failure(405,"Only GET and POST supported");
        if(target.length()>2048||!target.startsWith("/")||target.startsWith("//")||target.indexOf('#')>=0)throw new Failure(400,"Invalid request target");
        String path=target.split("\\?",2)[0];
        // No path decoding or arbitrary filesystem paths are needed for our fixed asset list.
        if(path.contains("%")||path.contains("\\")||path.contains(".."))throw new Failure(404,"Unknown path");
        Map<String,String> headers=new LinkedHashMap<>();
        for(String line;(line=lines.next()).length()!=0;){
            int colon=line.indexOf(':');if(colon<1)throw new Failure(400,"Invalid header");
            String name=line.substring(0,colon).toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z0-9-]+")||headers.containsKey(name))throw new Failure(400,"Invalid or duplicate header");
            headers.put(name,line.substring(colon+1).trim());
        }
        if(headers.containsKey("transfer-encoding")||headers.containsKey("expect"))throw new Failure(400,"Unsupported request framing");
        String length=headers.get("content-length");long size=0;
        if(length!=null){if(!length.matches("[0-9]{1,9}"))throw new Failure(400,"Invalid content length");size=Long.parseLong(length);}
        if(size>MAX_BODY)throw new Failure(413,"Request body too large");
        if(method.equals("POST")&&length==null)throw new Failure(411,"Content-Length required");
        if(method.equals("GET")&&size!=0)throw new Failure(400,"GET body not supported");
        if(method.equals("POST")){
            String type=headers.get("content-type");
            if(type==null||!type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))throw new Failure(415,"JSON body required");
        }
        byte[] body=new byte[(int)size];int offset=0;
        while(offset<body.length){int n=in.read(body,offset,body.length-offset);if(n<0)throw new Failure(400,"Incomplete request body");offset+=n;}
        return new Request(method,path,headers,body);
    }
    static void authorize(Request request,String token,String currentHost,int port) throws Failure {
        String host=request.headers.get("host");
        boolean trusted=(currentHost!=null&&host!=null&&host.equals(currentHost+":"+port))||("127.0.0.1:"+port).equals(host)||("localhost:"+port).equals(host);
        if(!trusted)throw new Failure(403,"Unexpected Host");
        String origin=request.headers.get("origin");
        if(origin!=null&&!origin.equals("http://"+host))throw new Failure(403,"Unexpected Origin");
        if(request.path.startsWith("/api/")){
            String supplied=request.headers.get("x-tvwled-token");
            if(supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new Failure(401,"Pair this browser using the QR code on the TV");
        }
    }
    static String reason(int status){
        switch(status){case 200:return "OK";case 400:return "Bad Request";case 401:return "Unauthorized";case 403:return "Forbidden";case 404:return "Not Found";case 405:return "Method Not Allowed";case 408:return "Request Timeout";case 409:return "Conflict";case 411:return "Length Required";case 413:return "Payload Too Large";case 415:return "Unsupported Media Type";case 431:return "Request Header Fields Too Large";case 503:return "Service Unavailable";default:return "Internal Server Error";}
    }
}
