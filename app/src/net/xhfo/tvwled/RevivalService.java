package net.xhfo.tvwled;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/** Optional system binding only. Never reads, queries, cancels, or handles notifications. */
public final class RevivalService extends NotificationListenerService {
    private static final String TAG="XHFlowRevival";
    private static volatile RevivalService current;
    private static volatile boolean manualRequested;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RecoveryPolicy.BindWindow window=new RecoveryPolicy.BindWindow();
    private volatile boolean alive,listenerConnected;
    private volatile AmbientService.HostBinder host;
    private HostConnection connection;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener=(prefs,key)->{
        if("serverEnabled".equals(key)||"autoStart".equals(key))main.post(()->{
            if(!alive)return;
            if(!mayHost(this)){cancelWindow();return;}
            startWindow();
        });
    };

    static boolean isAuthorized(Context context){
        try{
            ComponentName expected=new ComponentName(context,RevivalService.class);
            if(Build.VERSION.SDK_INT>=27){
                NotificationManager manager=context.getSystemService(NotificationManager.class);
                return manager!=null&&manager.isNotificationListenerAccessGranted(expected);
            }
            String enabled=Settings.Secure.getString(context.getContentResolver(),"enabled_notification_listeners");
            if(enabled!=null)for(String entry:enabled.split(":"))if(expected.equals(ComponentName.unflattenFromString(entry)))return true;
        }catch(RuntimeException unavailable){/* Approval is unknown, so do not start a host. */}
        return false;
    }
    static boolean isConnected(){
        RevivalService service=current;
        return service!=null&&service.alive&&service.listenerConnected&&service.host!=null&&service.host.isAliveHost()&&isAuthorized(service);
    }
    static boolean mayHost(Context context){
        SharedPreferences prefs=context.getSharedPreferences(AmbientService.PREFS,MODE_PRIVATE);
        RevivalService service=current;
        AmbientService.HostBinder active=service==null?null:service.host;
        return RecoveryPolicy.mayHost(prefs.getBoolean("serverEnabled",false),prefs.getBoolean("autoStart",true),manualRequested,active!=null&&active.isEstablishedHost());
    }
    static void requestSystemRebind(Context context){
        if(!isAuthorized(context))return;
        try{requestRebind(new ComponentName(context,RevivalService.class));}
        catch(RuntimeException failure){Log.w(TAG,"System recovery rebind request was rejected",failure);}
    }
    static void requestManualBind(Context context){
        manualRequested=true;
        RevivalService service=current;
        if(service!=null)service.main.post(()->{if(service.alive)service.startWindow();});
    }
    static void releaseHost(){
        manualRequested=false;
        RevivalService service=current;
        if(service!=null)service.cancelWindow();
    }
    @Override public void onCreate(){
        super.onCreate();alive=true;current=this;
        preferences=getSharedPreferences(AmbientService.PREFS,MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        // Some TV firmwares construct the approved service before delivering listenerConnected.
        // Check the actual approval on every attempt; construction alone is not authorization.
        startWindow();
    }
    @Override public void onListenerConnected(){
        super.onListenerConnected();
        if(!alive)return;
        listenerConnected=true;startWindow();AmbientService.refreshStatus();
        Log.i(TAG,"System recovery binding connected; notification filters exclude all categories");
    }
    @Override public void onListenerDisconnected(){
        super.onListenerDisconnected();
        listenerConnected=false;cancelWindow();AmbientService.refreshStatus();
        // This only asks Android to rebind an existing approval. It never grants or rewrites it.
        if(alive)requestSystemRebind(this);
    }
    private void startWindow(){
        if(!alive)return;
        if(connection!=null&&host!=null&&allowed()){AmbientService.refreshStatus();return;}
        cancelWindow();window.begin();ensureBinding();
    }
    private boolean allowed(){return alive&&mayHost(this)&&isAuthorized(this);}
    private void ensureBinding(){
        if(connection!=null)return;
        long lease=window.next(allowed());
        if(lease<0)return;
        HostConnection attempt=new HostConnection(lease);connection=attempt;
        try{
            attempt.bound=bindService(new Intent(this,AmbientService.class),attempt,Context.BIND_AUTO_CREATE);
            if(!attempt.bound)failed(attempt,"Local host binding was not accepted");
            else main.postDelayed(()->{
                if(connection==attempt&&window.accepts(lease,alive)&&host==null)failed(attempt,"Local host binding timed out");
            },5000);
        }catch(RuntimeException failure){Log.w(TAG,"Local host binding failed",failure);failed(attempt,"Local host binding failed");}
    }
    private final class HostConnection implements ServiceConnection {
        final long lease;boolean bound;
        HostConnection(long value){lease=value;}
        boolean valid(){return connection==this&&window.accepts(lease,allowed());}
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(!valid()){release(this);return;}
            if(!(binder instanceof AmbientService.HostBinder)){failed(this,"Unexpected local host binder");return;}
            host=(AmbientService.HostBinder)binder;
            host.ensureHosted(this::valid);AmbientService.refreshStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name){failed(this,"Local host disconnected");}
        @Override public void onBindingDied(ComponentName name){failed(this,"Local host binding died");}
        @Override public void onNullBinding(ComponentName name){failed(this,"Local host returned no binder");}
    }
    private void failed(HostConnection failed,String reason){
        if(connection!=failed)return;
        long lease=failed.lease;release(failed);AmbientService.refreshStatus();
        Log.w(TAG,reason);
        // At most three attempts in this lifecycle/preference window; no immortal retry loop.
        main.postDelayed(()->{if(window.accepts(lease,allowed()))ensureBinding();},1500);
    }
    private void release(HostConnection old){
        if(connection!=old)return;
        connection=null;host=null;
        if(old.bound){old.bound=false;try{unbindService(old);}catch(IllegalArgumentException ignored){}}
    }
    private void cancelWindow(){
        window.end();main.removeCallbacksAndMessages(null);
        if(connection!=null)release(connection);
        AmbientService.refreshStatus();
    }
    @Override public boolean onUnbind(Intent intent){
        listenerConnected=false;cancelWindow();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy(){
        alive=false;listenerConnected=false;
        if(current==this)current=null;
        if(preferences!=null){preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);preferences=null;}
        cancelWindow();manualRequested=false;
        super.onDestroy();
    }
}
