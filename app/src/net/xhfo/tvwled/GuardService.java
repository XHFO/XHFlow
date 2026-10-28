package net.xhfo.tvwled;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/** User-enabled system binding host only: no events, windows, keys, gestures, or global actions. */
public final class GuardService extends AccessibilityService {
    private static volatile GuardService current;
    private static volatile boolean manualRequested;
    private volatile boolean systemConnected,binding;
    private boolean bindingDeathRetried;
    private volatile AmbientService.HostBinder host;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener=(prefs,key)->{
        if("serverEnabled".equals(key)||"autoStart".equals(key)){
            if(!prefs.getBoolean("serverEnabled",false))releaseBinding();
            else if(mayHost(this))ensureBinding();
            AmbientService.refreshStatus();
        }
    };
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(!binding||!systemConnected||!mayHost(GuardService.this)){
                releaseBinding();return;
            }
            if(!(binder instanceof AmbientService.HostBinder)){releaseBinding();Log.w("TVWLED_GUARD","Unexpected local host binder");return;}
            host=(AmbientService.HostBinder)binder;
            host.ensureHosted();AmbientService.refreshStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name){host=null;AmbientService.refreshStatus();}
        @Override public void onBindingDied(ComponentName name){
            releaseBinding();
            if(!bindingDeathRetried&&systemConnected&&mayHost(GuardService.this)){bindingDeathRetried=true;ensureBinding();}
        }
        @Override public void onNullBinding(ComponentName name){releaseBinding();}
    };
    static boolean isConnected(){GuardService guard=current;return guard!=null&&guard.systemConnected&&guard.binding&&guard.host!=null&&guard.host.isAliveHost();}
    static boolean mayHost(Context context){
        SharedPreferences prefs=context.getSharedPreferences(AmbientService.PREFS,MODE_PRIVATE);
        return prefs.getBoolean("serverEnabled",false)&&(prefs.getBoolean("autoStart",true)||manualRequested);
    }
    static boolean isAuthorized(Context context){
        try{
            // This ROM's manager list contains only live bindings. Read the configured flag separately.
            String enabled=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(enabled==null||enabled.isEmpty())return false;
            ComponentName expected=new ComponentName(context,GuardService.class);
            for(String item:enabled.split(":")){
                ComponentName component=ComponentName.unflattenFromString(item);
                if(expected.equals(component))return true;
            }
            return false;
        }catch(RuntimeException unavailable){/* Public read unavailable: fall back to the manager. */}
        try{
            AccessibilityManager manager=context.getSystemService(AccessibilityManager.class);
            for(AccessibilityServiceInfo info:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){
                if(info.getResolveInfo()==null||info.getResolveInfo().serviceInfo==null)continue;
                android.content.pm.ServiceInfo service=info.getResolveInfo().serviceInfo;
                if(context.getPackageName().equals(service.packageName)&&GuardService.class.getName().equals(service.name))return true;
            }
        }catch(RuntimeException ignored){}
        return false;
    }
    static void requestManualBind(Context context){
        manualRequested=true;
        GuardService guard=current;
        if(guard!=null&&guard.systemConnected){guard.bindingDeathRetried=false;guard.ensureBinding();}
    }
    static void releaseHost(){manualRequested=false;GuardService guard=current;if(guard!=null)guard.releaseBinding();}
    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        AccessibilityServiceInfo info=getServiceInfo();
        if(info!=null){info.eventTypes=0;info.flags=0;setServiceInfo(info);}
        systemConnected=true;current=this;
        preferences=getSharedPreferences(AmbientService.PREFS,MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        if(mayHost(this))ensureBinding();
        AmbientService.refreshStatus();
        Log.i("TVWLED_GUARD","System connected optional background host; eventTypes=0 flags=0");
    }
    private void ensureBinding(){
        if(binding||!systemConnected||!mayHost(this))return;
        try{
            binding=bindService(new Intent(this,AmbientService.class),connection,Context.BIND_AUTO_CREATE);
            if(!binding)Log.w("TVWLED_GUARD","Local host binding was not accepted");
        }catch(RuntimeException failure){binding=false;Log.w("TVWLED_GUARD","Local host binding failed",failure);}
        AmbientService.refreshStatus();
    }
    private void releaseBinding(){
        host=null;
        if(binding){binding=false;try{unbindService(connection);}catch(IllegalArgumentException ignored){}}
        AmbientService.refreshStatus();
    }
    private void releaseSystemConnection(){
        systemConnected=false;
        if(current==this)current=null;
        if(preferences!=null){preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);preferences=null;}
        releaseBinding();
        manualRequested=false;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
    @Override public boolean onUnbind(Intent intent){releaseSystemConnection();return super.onUnbind(intent);}
    @Override public void onDestroy(){releaseSystemConnection();super.onDestroy();}
}
