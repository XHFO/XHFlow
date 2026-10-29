package net.xhfo.tvwled;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only verification of the exact editor baseline, including concurrent real writers. */
public final class EditorSnapshotContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static LayoutConfig config(double brightness) throws Exception {
        return new LayoutConfig(new JSONObject().put("schema","xh-tv-wled-layout/v1").put("coordinateSystem","screen-front-normalized")
            .put("controller",new JSONObject().put("address","192.168.1.50").put("ledCount",1))
            .put("output",new JSONObject().put("brightness",brightness))
            .put("futureFields",new JSONObject().put("nested",new JSONArray().put("保留原始字段").put(123.456789)))
            .put("leds",new JSONArray().put(new JSONObject().put("wledIndex",0).put("edge","top")
                .put("sample",new JSONObject().put("x",.1).put("y",.05)))).toString());
    }
    private static final class Store {
        volatile String layout="";int reads;boolean requireLock;
        final SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if(method.getName().equals("getString")){
                if(requireLock&&!Thread.holdsLock(ConfigServer.CONFIG_LOCK))throw new AssertionError("Snapshot read escaped CONFIG_LOCK");
                reads++;return "layout".equals(args[0])?layout:args[1];
            }
            if(method.getName().equals("edit")){
                String[] next={layout};
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
                    if(operation.getName().equals("putString")&&"layout".equals(values[0]))next[0]=(String)values[1];
                    if(operation.getName().equals("commit")){layout=next[0];return true;}
                    if(operation.getReturnType()==SharedPreferences.Editor.class)return editor;
                    throw new UnsupportedOperationException(operation.getName());
                });
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }
    private static void verify(JSONObject snapshot) throws Exception {
        JSONObject value=snapshot.getJSONObject("configuration");
        if(!snapshot.getString("savedConfigFingerprint").equals(ConfigFingerprint.of(value)))
            throw new AssertionError("Configuration and fingerprint came from different saves");
    }
    public static void main(String[] args) throws Exception {
        Store store=new Store();store.requireLock=true;
        JSONObject empty=ConfigServer.editorSnapshot(store.prefs);
        check(empty.has("configuration")&&empty.isNull("configuration"),"Empty device returns an explicit null configuration");
        check(empty.getString("savedConfigFingerprint").isEmpty(),"Empty device returns the same empty baseline fingerprint as status");
        check(store.reads==1,"Each snapshot reads saved configuration only once");
        LayoutConfig first=config(.3),second=config(.8);
        ConfigServer.saveConfiguration(store.prefs,first,false,"none");
        int readsBefore=store.reads;JSONObject original=ConfigServer.editorSnapshot(store.prefs);verify(original);
        check(store.reads==readsBefore+1,"Configured snapshot derives both fields from one raw read");
        check(original.length()==2,"Editor baseline has exactly configuration and savedConfigFingerprint fields");
        check(original.getString("savedConfigFingerprint").equals(first.fingerprint),"Snapshot uses the strict all-fields backend fingerprint");
        check(original.getJSONObject("configuration").getJSONObject("futureFields").getJSONArray("nested").getString(0).equals("保留原始字段"),"Unknown nested configuration fields survive unchanged");
        ConfigServer.saveConfiguration(store.prefs,second,true,first.fingerprint);
        check(original.getString("savedConfigFingerprint").equals(first.fingerprint),"An already returned baseline remains immutable after another save");
        check(original.getJSONObject("configuration").getJSONObject("output").getDouble("brightness")==.3,"Previously returned configuration does not alias mutable stored state");
        JSONObject latest=ConfigServer.editorSnapshot(store.prefs);verify(latest);
        check(latest.getString("savedConfigFingerprint").equals(second.fingerprint),"Next read observes the complete newer save");

        CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread writer=new Thread(()->{try{start.await();for(int i=0;i<300;i++)ConfigServer.saveConfiguration(store.prefs,(i&1)==0?first:second,true,null);}catch(Throwable error){failure.compareAndSet(null,error);}});
        Thread reader=new Thread(()->{try{start.await();for(int i=0;i<300;i++)verify(ConfigServer.editorSnapshot(store.prefs));}catch(Throwable error){failure.compareAndSet(null,error);}});
        writer.start();reader.start();start.countDown();writer.join(5000);reader.join(5000);
        check(!writer.isAlive()&&!reader.isAlive(),"Atomic snapshot and writer workers finish without deadlock");
        if(failure.get()!=null)throw new AssertionError("Concurrent editor baseline failure",failure.get());
        check(failure.get()==null,"Every concurrent baseline pairs the complete configuration with its own fingerprint");
        System.out.println("EditorSnapshotContractTest PASS ("+checks+" checks, 300 concurrent snapshots and 300 real writes)");
    }
}
