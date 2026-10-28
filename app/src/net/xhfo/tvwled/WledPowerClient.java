package net.xhfo.tvwled;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Background-only WLED power writes. HTTP success alone never confirms the resulting light state. */
final class WledPowerClient implements AutoCloseable {
    interface Transport extends AutoCloseable {
        /** A non-null JSON body means POST; null means GET. */
        String request(String address,String path,String json) throws Exception;
        @Override void close();
    }
    static final class Result {
        final boolean success,requestedOn;
        final Boolean readbackOn,readbackLive;
        final String error;
        final long durationMs;
        Result(boolean success,boolean requestedOn,Boolean readbackOn,Boolean readbackLive,String error,long durationMs){this.success=success;this.requestedOn=requestedOn;this.readbackOn=readbackOn;this.readbackLive=readbackLive;this.error=error;this.durationMs=durationMs;}
    }
    private final Transport transport;
    private volatile boolean closed;
    WledPowerClient(){this(new HttpTransport());}
    WledPowerClient(Transport transport){
        if(transport==null)throw new IllegalArgumentException("Power transport required");
        this.transport=transport;
    }
    synchronized Result setPower(String address,boolean on){
        long started=System.nanoTime();Boolean readbackOn=null,readbackLive=null;
        try{
            requireOpen();LayoutConfig.validateAddress(address);
            JSONObject command=new JSONObject().put("on",on).put("tt",0);
            if(!on)command.put("live",false);
            request(address,"/json/state",command.toString());
            JSONObject state=new JSONObject(request(address,"/json/state",null));
            Object value=state.get("on");
            if(!(value instanceof Boolean))throw new IllegalStateException("WLED电源回读格式错误");
            readbackOn=(Boolean)value;
            if(readbackOn!=on)throw new IllegalStateException("WLED电源回读与请求不一致");
            if(!on){
                Object live=new JSONObject(request(address,"/json/info",null)).get("live");
                if(!(live instanceof Boolean))throw new IllegalStateException("WLED实时输入回读格式错误");
                readbackLive=(Boolean)live;
                if(readbackLive)throw new IllegalStateException("WLED仍处于实时输入，未确认休眠关灯");
            }
            requireOpen();
            return result(true,on,readbackOn,readbackLive,"",started);
        }catch(Exception failure){return result(false,on,readbackOn,readbackLive,failure.getClass().getSimpleName()+": "+failure.getMessage(),started);}
    }
    private Result result(boolean success,boolean requestedOn,Boolean readbackOn,Boolean readbackLive,String error,long started){
        return new Result(success,requestedOn,readbackOn,readbackLive,error,Math.max(0,(System.nanoTime()-started)/1000000));
    }
    private void requireOpen(){
        if(closed||Thread.currentThread().isInterrupted())throw new IllegalStateException("电源请求已取消");
    }
    private String request(String address,String path,String json) throws Exception {
        requireOpen();String result=transport.request(address,path,json);requireOpen();return result;
    }
    void cancel(){closed=true;}
    @Override public void close(){cancel();transport.close();}

    /** Connection publication is synchronized with close, so cancellation cannot miss a late connection. */
    private static final class HttpTransport implements Transport {
        private boolean closed;
        private HttpURLConnection connection;
        @Override public String request(String address,String path,String json) throws Exception {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("电源请求已取消");
            HttpURLConnection next=(HttpURLConnection)new URL("http://"+address+path).openConnection();
            try{
                synchronized(this){
                    if(closed)throw new IllegalStateException("电源请求已取消");
                    connection=next;
                }
                next.setConnectTimeout(1800);next.setReadTimeout(1800);next.setInstanceFollowRedirects(false);next.setUseCaches(false);
                if(json!=null){
                    byte[] body=json.getBytes(StandardCharsets.UTF_8);next.setRequestMethod("POST");next.setRequestProperty("Content-Type","application/json");next.setDoOutput(true);next.setFixedLengthStreamingMode(body.length);
                    requireOpen();
                    try(OutputStream output=next.getOutputStream()){requireOpen();output.write(body);}
                }
                requireOpen();int code=next.getResponseCode();
                if(code<200||code>=300)throw new IllegalStateException("WLED HTTP "+code);
                ByteArrayOutputStream data=new ByteArrayOutputStream();long deadline=System.nanoTime()+7000000000L;
                try(InputStream input=next.getInputStream()){
                    byte[] buffer=new byte[4096];int count;
                    while((count=input.read(buffer))!=-1){
                        requireOpen();
                        if(System.nanoTime()>deadline||data.size()+count>131072)throw new IllegalStateException("WLED回读超时或过大");
                        data.write(buffer,0,count);
                    }
                }
                requireOpen();return new String(data.toByteArray(),StandardCharsets.UTF_8);
            }finally{
                synchronized(this){if(connection==next)connection=null;}
                next.disconnect();
            }
        }
        private synchronized void requireOpen(){
            if(closed||Thread.currentThread().isInterrupted())throw new IllegalStateException("电源请求已取消");
        }
        @Override public void close(){
            HttpURLConnection current;
            synchronized(this){closed=true;current=connection;}
            if(current!=null)current.disconnect();
        }
    }
}
