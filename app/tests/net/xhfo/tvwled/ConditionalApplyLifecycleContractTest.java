package net.xhfo.tvwled;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** Exercises the real conditional command coordinator without starting Android services. */
public final class ConditionalApplyLifecycleContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Store {
        String raw;boolean enabled,serverEnabled;
        Store(String value,boolean on){raw=value;enabled=on;}
        final SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if(!Thread.holdsLock(ConfigServer.CONFIG_LOCK))throw new AssertionError("Conditional lifecycle read escaped CONFIG_LOCK");
            if(method.getName().equals("getString"))return "layout".equals(args[0])?raw:args[1];
            if(method.getName().equals("getBoolean"))return "enabled".equals(args[0])?enabled:"serverEnabled".equals(args[0])?serverEnabled:args[1];
            throw new UnsupportedOperationException(method.getName());
        });
    }
    private static boolean execute(Store store,String expected,boolean applyOnly,boolean closing,Runnable initialize,Runnable command) throws Exception {
        return AmbientService.runConfigurationCommand(store.prefs,expected,applyOnly,closing,initialize,command);
    }
    public static void main(String[] args) throws Exception {
        String raw=new JSONObject().put("test","original").toString(),rival=new JSONObject().put("test","rival").toString(),fingerprint=ConfigFingerprint.of(raw);
        Store cold=new Store(raw,true);List<String> events=new ArrayList<>();
        check(execute(cold,fingerprint,true,false,()->{check(Thread.holdsLock(ConfigServer.CONFIG_LOCK),"Cold initialization holds the configuration lock");events.add("foreground");events.add("server");cold.serverEnabled=true;events.add("environment");},()->{events.add("apply");check(cold.enabled,"Automatic apply keeps the existing enabled intent");}),"An enabled current cold apply is accepted");
        check(events.equals(Arrays.asList("foreground","server","environment","apply")),"Cold host initialization completes before configuration application");
        check(cold.enabled&&cold.serverEnabled,"Cold apply leaves synchronization enabled and its configuration backend hosted");

        for(boolean closing:new boolean[]{false,true}){
            Store stopped=new Store(raw,false);events.clear();
            check(!execute(stopped,fingerprint,true,closing,()->events.add("initialize"),()->events.add("apply")),"A stopped synchronization rejects automatic apply before initialization");
            check(events.isEmpty()&&!stopped.enabled&&!stopped.serverEnabled,"Rejected apply cannot reopen either disabled synchronization or its stopped host");
        }
        Store stale=new Store(rival,true);events.clear();
        check(!execute(stale,fingerprint,true,false,()->events.add("initialize"),()->events.add("apply")),"Stale automatic apply rejects its old fingerprint");
        check(events.isEmpty(),"Stale queued work does not initialize a host or apply a rival document");
        Store closing=new Store(raw,true);events.clear();
        check(!execute(closing,fingerprint,true,true,()->events.add("initialize"),()->events.add("apply"))&&events.isEmpty(),"Closing hosts reject even a matching automatic apply");

        Store interrupted=new Store(raw,true);events.clear();
        check(!execute(interrupted,fingerprint,true,false,()->{events.add("initialize");interrupted.enabled=false;},()->events.add("apply")),"A synchronous stop during initialization is rechecked before apply");
        check(events.equals(Arrays.asList("initialize"))&&!interrupted.enabled,"Preparation cannot turn an interrupted automatic apply into a start");
        Store replaced=new Store(raw,true);events.clear();
        check(!execute(replaced,fingerprint,true,false,()->replaced.raw=rival,()->events.add("apply"))&&events.isEmpty(),"A target replaced by a synchronous callback is rechecked before apply");

        Store failed=new Store(raw,true);events.clear();
        try{execute(failed,fingerprint,true,false,()->{throw new IllegalStateException("initialization rejected");},()->events.add("apply"));throw new AssertionError("Initialization failure was hidden");}
        catch(IllegalStateException expected){check(events.isEmpty()&&failed.enabled,"Failed initialization never reaches apply or changes intent");}
        Store manual=new Store(raw,false);events.clear();
        check(execute(manual,fingerprint,false,true,()->events.add("initialize"),()->{events.add("start");manual.enabled=true;}),"An explicit conditional start may enable its matching configuration");
        check(manual.enabled&&events.equals(Arrays.asList("initialize","start")),"Explicit start retains its distinct intentional enable behavior");
        Store noTarget=new Store(raw,true);events.clear();
        check(!execute(noTarget,null,true,false,()->events.add("initialize"),()->events.add("apply"))&&events.isEmpty(),"An automatic apply without a target cannot initialize or execute");
        System.out.println("ConditionalApplyLifecycleContractTest PASS ("+checks+" checks, no Android service or network access)");
    }
}
