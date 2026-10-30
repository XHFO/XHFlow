package net.xhfo.tvwled;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Recovery cannot change the master intent, outlive a revoked lease, or retry forever. */
public final class RecoveryContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static SharedPreferences store(AtomicBoolean backend,boolean enabled){
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            check(Thread.holdsLock(ConfigServer.CONFIG_LOCK),"Hosted intent is checked under the same lock as shutdown");
            if(method.getName().equals("getBoolean")){
                if("serverEnabled".equals(args[0]))return backend.get();
                if("enabled".equals(args[0]))return enabled;
                return args[1];
            }
            throw new AssertionError("Recovery must not write preferences: "+method.getName());
        });
    }
    public static void main(String[] args){
        for(boolean backend:new boolean[]{false,true})for(boolean automatic:new boolean[]{false,true})for(boolean manual:new boolean[]{false,true})for(boolean established:new boolean[]{false,true}){
            check(RecoveryPolicy.mayRecover(backend,automatic)==(backend&&automatic),"Process recovery only follows persisted backend and auto-start intent");
            check(RecoveryPolicy.mayHost(backend,automatic,manual,established)==(backend&&(automatic||manual||established)),"Manual and already-established sessions may continue without future automatic recovery");
        }
        RecoveryPolicy.BindWindow window=new RecoveryPolicy.BindWindow();
        check(window.next(true)<0,"No binding attempt exists before a lifecycle is active");
        window.begin();check(window.next(false)<0,"Denied approval does not create a binding");
        long first=window.next(true);
        check(first>=0&&window.accepts(first,true),"An authorized attempt owns a live callback lease");
        check(!window.accepts(first,false),"A queued callback is invalid as soon as approval or intent is withdrawn");
        check(window.next(true)==first&&window.next(true)==first&&window.next(true)<0,"A failure window has at most three attempts");
        window.end();check(!window.accepts(first,true)&&window.next(true)<0,"Disconnect and shutdown invalidate pending retries and callbacks");
        window.begin();long replacement=window.next(true);
        check(replacement!=first&&!window.accepts(first,true)&&window.accepts(replacement,true),"A new manual session cannot be revived by an old callback");

        for(boolean enabled:new boolean[]{false,true}){
            AtomicBoolean backend=new AtomicBoolean(true),approval=new AtomicBoolean(true);
            AtomicInteger hosts=new AtomicInteger(),syncs=new AtomicInteger();SharedPreferences prefs=store(backend,enabled);
            Runnable activate=()->{hosts.incrementAndGet();if(prefs.getBoolean("enabled",false))syncs.incrementAndGet();};
            check(AmbientService.runHostedCommand(prefs,false,approval::get,activate),"Current approved host is accepted");
            check(hosts.get()==1&&syncs.get()==(enabled?1:0),"A disabled master restores only its configuration backend");
            approval.set(false);
            check(!AmbientService.runHostedCommand(prefs,false,approval::get,activate)&&hosts.get()==1,"Revocation before queued execution prevents activation");
            approval.set(true);backend.set(false);
            check(!AmbientService.runHostedCommand(prefs,false,approval::get,activate)&&hosts.get()==1,"A closed backend cannot be resurrected by a queued binding callback");
            backend.set(true);
            check(!AmbientService.runHostedCommand(prefs,true,approval::get,activate)&&hosts.get()==1,"A closing service cannot be activated again");
        }
        AtomicBoolean backend=new AtomicBoolean(true),automatic=new AtomicBoolean(true),established=new AtomicBoolean(false);
        AtomicInteger activations=new AtomicInteger();SharedPreferences prefs=store(backend,false);
        java.util.function.BooleanSupplier mayContinue=()->RecoveryPolicy.mayHost(backend.get(),automatic.get(),false,established.get());
        Runnable establish=()->{activations.incrementAndGet();established.set(true);};
        // Binding succeeded, but its queued main-thread activation has not happened yet.
        automatic.set(false);
        check(!AmbientService.runHostedCommand(prefs,false,mayContinue,establish)&&activations.get()==0,"Disabling auto-start before cold activation cannot create an artificial existing-session authorization");
        automatic.set(true);
        check(AmbientService.runHostedCommand(prefs,false,mayContinue,establish)&&activations.get()==1,"An approved automatic recovery may first establish a real backend");
        automatic.set(false);
        check(AmbientService.runHostedCommand(prefs,false,mayContinue,establish)&&activations.get()==2,"Turning off future auto-start preserves the current system-restored backend and web connection");
        check(!RecoveryPolicy.mayRecover(backend.get(),automatic.get()),"The preserved session does not change future auto-start intent");
        established.set(false); // Process exit removes the runtime-only session evidence.
        check(!AmbientService.runHostedCommand(prefs,false,mayContinue,establish)&&activations.get()==2,"After process exit auto-start=false cannot recreate the former session");
        backend.set(false);established.set(true);
        check(!AmbientService.runHostedCommand(prefs,false,mayContinue,establish)&&activations.get()==2,"Explicit backend shutdown still wins over established-session evidence");
        System.out.println("RecoveryContractTest PASS ("+checks+" checks, no Android service or device access)");
    }
}
