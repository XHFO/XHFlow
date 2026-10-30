package net.xhfo.tvwled;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class AmbientService extends Service {
    public static final String ACTION_START="net.xhfo.tvwled.START";
    public static final String ACTION_STOP="net.xhfo.tvwled.STOP", ACTION_PAUSE=ACTION_STOP;
    public static final String ACTION_CONFIG="net.xhfo.tvwled.CONFIG", ACTION_SERVER=ACTION_CONFIG;
    public static final String ACTION_SHUTDOWN="net.xhfo.tvwled.SHUTDOWN";
    public static final String ACTION_RECOVER="net.xhfo.tvwled.RECOVER";
    static final String ACTION_APPLY="net.xhfo.tvwled.APPLY",EXTRA_EXPECTED_CONFIG="expectedConfigFingerprint";
    static final String PREFS="tvwled";
    private static final String TAG="TVWLED", CHANNEL="ambient";
    private static final int NOTICE=101;
    private static volatile AmbientService liveInstance;
    private static volatile String snapshot="{\"running\":false,\"serverRunning\":false,\"state\":\"尚未开始\"}";
    private final Object lifecycleLock=new Object();
    private volatile Session session, lastSession;
    private volatile boolean destroyed, closing, serverRunning, foreground;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HostBinder hostBinder=new HostBinder();
    private volatile String foregroundError="";
    private volatile String state="配置后台待启动", error="", serverError="", recovery="", preferenceError="";
    private ConfigServer server;
    // Saved configuration is intentionally separate from the currently applied session.
    private volatile LayoutConfig activeConfig;
    private volatile boolean outputAllowed,sourceOwned;
    private boolean configurationLoaded,initializationFailed,sleepHandled,sourceExcludedHandled;
    private volatile String suspendedReason="";
    private long configurationGeneration,powerSerial;
    private volatile String wakeAddress="",restorePowerReason="";
    private String cachedSavedRaw,cachedSavedFingerprint="",fingerprintError="";
    private final Object fingerprintLock=new Object();
    private volatile DisplayEnvironment.Snapshot environment;
    private DisplayEnvironment environmentMonitor;
    private ScheduledExecutorService powerExecutor;
    private volatile PowerOperation powerOperation;
    private static final class PowerOperation {
        final LayoutConfig config;final long generation,serial;final boolean on;final String reason;
        volatile WledPowerClient client=new WledPowerClient();
        volatile boolean pending=true,retrying;volatile int attempt=1;volatile WledPowerClient.Result result;
        PowerOperation(LayoutConfig config,long generation,long serial,boolean on,String reason){this.config=config;this.generation=generation;this.serial=serial;this.on=on;this.reason=reason;}
    }
    static DisplayEnvironment.Source[] supportedSources(){
        AmbientService current=liveInstance;DisplayEnvironment.Snapshot value=current==null?null:current.environment;
        return value==null?new DisplayEnvironment.Source[0]:value.supported.clone();
    }

    public static String status(){AmbientService current=liveInstance;if(current!=null&&!current.destroyed)current.publish();return snapshot;}
    public static String preview(){
        AmbientService current=liveInstance;
        try{
            Session active=current==null?null:current.session;
            PreviewFrame sample=active==null?null:active.previewFrame;
            JSONObject frame=sample==null?new JSONObject().put("points",new JSONArray()).put("source","screen-edges"):new JSONObject(sample.json);
            long age=sample==null?-1:Math.max(0,SystemClock.elapsedRealtime()-sample.capturedAt);
            return frame.put("available",active!=null&&active.isCurrent()&&active.valid&&sample!=null&&age<=1500).put("ageMs",age).toString();
        }catch(Exception ignored){return "{\"available\":false,\"points\":[],\"source\":\"screen-edges\"}";}
    }
    public static void dispatch(Context context,String action){
        AmbientService current=liveInstance;
        if(current!=null&&!current.destroyed){current.main.post(()->current.handleCommand(action));return;}
        context.startForegroundService(new Intent(context,AmbientService.class).setAction(action));
    }
    /** Conditional settings updates never become an implicit request to enable synchronization. */
    static void dispatchConfiguration(Context context,boolean applyOnly,String expectedFingerprint){
        String action=applyOnly?ACTION_APPLY:ACTION_START;AmbientService current=liveInstance;
        if(current!=null&&!current.destroyed){current.main.post(()->current.handleConfigurationCommand(action,expectedFingerprint));return;}
        context.startForegroundService(new Intent(context,AmbientService.class).setAction(action).putExtra(EXTRA_EXPECTED_CONFIG,expectedFingerprint));
    }
    static boolean configurationCommandAllowed(String expected,String savedRaw,boolean enabled,boolean applyOnly) throws Exception {
        return expected!=null&&expected.equals(ConfigServer.configurationFingerprint(savedRaw))&&(!applyOnly||enabled);
    }
    /** Gate initialization as well as application, so a cold automatic apply cannot revive a stop. */
    static boolean runConfigurationCommand(SharedPreferences prefs,String expected,boolean applyOnly,boolean closing,Runnable initialize,Runnable command) throws Exception {
        synchronized(ConfigServer.CONFIG_LOCK){
            if((applyOnly&&closing)||!configurationCommandAllowed(expected,prefs.getString("layout",""),prefs.getBoolean("enabled",false),applyOnly))return false;
            initialize.run();
            // Initialization may invoke synchronous local callbacks; never apply a stale target.
            if(!configurationCommandAllowed(expected,prefs.getString("layout",""),prefs.getBoolean("enabled",false),applyOnly))return false;
            command.run();return true;
        }
    }
    static void refreshStatus(){AmbientService current=liveInstance;if(current!=null&&!current.destroyed)current.main.post(()->{current.publish();current.updateNotification();});}
    /** A queued system-binding callback must still own its lease when it reaches the main thread. */
    static boolean runHostedCommand(SharedPreferences prefs,boolean closing,BooleanSupplier hostAllowed,Runnable activate){
        synchronized(ConfigServer.CONFIG_LOCK){
            if(closing||!prefs.getBoolean("serverEnabled",false)||!hostAllowed.getAsBoolean())return false;
            activate.run();return true;
        }
    }
    final class HostBinder extends Binder {
        void ensureHosted(BooleanSupplier hostAllowed){main.post(()->{
            if(destroyed)return;
            runHostedCommand(preferences(),closing,hostAllowed,()->{cancelRecovery();recovery="";activateBackend();});
        });}
        boolean isAliveHost(){return !destroyed;}
        boolean isEstablishedHost(){return !destroyed&&!closing&&serverRunning;}
    }
    public static void idleError(String message){
        try{snapshot=new JSONObject().put("running",false).put("serverRunning",false).put("state",message).toString();}catch(Exception ignored){}
    }
    static boolean mayRecover(Context context){
        SharedPreferences preferences=context.getSharedPreferences(PREFS,MODE_PRIVATE);
        return RecoveryPolicy.mayRecover(preferences.getBoolean("serverEnabled",false),preferences.getBoolean("autoStart",true));
    }
    private SharedPreferences preferences(){return getSharedPreferences(PREFS,MODE_PRIVATE);}
    private void persistFlags(Boolean enabled,Boolean serverEnabled){
        synchronized(ConfigServer.CONFIG_LOCK){
            SharedPreferences.Editor edit=preferences().edit();
            if(enabled!=null)edit.putBoolean("enabled",enabled);
            if(serverEnabled!=null)edit.putBoolean("serverEnabled",serverEnabled);
            preferenceError=edit.commit()?"":"开关未能保存到磁盘，设备重启后的恢复状态无法保证";
        }
        if(!preferenceError.isEmpty())Log.e(TAG,preferenceError);
    }
    @Override public IBinder onBind(Intent intent){return hostBinder;}
    @Override public void onCreate(){
        super.onCreate();liveInstance=this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"XH光流",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&(ACTION_APPLY.equals(intent.getAction())||intent.hasExtra(EXTRA_EXPECTED_CONFIG)))
            return handleConfigurationCommand(intent.getAction(),intent.getStringExtra(EXTRA_EXPECTED_CONFIG));
        return handleCommand(intent==null?ACTION_RECOVER:intent.getAction());
    }
    /** Recheck on the main thread: queued applies cannot revive a stopped sync or load a rival save. */
    private int handleConfigurationCommand(String action,String expectedFingerprint){
        if(destroyed||(!ACTION_APPLY.equals(action)&&!ACTION_START.equals(action)))return START_NOT_STICKY;
        boolean applyOnly=ACTION_APPLY.equals(action);
        try{
            SharedPreferences prefs=preferences();
            // A request can outlive the HTTP worker's original service instance. Prepare the
            // foreground host, server, and display monitor before applying on a new instance.
            boolean executed=runConfigurationCommand(prefs,expectedFingerprint,applyOnly,closing,
                ()->{if(applyOnly)prepareBackend(false);},
                ()->{if(applyOnly)applySavedConfiguration();else handleCommand(ACTION_START);});
            if(executed)return START_STICKY;
            publish();return prefs.getBoolean("serverEnabled",false)?START_STICKY:START_NOT_STICKY;
        }catch(Exception failure){
            Log.w(TAG,"Conditional configuration request was not applied",failure);publish();return START_NOT_STICKY;
        }
    }
    private int handleCommand(String action){
        if(destroyed)return START_NOT_STICKY;
        if(ACTION_SHUTDOWN.equals(action)){
            closing=true;persistFlags(false,false);cancelRecovery();
            closeResources("同步与配置后台均已关闭");
            GuardService.releaseHost();
            RevivalService.releaseHost();
            publish();stopSelf();
            return START_NOT_STICKY;
        }
        boolean restoring=ACTION_RECOVER.equals(action);
        if(restoring&&!mayRecover(this)){
            if(!serverRunning&&!GuardService.isConnected()&&!RevivalService.isConnected())stopSelf();
            return START_NOT_STICKY;
        }
        if(!restoring&&!ACTION_CONFIG.equals(action)&&!ACTION_START.equals(action)&&!ACTION_STOP.equals(action))return START_NOT_STICKY;
        prepareBackend(!restoring&&(ACTION_CONFIG.equals(action)||ACTION_START.equals(action)));
        if(ACTION_STOP.equals(action)){
            persistFlags(false,null);cancelPower();suspendedReason="";stopSession("同步已停止；配置后台继续运行");sourceOwned=false;
        }else if(ACTION_START.equals(action)){
            persistFlags(true,null);applySavedConfiguration();
        }else resumeSavedSession();
        publish();updateNotification();
        return START_STICKY;
    }
    /** Hosting is independent of synchronization intent; this never writes enabled. */
    private void prepareBackend(boolean manualBind){
        closing=false;persistFlags(null,true);cancelRecovery();recovery="";
        if(manualBind){GuardService.requestManualBind(this);RevivalService.requestManualBind(this);}
        ensureForeground();ensureServer();ensureEnvironment();
    }
    private void activateBackend(){ensureForeground();ensureServer();ensureEnvironment();resumeSavedSession();publish();updateNotification();}
    private void ensureEnvironment(){
        if(environmentMonitor!=null)return;
        if(powerExecutor==null||powerExecutor.isShutdown())powerExecutor=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"TVWLED-power"));
        DisplayEnvironment next=new DisplayEnvironment(this,main,value->{
            environment=value;reconcile();publish();updateNotification();
        });
        try{next.start();environmentMonitor=next;}
        catch(RuntimeException failure){next.close();serverError="显示状态监测启动失败："+failure.getMessage();Log.w(TAG,serverError,failure);}
    }
    private void resumeSavedSession(){
        if(preferences().getBoolean("enabled",false)){
            if(!configurationLoaded)applySavedConfiguration();else reconcile();
        }else if(session==null){suspendedReason="";state="配置后台运行中；同步尚未开启";}
    }
    private void applySavedConfiguration(){
        configurationGeneration++;configurationLoaded=true;initializationFailed=false;sleepHandled=false;sourceExcludedHandled=false;
        cancelPower();outputAllowed=false;stopSession("正在读取布局");sourceOwned=false;activeConfig=null;
        try{
            LayoutConfig config=new LayoutConfig(preferences().getString("layout",""));
            activeConfig=config;
            if(!config.address.equals(wakeAddress)){wakeAddress="";restorePowerReason="";}
            error="";reconcile();
        }catch(Exception failure){
            suspendedReason="config_invalid";error="无法开始同步："+failure.getMessage();state="请先通过配置网页保存有效布局";Log.w(TAG,error);
        }
        publish();updateNotification();
    }
    /** Runs on the service main thread. Suspension never changes user intent or reloads saved edits. */
    private void reconcile(){
        if(destroyed||closing)return;
        if(!preferences().getBoolean("enabled",false)){
            cancelPower();suspendedReason="";if(session!=null)stopSession("同步已停止；配置后台继续运行");return;
        }
        LayoutConfig config=activeConfig;
        if(config==null){outputAllowed=false;suspendedReason="config_invalid";return;}
        DisplayEnvironment.Snapshot display=environment;
        if(display==null){suspend("initializing","正在确认显示状态");return;}
        String sourceReason=sourceBlockReason(config,display);
        boolean sourceExcluded="input_unknown".equals(sourceReason)||"input_not_allowed".equals(sourceReason);
        // End each condition cycle even during an indeterminate display transition. A canceled
        // attempt must be eligible again if that condition genuinely becomes true later.
        if(!sourceExcluded)sourceExcludedHandled=false;
        if(!display.sleeping)sleepHandled=false;
        if(display.sleeping){
            initializationFailed=false;suspend("display_sleep","屏幕休眠，已暂停采样和输出");
            if(sourceOffRequested(config,sourceReason)){
                sourceOwned=false;handleSourceExcludedPower(config);return;
            }
            if(config.sourceFilterEnabled&&display.inputKnown&&!sourceEligible(config,display))sourceOwned=false;
            if(config.sleepOff&&sleepPowerEligible(config,display)){
                if(!sleepHandled){sleepHandled=true;cancelPower();recordPowerOwnership(config,"sleep");requestPower(config,false,"sleep");}
                PowerOperation operation=powerOperation;
                if(operation!=null&&operation.result!=null&&!operation.result.success){error=operation.result.error;state=operation.pending?"屏幕休眠，正在重试 WLED 关灯":"屏幕休眠，WLED 关灯尚未确认";}
            }else if(powerOperation!=null&&powerOperation.pending)cancelPower();
            return;
        }
        if(!display.awake){
            initializationFailed=false;suspend("initializing","显示状态未确认，已暂停输出");
            if(sourceOffRequested(config,sourceReason)){sourceOwned=false;handleSourceExcludedPower(config);}
            else cancelPower();
            return;
        }
        if(!sourceReason.isEmpty()){
            initializationFailed=false;
            String message="input_unknown".equals(sourceReason)?"当前选中接口未知，已暂停输出":"input_not_allowed".equals(sourceReason)?"当前选中接口不在同步范围，已暂停输出":"接口筛选配置无效";
            suspend(sourceReason,message);sourceOwned=false;
            if(sourceOffRequested(config,sourceReason))handleSourceExcludedPower(config);else cancelPower();
            return;
        }
        if(config.address.equals(wakeAddress)){
            PowerOperation operation=powerOperation;
            if(operation==null||!operation.on||operation.config!=config){
                cancelPower();suspend("wled_power_pending","正在恢复 WLED 并回读电源状态");requestPower(config,true,restorePowerReason);
            }else if(operation.pending)suspend("wled_power_pending","正在恢复 WLED 并回读电源状态");
            else if(operation.result!=null&&!operation.result.success){suspend("wled_power_error","WLED 电源尚未确认，已暂停输出");error=operation.result.error;}
            return;
        }
        if(powerOperation!=null&&powerOperation.pending)cancelPower();
        if(initializationFailed)return;
        if(session==null)startSession(config);
    }
    static String sourceBlockReason(LayoutConfig config,DisplayEnvironment.Snapshot display){
        if(!config.sourceFilterEnabled)return "";
        if(display==null||display.supported.length==0||!display.inputKnown)return "input_unknown";
        try{ConfigServer.validateSourceFilter(config,display.supported);}catch(Exception invalid){return "config_invalid";}
        return config.allows(display.inputId)?"":"input_not_allowed";
    }
    static boolean sourceOffRequested(LayoutConfig config,String reason){
        return config.sourceFilterEnabled&&config.sourceOffWhenExcluded&&("input_unknown".equals(reason)||"input_not_allowed".equals(reason));
    }
    private void recordPowerOwnership(LayoutConfig config,String reason){wakeAddress=config.address;restorePowerReason=reason;}
    private void handleSourceExcludedPower(LayoutConfig config){
        if(!sourceExcludedHandled){
            sourceExcludedHandled=true;cancelPower();recordPowerOwnership(config,"source_excluded");requestPower(config,false,"source_excluded");
        }
        PowerOperation operation=powerOperation;
        if(operation!=null&&"source_excluded".equals(operation.reason)&&!operation.on){
            if(operation.pending)state="接口条件不满足，正在确认 WLED 关灯";
            else if(operation.result!=null&&operation.result.success)state="接口条件不满足，已确认 WLED 关灯";
            else if(operation.result!=null){state="接口条件不满足，WLED 关灯尚未确认";error=operation.result.error;}
        }
    }
    private void suspend(String reason,String message){
        outputAllowed=false;suspendedReason=reason;stopSession(message);
    }
    private boolean sourceEligible(LayoutConfig config,DisplayEnvironment.Snapshot display){return sourceBlockReason(config,display).isEmpty();}
    private boolean sleepPowerEligible(LayoutConfig config,DisplayEnvironment.Snapshot display){
        // Filtering transfers ownership only after actual output. A sleep-time unknown source may
        // retain that ownership; a confirmed different source never does.
        return !config.sourceFilterEnabled||(sourceOwned&&(!display.inputKnown||sourceEligible(config,display)));
    }
    private boolean powerIsCurrent(PowerOperation operation){
        if(destroyed||closing||powerOperation!=operation||activeConfig!=operation.config||configurationGeneration!=operation.generation||powerSerial!=operation.serial||!preferences().getBoolean("enabled",false))return false;
        DisplayEnvironment.Snapshot display=environment;if(display==null)return false;
        if(!operation.on){
            if("source_excluded".equals(operation.reason)){
                String reason=sourceBlockReason(operation.config,display);
                return sourceOffRequested(operation.config,reason);
            }
            return "sleep".equals(operation.reason)&&operation.config.sleepOff&&display.sleeping&&sleepPowerEligible(operation.config,display);
        }
        return operation.config.address.equals(wakeAddress)&&display.awake&&sourceEligible(operation.config,display);
    }
    private void requestPower(LayoutConfig config,boolean on,String reason){
        if(powerExecutor==null||powerExecutor.isShutdown())return;
        final PowerOperation operation=new PowerOperation(config,configurationGeneration,++powerSerial,on,reason);powerOperation=operation;
        executePower(operation);
    }
    private void executePower(PowerOperation operation){
        if(powerExecutor==null||powerExecutor.isShutdown())return;
        final WledPowerClient client=operation.client;
        powerExecutor.execute(()->{
            if(!powerIsCurrent(operation)){client.close();return;}
            WledPowerClient.Result result=client.setPower(operation.config.address,operation.on);client.close();
            main.post(()->{
                if(!powerIsCurrent(operation))return;
                operation.result=result;operation.retrying=false;
                int maximum=operation.on?3:2;
                if(!result.success&&operation.attempt<maximum&&powerExecutor!=null&&!powerExecutor.isShutdown()){
                    operation.retrying=true;operation.pending=true;
                    long delay=operation.on&&operation.attempt>=2?5:2;
                    powerExecutor.schedule(()->main.post(()->{
                        if(!powerIsCurrent(operation))return;
                        operation.attempt++;operation.retrying=false;operation.client=new WledPowerClient();executePower(operation);publish();
                    }),delay,TimeUnit.SECONDS);
                }else{
                    operation.pending=false;
                    if(operation.on&&result.success){wakeAddress="";restorePowerReason="";}
                }
                reconcile();publish();updateNotification();
            });
        });
    }
    private void cancelPower(){
        powerSerial++;PowerOperation previous=powerOperation;powerOperation=null;
        if(previous!=null){
            previous.client.cancel(); // Invalidate immediately; socket cancellation stays off the UI thread.
            if(previous.pending){Thread cancel=new Thread(previous.client::close,"TVWLED-power-cancel");cancel.setDaemon(true);cancel.start();}
        }
    }
    private int registeredForegroundType(){
        if(Build.VERSION.SDK_INT<29)return -1;
        try{return getForegroundServiceType();}catch(RuntimeException failure){return -1;}
    }
    private void ensureForeground(){
        int actual=registeredForegroundType();
        if(actual>0||(actual<0&&foreground))return;
        try{
            Notification notice=notification();
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTICE,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTICE,notice);
            foreground=true;
            foregroundError="";
            if(registeredForegroundType()==0)foregroundError="电视固件未登记前台服务，后台运行可能受限";
        }catch(RuntimeException failure){
            foreground=false;
            foregroundError="前台服务请求未获系统接受："+failure.getMessage();
            Log.w(TAG,foregroundError,failure);
        }
    }
    private void ensureServer(){
        if(serverRunning)return;
        try{
            if(server!=null)server.close();
            server=new ConfigServer(this);
            server.start();
            serverRunning=true;
            serverError="";
        }catch(Exception failure){
            if(server!=null)server.close();
            server=null;
            serverError="配置后台启动失败："+failure.getMessage();
            Log.e(TAG,"configuration server start failed",failure);
        }
        publish();
    }
    private Notification notification(){
        boolean syncing=preferences().getBoolean("enabled",false);
        Session active=session;long now=SystemClock.elapsedRealtime();
        boolean running=syncing&&active!=null&&active.isCurrent()&&active.valid&&now-active.lastValid<=1200&&active.sent.get()>0&&now-active.lastSent<=1200;
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,2,new Intent(this,AmbientService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String title=syncing?(running?"XH光流 · 正在输出":"XH光流 · 已开启，暂停输出"):"XH光流 · 已关闭";
        String output=syncing?(running?"灯光正在跟随画面":state):"总开关已关闭，网页配置仍可使用";
        String protection=RevivalService.isConnected()?"系统恢复保护已连接":GuardService.isConnected()?"后台守护已连接":registeredForegroundType()>0?"系统前台服务已登记":"后台保护受限";
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(output+" · "+protection+" · 点此显示配置二维码")
            .setContentIntent(open).setOngoing(true);
        builder.addAction(new Notification.Action.Builder(syncing?android.R.drawable.ic_media_pause:android.R.drawable.ic_menu_view,syncing?"关闭 XH光流":"打开 XH光流",syncing?pause:open).build());
        return builder.build();
    }
    private void updateNotification(){
        if((foreground||serverRunning)&&!destroyed&&!closing)getSystemService(NotificationManager.class).notify(NOTICE,notification());
    }
    private void startSession(LayoutConfig config){
        synchronized(lifecycleLock){
            if(destroyed||closing||activeConfig!=config)return;
            outputAllowed=true;suspendedReason="initializing";
            Session next=new Session(config);session=next;lastSession=next;
            error="";state="正在连接 WLED";publish();next.sampler.execute(next::initialize);
        }
        updateNotification();
    }
    private void stopSession(String message){
        synchronized(lifecycleLock){
            Session previous=session;
            session=null;
            if(previous!=null)previous.close();
            state=message;
            error="";
            publish();
        }
        updateNotification();
    }
    private void failed(Session owner,String message,Exception failure){
        main.post(()->{
            synchronized(lifecycleLock){
                if(!owner.isCurrent())return;
                outputAllowed=false;session=null;owner.close();initializationFailed=true;
                suspendedReason="capture_error";state=message;error=failure.getClass().getSimpleName()+": "+failure.getMessage();publish();
            }
            updateNotification();
        });
    }
    private void publish(){
        try{
            Session active=session, details=active==null?lastSession:active;
            int foregroundType=registeredForegroundType();boolean guardConnected=GuardService.isConnected(),guardAuthorized=GuardService.isAuthorized(this);
            boolean revivalConnected=RevivalService.isConnected(),revivalAuthorized=RevivalService.isAuthorized(this);
            String keepAliveMode=revivalConnected?"revival":guardConnected?"guard":foregroundType>0?"foreground":"limited";
            String warning=!serverRunning?"配置后台未运行":revivalConnected?(preferences().getBoolean("autoStart",true)?"系统恢复保护已连接；由系统决定进程结束后的重新绑定，不保证手动强行停止后恢复":"系统恢复保护已连接；开机与自动恢复已关闭，仅维持本次手动会话"):revivalAuthorized?"系统恢复保护已授权但尚未连接，当前仍可能被系统结束":guardConnected?"后台守护已连接；整个进程被清理后仍可能需要手动重连":guardAuthorized?"后台守护已授权但未连接，请在电视“后台保活设置”中重新开启守护":foregroundType>0?"系统已登记前台服务；强行停止后仍需手动开启":"当前电视固件未登记前台服务，离开应用后可能停止；可选择手动启用系统恢复保护";
            boolean fresh=active!=null&&active.isCurrent()&&active.valid&&SystemClock.elapsedRealtime()-active.lastValid<=1200;
            JSONObject status=new JSONObject().put("running",fresh&&active.sent.get()>0&&SystemClock.elapsedRealtime()-active.lastSent<=1200).put("serverRunning",serverRunning).put("suspendedReason",suspendedReason)
                .put("foregroundRegistered",foregroundType>0).put("foregroundStateKnown",foregroundType>=0).put("foregroundType",foregroundType)
                .put("guardConnected",guardConnected).put("guardAuthorized",guardAuthorized).put("keepAliveMode",keepAliveMode).put("keepAliveWarning",warning).put("foregroundError",foregroundError)
                .put("revivalAuthorized",revivalAuthorized).put("revivalConnected",revivalConnected)
                .put("state",state).put("error",error).put("serverError",serverError).put("preferenceError",preferenceError)
                .put("enabled",preferences().getBoolean("enabled",false))
                .put("autoStart",preferences().getBoolean("autoStart",true)).put("recovery",recovery);
            DisplayEnvironment.Snapshot display=environment;JSONArray supported=new JSONArray();
            if(display!=null)for(DisplayEnvironment.Source source:display.supported)supported.put(new JSONObject().put("id",source.id).put("label",source.label));
            status.put("displayInteractive",display!=null&&display.interactive).put("displayState",display==null?"unknown":display.displayState)
                .put("displayStateCode",display==null?0:display.displayCode).put("displayError",display==null?"":display.error)
                .put("inputSource",new JSONObject().put("id",display==null||display.inputId==null?JSONObject.NULL:display.inputId).put("label",display==null?"未知":display.inputLabel).put("known",display!=null&&display.inputKnown))
                .put("inputsSupported",supported);
            PowerOperation operation=powerOperation;
            if(operation==null)status.put("wledPower",JSONObject.NULL);
            else{
                WledPowerClient.Result result=operation.result;
                status.put("wledPower",new JSONObject().put("address",operation.config.address).put("requestedOn",operation.on).put("reason",operation.reason).put("pending",operation.pending)
                    .put("attempt",operation.attempt).put("maxAttempts",operation.on?3:2).put("retrying",operation.retrying).put("success",result!=null&&result.success).put("readbackOn",result==null||result.readbackOn==null?JSONObject.NULL:result.readbackOn)
                    .put("readbackLive",result==null||result.readbackLive==null?JSONObject.NULL:result.readbackLive).put("error",result==null?"":result.error).put("durationMs",result==null?0:result.durationMs));
            }
            LayoutConfig applied=activeConfig;
            String savedFingerprint=savedConfigFingerprint(),appliedFingerprint=applied==null?"":applied.fingerprint;
            status.put("savedConfigFingerprint",savedFingerprint).put("appliedConfigFingerprint",appliedFingerprint)
                .put("pendingApply",!savedFingerprint.isEmpty()&&!savedFingerprint.equals(appliedFingerprint)).put("configFingerprintError",fingerprintError);
            if(applied!=null)status.put("address",applied.address).put("ledCount",applied.ledCount).put("sleepOff",applied.sleepOff).put("sourceFilterEnabled",applied.sourceFilterEnabled).put("sourceOffWhenExcluded",applied.sourceOffWhenExcluded).put("samplingQuality",applied.samplingQuality).put("configuredSamplePoints",applied.pointsPerEdge*4);
            if(details!=null){
                status.put("samples",details.samples).put("sentFrames",details.sent.get()).put("captureMs",details.captureMs)
                    .put("samplePoints",details.coordinates==null?0:details.coordinates.length)
                    .put("fresh",fresh)
                    .put("softeningEnabled",details.config.softeningEnabled).put("softeningStrength",details.config.softeningStrength)
                    .put("colorCorrectionActive",details.correction!=null&&!details.correction.isIdentity())
                    .put("samplingInset",details.config.samplingInset).put("blackBarsEnabled",details.config.blackBarsEnabled).put("blackBarProbeMs",details.blackBarProbeMs).put("blackBarError",details.blackBarError);
            }else status.put("fresh",false).put("samples",0).put("sentFrames",0).put("samplePoints",0);
            snapshot=status.toString();
            if(details!=null)details.lastPublish=SystemClock.elapsedRealtime();
        }catch(Exception ignored){}
    }
    private String savedConfigFingerprint(){
        String raw=preferences().getString("layout","");
        synchronized(fingerprintLock){
            if(!raw.equals(cachedSavedRaw)){
                cachedSavedRaw=raw;fingerprintError="";
                try{cachedSavedFingerprint=raw.isEmpty()?"":ConfigFingerprint.of(raw);}
                catch(Exception invalid){cachedSavedFingerprint="";fingerprintError="已保存配置指纹无法读取："+invalid.getMessage();}
            }
            return cachedSavedFingerprint;
        }
    }
    private PendingIntent recoveryIntent(){
        return PendingIntent.getBroadcast(this,91,new Intent(this,RecoveryReceiver.class).setAction(ACTION_RECOVER),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void cancelRecovery(){getSystemService(AlarmManager.class).cancel(recoveryIntent());}
    private void scheduleRecovery(){
        if(closing||!mayRecover(this))return;
        try{
            // One inexact fallback only; the system may delay or reject background restart.
            getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+15000,recoveryIntent());
            recovery="已安排一次后台恢复尝试，是否唤醒由系统决定";
            publish();
        }catch(RuntimeException failure){recovery="后台恢复安排失败："+failure.getMessage();publish();Log.w(TAG,recovery);}
    }
    @Override public void onTaskRemoved(Intent rootIntent){scheduleRecovery();super.onTaskRemoved(rootIntent);}
    private void closeResources(String message){
        configurationGeneration++;cancelPower();outputAllowed=false;
        if(environmentMonitor!=null)environmentMonitor.close();environmentMonitor=null;environment=null;
        if(powerExecutor!=null)powerExecutor.shutdownNow();powerExecutor=null;
        activeConfig=null;configurationLoaded=false;wakeAddress="";restorePowerReason="";suspendedReason="";sourceExcludedHandled=false;sleepHandled=false;
        synchronized(lifecycleLock){
            Session previous=session;session=null;if(previous!=null)previous.close();sourceOwned=false;
            if(server!=null)server.close();server=null;serverRunning=false;
        }
        state=message;
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;
        getSystemService(NotificationManager.class).cancel(NOTICE);
        publish();
    }
    @Override public void onDestroy(){
        destroyed=true;
        closeResources(closing?"同步与配置后台均已关闭":mayRecover(this)?"后台服务已结束；等待系统恢复":"后台服务已结束；自动恢复已关闭");
        if(liveInstance==this)liveInstance=null;
        if(!closing)scheduleRecovery();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();Log.i(TAG,"service stopped; no final black frame sent");
    }
    private static final class PreviewFrame {
        final String json;final long capturedAt;
        PreviewFrame(String json,long capturedAt){this.json=json;this.capturedAt=capturedAt;}
    }
    private final class Session {
        final LayoutConfig config;
        final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"TVWLED-color"));
        final ScheduledExecutorService sender=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"TVWLED-ddp"));
        final AtomicLong sent=new AtomicLong();
        volatile boolean active=true, valid;
        volatile long lastValid,lastSent,captureMs,samples,lastPublish;
        volatile int[] target;
        int[][] coordinates;
        ColorClient colors;
        DatagramSocket socket;
        InetAddress address;
        EdgeColorEngine engine;
        ColorCorrection correction;
        BlackBarDetector blackBars;
        int panelWidth,panelHeight;
        long nextBlackBarProbe;
        volatile long blackBarProbeMs;
        volatile String blackBarError="";
        volatile PreviewFrame previewFrame;
        int sequence=1;
        Session(LayoutConfig layout){config=layout;}
        boolean isCurrent(){return active&&outputAllowed&&session==this&&activeConfig==config&&!destroyed&&!closing;}
        void close(){active=false;valid=false;sampler.shutdownNow();sender.shutdownNow();if(socket!=null)socket.close();}
        void initialize(){
            try{
                if(!isCurrent())return;
                JSONObject info=WledInfo.read(config.address);
                if(!isCurrent())return;
                if(info.getInt("ledCount")!=config.ledCount)throw new IllegalStateException("WLED 灯数与布局不一致，请重新核对");
                ColorClient client=new ColorClient();
                if(!client.supported())throw new IllegalStateException("当前设备不支持原厂取色");
                int[] size=client.resolution();
                EdgeColorEngine nextEngine=config.newEngine();
                ColorCorrection nextCorrection=config.newCorrection();
                BlackBarDetector nextDetector=new BlackBarDetector();
                int[][] points=EdgeColorEngine.coordinates(EdgeColorEngine.plan(EdgeColorEngine.Region.FULL,config.samplingInset,config.pointsPerEdge),size[0],size[1]);
                InetAddress resolved=LayoutConfig.validateAddress(config.address);
                synchronized(lifecycleLock){
                    if(!isCurrent())return;
                    colors=client;coordinates=points;address=resolved;socket=new DatagramSocket();
                    panelWidth=size[0];panelHeight=size[1];engine=nextEngine;correction=nextCorrection;blackBars=nextDetector;
                    state="等待屏幕取色";publish();
                    Log.i(TAG,"sync uid="+android.os.Process.myUid()+" panel="+size[0]+"x"+size[1]+" points="+coordinates.length+" leds="+config.ledCount);
                    sampler.schedule(this::sample,0,TimeUnit.MILLISECONDS);
                    sender.scheduleAtFixedRate(this::send,0,33,TimeUnit.MILLISECONDS);
                }
            }catch(Exception failure){if(isCurrent()){Log.e(TAG,"initialize failed",failure);failed(this,"连接或取色初始化失败；配置后台继续运行",failure);}}
        }
        void sample(){
            if(!isCurrent())return;
            long started=SystemClock.elapsedRealtime();
            try{
                if(!getSystemService(PowerManager.class).isInteractive()){
                    synchronized(lifecycleLock){if(isCurrent()){valid=false;suspendedReason="display_sleep";state="屏幕休眠，已暂停输出";}}
                    return;
                }
                EdgeColorEngine.Region region=config.blackBarsEnabled?blackBars.region():EdgeColorEngine.Region.FULL;
                EdgeColorEngine.Point[] plan=EdgeColorEngine.plan(region,config.samplingInset,config.pointsPerEdge);
                int[][] points=EdgeColorEngine.coordinates(plan,panelWidth,panelHeight);
                int[][] sampled=colors.sample(points);
                int[] next=engine.map(sampled,correction.isIdentity()?config.brightness:1,config.softeningEnabled,config.softeningStrength);
                if(!correction.isIdentity())next=correction.apply(next,config.brightness);
                JSONObject frame=new JSONObject().put("source","screen-edges").put("panelWidth",panelWidth).put("panelHeight",panelHeight).put("samplingInset",config.samplingInset).put("samplingQuality",config.samplingQuality);
                frame.put("activeRegion",new JSONObject().put("left",region.left).put("top",region.top).put("right",region.right).put("bottom",region.bottom));
                JSONArray previewPoints=new JSONArray();
                for(int i=0;i<plan.length;i++)previewPoints.put(new JSONObject().put("edge",plan[i].edge).put("position",plan[i].position).put("x",(double)points[i][0]/(panelWidth-1)).put("y",(double)points[i][1]/(panelHeight-1)).put("color",String.format(java.util.Locale.ROOT,"#%02x%02x%02x",sampled[i][0],sampled[i][1],sampled[i][2])));
                frame.put("points",previewPoints);
                synchronized(lifecycleLock){
                    if(!isCurrent())return;
                    coordinates=points;target=next;lastValid=SystemClock.elapsedRealtime();captureMs=lastValid-started;samples++;valid=true;previewFrame=new PreviewFrame(frame.toString(),lastValid);
                    error="";suspendedReason="";state="正在采集并发送 · UDP 无回执";
                }
                if(config.blackBarsEnabled&&SystemClock.elapsedRealtime()>=nextBlackBarProbe&&isCurrent()){
                    nextBlackBarProbe=SystemClock.elapsedRealtime()+750;
                    long probeStarted=SystemClock.elapsedRealtime();
                    try{
                        BlackBarDetector.Probe probe=blackBars.nextProbe(panelWidth,panelHeight);
                        int[][] probeColors=colors.sample(probe.coordinates);
                        if(isCurrent())blackBars.observe(probe,probeColors);
                        blackBarError="";
                    }catch(Exception failure){if(isCurrent()){colors.reset();blackBarError="黑边探测暂不可用："+failure.getMessage();}}
                    finally{blackBarProbeMs=SystemClock.elapsedRealtime()-probeStarted;}
                }
            }catch(Exception failure){
                synchronized(lifecycleLock){
                    if(isCurrent()){
                        valid=false;colors.reset();error=failure.getClass().getSimpleName()+": "+failure.getMessage();
                        suspendedReason="capture_error";state="取色失败，已暂停输出";Log.w(TAG,error);
                    }
                }
            }finally{
                synchronized(lifecycleLock){
                    if(isCurrent()){
                        publish();
                        sampler.schedule(this::sample,Math.max(30,180-(SystemClock.elapsedRealtime()-started)),TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
        void send(){
            if(!isCurrent()||!valid||SystemClock.elapsedRealtime()-lastValid>1200)return;
            int[] goal=target;if(goal==null)return;
            try{
                int[] rgb=engine.smooth(goal,SystemClock.elapsedRealtime(),config.softeningEnabled,config.softeningStrength);
                byte[] data=DdpPacket.encode(rgb,sequence);sequence=sequence==15?1:sequence+1;
                synchronized(lifecycleLock){
                    if(!isCurrent()||!valid||SystemClock.elapsedRealtime()-lastValid>1200)return;
                    socket.send(new DatagramPacket(data,data.length,address,DdpPacket.PORT));sent.incrementAndGet();lastSent=SystemClock.elapsedRealtime();sourceOwned=true;
                    if(SystemClock.elapsedRealtime()-lastPublish>1000)publish();
                }
            }catch(Exception failure){
                synchronized(lifecycleLock){if(isCurrent()){error="发送失败："+failure.getMessage();state="WLED 发送异常";publish();}}
            }
        }
    }
}
