package net.xhfo.tvwled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public display state plus read-only, verified Redmi current-selected-source settings. */
final class DisplayEnvironment implements AutoCloseable {
    interface Listener {void changed(Snapshot snapshot);}
    static final class Source {
        final String id,label;Source(String id,String label){this.id=id;this.label=label;}
    }
    static final class Snapshot {
        final boolean interactive,awake,sleeping,inputKnown;
        final int displayCode,str;
        final String displayState,inputId,inputLabel,error;
        final Source[] supported;
        Snapshot(boolean interactive,int displayCode,int str,String inputId,Source[] supported,String error){
            this.interactive=interactive;this.displayCode=displayCode;this.str=str;this.inputId=inputId;this.supported=supported.clone();this.error=error;
            displayState=name(displayCode);
            boolean panelOn=displayCode==Display.STATE_ON||displayCode==Display.STATE_VR||displayCode==Display.STATE_ON_SUSPEND;
            boolean panelOff=displayCode==Display.STATE_OFF||displayCode==Display.STATE_DOZE||displayCode==Display.STATE_DOZE_SUSPEND;
            awake=interactive&&panelOn&&str!=1;
            sleeping=!interactive&&(panelOff||str==1);
            String label="未知";boolean known=false;
            for(Source source:supported)if(source.id.equals(inputId)){label=source.label;known=true;break;}
            inputKnown=known;inputLabel=label;
        }
        private static String name(int state){switch(state){case Display.STATE_ON:return "on";case Display.STATE_OFF:return "off";case Display.STATE_DOZE:return "doze";case Display.STATE_DOZE_SUSPEND:return "doze_suspend";case Display.STATE_ON_SUSPEND:return "on_suspend";case Display.STATE_VR:return "vr";default:return "unknown";}}
    }
    private final Context context;
    private final Handler main;
    private final Listener listener;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"TVWLED-display"));
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean active;
    private boolean receiverRegistered,displayRegistered,observerRegistered;
    private Source[] sources=new Source[0];
    private long nextSourceCheck;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){requestRefresh();}};
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){public void onDisplayAdded(int id){requestRefresh();}public void onDisplayRemoved(int id){requestRefresh();}public void onDisplayChanged(int id){if(id==Display.DEFAULT_DISPLAY)requestRefresh();}};
    private final ContentObserver observer;
    DisplayEnvironment(Context context,Handler main,Listener listener){
        this.context=context.getApplicationContext();this.main=main;this.listener=listener;
        observer=new ContentObserver(main){@Override public void onChange(boolean selfChange){requestRefresh();}};
    }
    void start(){
        if(active)return;active=true;
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(receiver,filter);
        receiverRegistered=true;
        DisplayManager manager=context.getSystemService(DisplayManager.class);if(manager!=null){manager.registerDisplayListener(displayListener,main);displayRegistered=true;}
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor("current_source_for_tvplayer"),false,observer);observerRegistered=true;
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor("system_str"),false,observer);
        executor.scheduleWithFixedDelay(this::read,0,1000,TimeUnit.MILLISECONDS);
    }
    void requestRefresh(){if(active&&queued.compareAndSet(false,true))try{executor.execute(()->{queued.set(false);read();});}catch(RuntimeException stopped){queued.set(false);}}
    private void read(){
        if(!active)return;
        try{
            String error="";
            long now=android.os.SystemClock.elapsedRealtime();
            if(sources.length==0&&now>=nextSourceCheck){
                nextSourceCheck=now+10000;
                try{
                    String profile=new ColorClient().configuration("source_ports_list");
                    if("source_list_monitor_usbc".equals(profile))sources=new Source[]{new Source("23","HDMI 1"),new Source("24","HDMI 2"),new Source("29","DP"),new Source("30","USB-C"),new Source("34","内置系统画面")};
                    else error="当前接口列表未确认";
                }catch(Exception unavailable){error="接口列表暂不可读："+unavailable.getMessage();}
            }
            PowerManager power=context.getSystemService(PowerManager.class);boolean interactive=power!=null&&power.isInteractive();
            DisplayManager manager=context.getSystemService(DisplayManager.class);Display display=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
            int code=display==null?Display.STATE_UNKNOWN:display.getState();
            int str=Settings.System.getInt(context.getContentResolver(),"system_str",-1);
            String source=null;
            try{source=String.valueOf(Settings.System.getInt(context.getContentResolver(),"current_source_for_tvplayer"));}catch(Settings.SettingNotFoundException missing){error="当前选中接口未知";}
            Snapshot value=new Snapshot(interactive,code,str,source,sources,error);
            main.post(()->{if(active)listener.changed(value);});
        }catch(RuntimeException failure){
            Snapshot value=new Snapshot(false,Display.STATE_UNKNOWN,-1,null,new Source[0],"显示状态读取失败："+failure.getMessage());
            main.post(()->{if(active)listener.changed(value);});
        }
    }
    @Override public void close(){
        active=false;executor.shutdownNow();
        if(receiverRegistered){receiverRegistered=false;try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}}
        if(displayRegistered){displayRegistered=false;DisplayManager manager=context.getSystemService(DisplayManager.class);if(manager!=null)try{manager.unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}}
        if(observerRegistered){observerRegistered=false;try{context.getContentResolver().unregisterContentObserver(observer);}catch(RuntimeException ignored){}}
    }
}
