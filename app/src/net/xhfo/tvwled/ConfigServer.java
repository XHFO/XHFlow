package net.xhfo.tvwled;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** LAN-only settings server. Never maps request paths onto files. */
final class ConfigServer implements AutoCloseable {
    static final int PORT=8768;
    private static final String PREFS="tvwled",TAG="TVWLED_HTTP";
    private static volatile boolean live;
    private static volatile String connection="尚未检测WLED";
    private final Context context;
    private final String token;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->new Thread(r,"TVWLED-http-worker"),new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,String> assets=new HashMap<>();
    private volatile ServerSocket server;
    private final Set<Socket> clients=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    ConfigServer(Context context){
        this.context=context.getApplicationContext();token=pairingToken(context);
        assets.put("/","index.html");assets.put("/index.html","index.html");assets.put("/styles.css","styles.css");assets.put("/model.js","model.js");assets.put("/app.js","app.js");assets.put("/console.js","console.js");assets.put("/mark.svg","mark.svg");
    }
    static boolean isLive(){return live;}
    static String localAddress(){
        try{
            String fallback=null;
            for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())){
                if(!network.isUp()||network.isLoopback())continue;
                for(InetAddress value:Collections.list(network.getInetAddresses())){
                    if(value instanceof Inet4Address&&value.isSiteLocalAddress()){
                        String name=network.getName();if(name.startsWith("wlan")||name.startsWith("eth"))return value.getHostAddress();
                        if(fallback==null)fallback=value.getHostAddress();
                    }
                }
            }
            return fallback;
        }catch(Exception ignored){return null;}
    }
    static synchronized String pairingToken(Context context){
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);String value=prefs.getString("serverToken","");
        if(!value.matches("[a-f0-9]{32}")){
            byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));value=text.toString();
            if(!prefs.edit().putString("serverToken",value).commit())throw new IllegalStateException("访问凭据未能保存");
        }
        return value;
    }
    static String pairingUrl(Context context){String address=localAddress();return address==null?null:"http://"+address+":"+PORT+"/?token="+pairingToken(context);}
    synchronized void start() throws IOException {
        if(server!=null)return;
        ServerSocket next=new ServerSocket();next.setReuseAddress(true);next.bind(new InetSocketAddress("0.0.0.0",PORT),8);server=next;live=true;
        new Thread(this::accept,"TVWLED-http").start();Log.i(TAG,"LAN configuration server ready on port "+PORT);
    }
    private void accept(){
        while(true){
            ServerSocket active=server;if(active==null||active.isClosed())return;
            try{
                Socket client=active.accept();InetAddress peer=client.getInetAddress();
                if(!peer.isSiteLocalAddress()&&!peer.isLoopbackAddress()){client.close();continue;}
                client.setSoTimeout(5000);clients.add(client);
                try{workers.execute(()->serve(client));}catch(RuntimeException full){clients.remove(client);client.close();}
            }catch(IOException closed){if(!active.isClosed())Log.w(TAG,"HTTP accept failed",closed);}
        }
    }
    private void serve(Socket socket){
        try(Socket client=socket){
            try{
                final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                InputStream raw=client.getInputStream();InputStream timed=new InputStream(){
                    private void check() throws IOException {long remaining=deadline-System.nanoTime();if(remaining<=0)throw new SocketTimeoutException();client.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));}
                    @Override public int read() throws IOException {check();return raw.read();}
                    @Override public int read(byte[] b,int off,int length) throws IOException {check();return raw.read(b,off,length);}
                };
                HttpProtocol.Request request=HttpProtocol.read(timed);HttpProtocol.authorize(request,token,localAddress(),PORT);
                if(request.path.startsWith("/api/")){
                    JSONObject result=api(request);respond(client,200,"application/json; charset=utf-8",result==null?"null".getBytes(StandardCharsets.UTF_8):result.toString().getBytes(StandardCharsets.UTF_8));
                }else{
                    if(!request.method.equals("GET"))throw new HttpProtocol.Failure(405,"Static content requires GET");
                    String name=assets.get(request.path);if(name==null)throw new HttpProtocol.Failure(404,"Unknown path");
                    try(InputStream source=context.getAssets().open(name)){respond(client,200,name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":name.endsWith(".svg")?"image/svg+xml; charset=utf-8":"application/javascript; charset=utf-8",readAsset(source));}
                }
            }catch(HttpProtocol.Failure failure){respond(client,failure.status,"application/json; charset=utf-8",error(failure.getMessage()));}
            catch(SocketTimeoutException timeout){respond(client,408,"application/json; charset=utf-8",error("Request timeout"));}
            catch(Exception failure){Log.w(TAG,"HTTP request failed",failure);respond(client,400,"application/json; charset=utf-8",error(failure.getMessage()==null?"Request failed":failure.getMessage()));}
        }catch(IOException ignored){}finally{clients.remove(socket);}
    }
    private byte[] readAsset(InputStream source) throws IOException {ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=source.read(buffer))!=-1){if(data.size()+n>1048576)throw new IOException("Asset too large");data.write(buffer,0,n);}return data.toByteArray();}
    private byte[] error(String text){try{return new JSONObject().put("ok",false).put("message",text).toString().getBytes(StandardCharsets.UTF_8);}catch(Exception ignored){return "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);}}
    static void validateSourceFilter(LayoutConfig layout,DisplayEnvironment.Source[] sources){
        if(!layout.sourceFilterEnabled)return;
        java.util.HashSet<String> supported=new java.util.HashSet<>();
        if(sources!=null)for(DisplayEnvironment.Source source:sources)if(source!=null&&source.id!=null&&!source.id.isEmpty())supported.add(source.id);
        if(supported.isEmpty())throw new IllegalArgumentException("尚未取得当前设备的接口列表，不能开启接口筛选");
        for(String id:layout.allowedSources)if(!supported.contains(id))throw new IllegalArgumentException("当前设备不支持接口ID："+id);
    }
    private JSONObject api(HttpProtocol.Request request) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(request.method.equals("GET")&&request.path.equals("/api/config")){String raw=prefs.getString("layout","");return raw.isEmpty()?null:new JSONObject(raw);}
        if(request.method.equals("GET")&&request.path.equals("/api/preview"))return new JSONObject(AmbientService.preview());
        if(request.method.equals("GET")&&request.path.equals("/api/status")){
            JSONObject result=new JSONObject(AmbientService.status()).put("enabled",prefs.getBoolean("enabled",false)).put("autoStart",prefs.getBoolean("autoStart",true)).put("serverEnabled",prefs.getBoolean("serverEnabled",false)).put("connection",connection).put("serverUrl",pairingUrl(context));
            String raw=prefs.getString("layout","");if(!raw.isEmpty()){
                LayoutConfig layout=new LayoutConfig(raw);result.put("savedAddress",layout.address).put("savedLedCount",layout.ledCount).put("savedConfigFingerprint",layout.fingerprint)
                    .put("pendingApply",!layout.fingerprint.equals(result.optString("appliedConfigFingerprint","")));
                boolean hasAppliedAddress=!result.isNull("address")&&!result.optString("address","").isEmpty();
                boolean hasAppliedCount=!result.isNull("ledCount")&&result.optInt("ledCount",0)>0;
                if(!hasAppliedAddress&&!hasAppliedCount)result.put("address",layout.address).put("ledCount",layout.ledCount);
            }
            return result;
        }
        if(!request.method.equals("POST"))throw new HttpProtocol.Failure(405,"This API requires POST");
        String raw=new String(request.body,StandardCharsets.UTF_8);JSONObject body=raw.isEmpty()?new JSONObject():new JSONObject(raw);
        if(request.path.equals("/api/config")){
            LayoutConfig layout=new LayoutConfig(raw);validateSourceFilter(layout,AmbientService.supportedSources());JSONObject preferences=body.optJSONObject("preferences");boolean autoStart=preferences==null?prefs.getBoolean("autoStart",true):preferences.optBoolean("autoStart",true);
            if(!prefs.edit().putString("layout",layout.raw).putBoolean("autoStart",autoStart).commit())throw new IOException("配置未能写入电视");
            return new JSONObject().put("ok",true).put("savedConfigFingerprint",layout.fingerprint).put("message","布局已保存到电视；重新开始同步后生效");
        }
        if(request.path.equals("/api/start")){
            LayoutConfig layout=new LayoutConfig(prefs.getString("layout",""));validateSourceFilter(layout,AmbientService.supportedSources());AmbientService.dispatch(context,AmbientService.ACTION_START);
            return new JSONObject().put("ok",true).put("requestedConfigFingerprint",layout.fingerprint).put("message","已请求应用保存配置，请核对已应用指纹与实际输出");
        }
        if(request.path.equals("/api/stop")){
            AmbientService.dispatch(context,AmbientService.ACTION_PAUSE);
            return new JSONObject().put("ok",true).put("message","已请求停止同步，网页后台继续运行");
        }
        if(request.path.equals("/api/probe")){
            String address=body.getString("address").trim();JSONObject info=WledInfo.read(address);connection=info.getString("name")+" · "+info.getInt("ledCount")+"颗 · WLED "+info.getString("version");
            return info.put("ok",true).put("address",address);
        }
        throw new HttpProtocol.Failure(404,"Unknown API");
    }
    private void respond(Socket socket,int status,String type,byte[] body) throws IOException {
        OutputStream out=socket.getOutputStream();String headers="HTTP/1.1 "+status+" "+HttpProtocol.reason(status)+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    @Override public synchronized void close(){live=false;ServerSocket old=server;server=null;if(old!=null)try{old.close();}catch(IOException ignored){}workers.shutdownNow();for(Socket client:clients)try{client.close();}catch(IOException ignored){}clients.clear();Log.i(TAG,"LAN configuration server stopped");}
}
