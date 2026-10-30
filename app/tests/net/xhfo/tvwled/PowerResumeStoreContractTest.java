package net.xhfo.tvwled;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;

/** Android commit failure changes the in-memory map; only another successful commit proves disk durability. */
public final class PowerResumeStoreContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Store {
        String memory="",disk="",pending;
        boolean commitResult=true;
        int commits;
        final SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(proxy,method,args)->{
            check(Thread.holdsLock(ConfigServer.CONFIG_LOCK),"Power-state writes hold the shared configuration lock");
            if("putString".equals(method.getName())){
                check(PowerResumeState.PREF_KEY.equals(args[0]),"The durable helper writes only its own power-state key");
                pending=(String)args[1];return proxy;
            }
            if("commit".equals(method.getName())){
                commits++;memory=pending; // Real SharedPreferences publishes memory before disk I/O.
                if(commitResult)disk=memory;
                return commitResult;
            }
            throw new AssertionError("Unexpected power-state editor operation: "+method.getName());
        });
        final SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            check(Thread.holdsLock(ConfigServer.CONFIG_LOCK),"Power-state reads and writes share one atomic boundary");
            if("getString".equals(method.getName())){
                check(PowerResumeState.PREF_KEY.equals(args[0]),"The helper reads only the power-state key");
                return memory;
            }
            if("edit".equals(method.getName())){pending=memory;return editor;}
            throw new AssertionError("Unexpected power-state preference operation: "+method.getName());
        });
    }
    public static void main(String[] args){
        PowerResumeState pending=PowerResumeState.empty().forAddress("192.168.1.50").afterOutput().beforeOff(PowerResumeState.SLEEP);
        Store store=new Store();store.commitResult=false;int permittedOffs=0;
        if(AmbientService.persistPowerResume(store.prefs,pending,true))permittedOffs++;
        check(permittedOffs==0&&store.commits==1,"A failed before-OFF commit grants no permission to switch off");
        check(store.memory.equals(pending.encode())&&store.disk.isEmpty(),"Fixture reproduces failed commit with a new in-memory value and unchanged disk");
        if(AmbientService.persistPowerResume(store.prefs,pending,true))permittedOffs++;
        check(permittedOffs==0&&store.commits==2&&store.disk.isEmpty(),"The next display poll must try disk again even though the in-memory value matches");
        store.commitResult=true;
        if(AmbientService.persistPowerResume(store.prefs,pending,true))permittedOffs++;
        check(permittedOffs==1&&store.commits==3&&store.disk.equals(pending.encode()),"Only a successful durable retry permits an external OFF");
        check(AmbientService.persistPowerResume(store.prefs,pending,true)&&store.commits==4,"A required durable write never relies on the readback shortcut");
        store.commitResult=false;
        check(AmbientService.persistPowerResume(store.prefs,pending,false)&&store.commits==4,"Ordinary unchanged bookkeeping may retain its explicit non-durable fast path");
        PowerResumeState replacement=pending.beforeOff(PowerResumeState.SOURCE_EXCLUDED);
        check(!AmbientService.persistPowerResume(store.prefs,replacement,false)&&store.commits==5,"A changed non-forced value still reports a failed commit");
        check(store.memory.equals(replacement.encode())&&store.disk.equals(pending.encode()),"Failed replacement again differs between memory and disk");
        check(!AmbientService.persistPowerResume(store.prefs,replacement,true)&&store.commits==6,"A subsequent before-OFF write cannot borrow an uncommitted bookkeeping value");
        store.commitResult=true;
        check(AmbientService.persistPowerResume(store.prefs,replacement,true)&&store.disk.equals(replacement.encode()),"The changed OFF reason becomes actionable only after its own durable success");
        check(AmbientService.persistPowerResume(store.prefs,PowerResumeState.empty(),true)&&store.disk.isEmpty(),"Explicit clearing can use the same durable contract");
        System.out.println("PowerResumeStoreContractTest PASS ("+checks+" checks, failed commits retain updated memory)");
    }
}
