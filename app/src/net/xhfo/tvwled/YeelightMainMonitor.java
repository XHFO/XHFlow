package net.xhfo.tvwled;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only observation of the front lamp, separate from the ambient UDP session. */
final class YeelightMainMonitor implements Closeable {
    interface Listener {
        void snapshot(int brightness,int ct);
        /** A reply may have been sampled before a newer UDP write; retain its request age. */
        default void snapshot(int brightness,int ct,long queryAgeMs){snapshot(brightness,ct);}
        void changed(Integer brightness,Integer ct);
    }

    static final int PORT=55443;
    private static final int POLL_MS=2000,CONNECT_MS=900,REPLY_MS=1500,RETRY_MS=350;
    private static final int MAX_LINE=4096,LINE_MS=1500;
    private static final long FRESH_NS=6000000000L;
    private final InetAddress address;
    private final int port;
    private final Listener listener;
    private volatile boolean closed,connected;
    private volatile long lastSnapshot;
    private volatile Socket socket;
    private volatile Thread worker;
    private int nextId=1;

    YeelightMainMonitor(String address,Listener listener) throws Exception {
        this(LayoutConfig.validateAddress(address),PORT,listener);
    }

    /** Loopback injection for host protocol contracts; production uses the validated LAN address. */
    YeelightMainMonitor(InetAddress address,int port,Listener listener) {
        if(address==null||listener==null||port<1||port>65535)throw new IllegalArgumentException("Monitor endpoint and listener required");
        this.address=address;this.port=port;this.listener=listener;
    }

    synchronized void start(){
        if(closed||worker!=null)return;
        worker=new Thread(this::run,"TVWLED-yeelight-main");
        worker.setDaemon(true);worker.start();
    }

    boolean ready(){
        long age=System.nanoTime()-lastSnapshot;
        return !closed&&connected&&lastSnapshot!=0&&age>=0&&age<=FRESH_NS;
    }

    private void run(){
        while(!closed){
            Socket attempt=new Socket();socket=attempt;
            try{
                if(closed)break;
                attempt.connect(new InetSocketAddress(address,port),CONNECT_MS);
                attempt.setSoTimeout(200);attempt.setTcpNoDelay(true);
                connected=true;lastSnapshot=0;
                observe(attempt);
            }catch(Exception unavailable){
                // A missing or unsupported LAN status channel never grants main-light control.
            }finally{
                connected=false;lastSnapshot=0;
                try{attempt.close();}catch(IOException ignored){}
                if(socket==attempt)socket=null;
            }
            if(!closed)try{Thread.sleep(RETRY_MS);}catch(InterruptedException stop){if(closed)return;}
        }
    }

    private void observe(Socket active) throws Exception {
        InputStream input=active.getInputStream();
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        byte[] chunk=new byte[512];
        long nextPoll=0,pendingAt=0,lineAt=0;
        int pendingId=0;
        while(!closed){
            long now=System.nanoTime();
            if(pendingId!=0&&elapsedMs(pendingAt,now)>=REPLY_MS)throw new IOException("Status reply timed out");
            if(line.size()>0&&elapsedMs(lineAt,now)>=LINE_MS)throw new IOException("Status line timed out");
            if(pendingId==0&&(nextPoll==0||now-nextPoll>=0)){
                pendingId=nextId++;if(nextId<1)nextId=1;
                String query=new JSONObject().put("id",pendingId).put("method","get_prop")
                    .put("params",new JSONArray().put("bright").put("ct")).toString()+"\r\n";
                active.getOutputStream().write(query.getBytes(StandardCharsets.UTF_8));
                active.getOutputStream().flush();pendingAt=now;nextPoll=now+POLL_MS*1000000L;
            }
            int count;
            try{count=input.read(chunk);}catch(SocketTimeoutException retry){continue;}
            if(count<0)throw new IOException("Status connection ended");
            for(int i=0;i<count&&!closed;i++){
                int value=chunk[i]&255;
                if(value=='\n'){
                    String message=new String(line.toByteArray(),StandardCharsets.UTF_8);line.reset();lineAt=0;
                    if(handle(message,pendingId,pendingAt)){pendingId=0;pendingAt=0;}
                }else{
                    if(line.size()==0)lineAt=System.nanoTime();
                    if(line.size()>=MAX_LINE)throw new IOException("Status line too long");
                    line.write(value);
                }
            }
        }
    }

    /** Only the matching, complete get_prop response refreshes readiness. */
    private boolean handle(String line,int pendingId,long pendingAt){
        if(closed||line.trim().isEmpty())return false;
        try{
            JSONObject message=new JSONObject(line);
            if("props".equals(message.opt("method"))){
                JSONObject params=message.optJSONObject("params");
                if(params==null)return false;
                Integer brightness=integer(params.opt("bright"),1,100),ct=integer(params.opt("ct"),1700,6500);
                if(brightness!=null||ct!=null)listener.changed(brightness,ct);
                return false;
            }
            Integer id=integer(message.opt("id"),1,Integer.MAX_VALUE);
            if(pendingId==0||id==null||id!=pendingId||message.has("error"))return false;
            JSONArray result=message.optJSONArray("result");
            if(result==null||result.length()!=2)return false;
            Integer brightness=integer(result.opt(0),1,100),ct=integer(result.opt(1),1700,6500);
            if(brightness==null||ct==null)return false;
            listener.snapshot(brightness,ct,Math.max(0,elapsedMs(pendingAt,System.nanoTime())));
            if(!closed)lastSnapshot=System.nanoTime();
            return true;
        }catch(Exception malformed){return false;}
    }

    private static Integer integer(Object value,int minimum,int maximum){
        if(value instanceof String){
            String text=(String)value;
            if(text.length()>10||!text.matches("[0-9]+"))return null;
            try{long number=Long.parseLong(text);return number>=minimum&&number<=maximum?(int)number:null;}
            catch(NumberFormatException invalid){return null;}
        }
        if(!(value instanceof Number))return null;
        double number=((Number)value).doubleValue();
        return Double.isFinite(number)&&number>=minimum&&number<=maximum&&number==Math.rint(number)?(int)number:null;
    }

    private static long elapsedMs(long start,long end){return (end-start)/1000000L;}

    @Override public void close(){
        closed=true;connected=false;lastSnapshot=0;
        Socket active=socket;if(active!=null)try{active.close();}catch(IOException ignored){}
        Thread activeWorker=worker;if(activeWorker!=null)activeWorker.interrupt();
    }
}
