package net.xhfo.tvwled;

import android.content.SharedPreferences;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses the real conditional writer with in-memory preferences; no Android process or network. */
public final class ConditionalSaveContractTest {
    private static int checks;
    private interface Work {void run() throws Exception;}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void fails(int status,Work work,String message) throws Exception {
        try{work.run();throw new AssertionError(message);}catch(HttpProtocol.Failure expected){check(expected.status==status,message);}
    }
    private static LayoutConfig config(double brightness) throws Exception {
        return new LayoutConfig(new JSONObject().put("schema","xh-tv-wled-layout/v1").put("coordinateSystem","screen-front-normalized")
            .put("controller",new JSONObject().put("address","192.168.1.50").put("ledCount",1))
            .put("output",new JSONObject().put("brightness",brightness))
            .put("leds",new JSONArray().put(new JSONObject().put("wledIndex",0).put("edge","top")
                .put("sample",new JSONObject().put("x",.1).put("y",.05)))).toString());
    }
    private static HttpProtocol.Request request(String condition) throws Exception {
        String raw="POST /api/config HTTP/1.1\r\nHost: 192.168.1.80:8768\r\nContent-Type: application/json\r\nContent-Length: 2\r\n"
            +(condition==null?"":"If-Match: "+condition+"\r\n")+"\r\n{}";
        return HttpProtocol.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String,Object> values=new HashMap<>();
        int commits,editCalls;boolean rejectCommit;
        @Override public synchronized Map<String,?> getAll(){return new HashMap<>(values);}
        @Override public synchronized String getString(String key,String fallback){Object value=values.get(key);return value instanceof String?(String)value:fallback;}
        @SuppressWarnings("unchecked") @Override public synchronized Set<String> getStringSet(String key,Set<String> fallback){Object value=values.get(key);return value instanceof Set?(Set<String>)value:fallback;}
        @Override public synchronized int getInt(String key,int fallback){Object value=values.get(key);return value instanceof Integer?(Integer)value:fallback;}
        @Override public synchronized long getLong(String key,long fallback){Object value=values.get(key);return value instanceof Long?(Long)value:fallback;}
        @Override public synchronized float getFloat(String key,float fallback){Object value=values.get(key);return value instanceof Float?(Float)value:fallback;}
        @Override public synchronized boolean getBoolean(String key,boolean fallback){Object value=values.get(key);return value instanceof Boolean?(Boolean)value:fallback;}
        @Override public synchronized boolean contains(String key){return values.containsKey(key);}
        @Override public synchronized Editor edit(){editCalls++;return new MemoryEditor();}
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        private final class MemoryEditor implements Editor {
            private final Map<String,Object> changed=new HashMap<>();private boolean clear;
            @Override public Editor putString(String key,String value){changed.put(key,value);return this;}
            @Override public Editor putStringSet(String key,Set<String> value){changed.put(key,value);return this;}
            @Override public Editor putInt(String key,int value){changed.put(key,value);return this;}
            @Override public Editor putLong(String key,long value){changed.put(key,value);return this;}
            @Override public Editor putFloat(String key,float value){changed.put(key,value);return this;}
            @Override public Editor putBoolean(String key,boolean value){changed.put(key,value);return this;}
            @Override public Editor remove(String key){changed.put(key,null);return this;}
            @Override public Editor clear(){clear=true;return this;}
            @Override public boolean commit(){synchronized(MemoryPreferences.this){
                commits++;if(rejectCommit)return false;if(clear)values.clear();
                for(Map.Entry<String,Object> entry:changed.entrySet())if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());
                return true;
            }}
            @Override public void apply(){commit();}
        }
    }
    public static void main(String[]args) throws Exception {
        LayoutConfig original=config(.3),newer=config(.6),rival=config(.9);
        check(ConfigServer.expectedFingerprint(request(null),false)==null,"Legacy client may omit If-Match");
        fails(400,()->ConfigServer.expectedFingerprint(request(null),true),"Safe apply requires an explicit target");
        check(ConfigServer.expectedFingerprint(request("none"),true).equals("none"),"First save can condition on no stored config");
        check(ConfigServer.expectedFingerprint(request(original.fingerprint),true).equals(original.fingerprint),"Bare fingerprint header accepted");
        check(ConfigServer.expectedFingerprint(request("\""+original.fingerprint+"\""),true).equals(original.fingerprint),"Quoted fingerprint header accepted");
        check(ConfigServer.expectedFingerprint(request("\"none\""),true).equals("none"),"Quoted first-save marker accepted");
        for(String invalid:new String[]{"","*","W/\""+original.fingerprint+"\"",original.fingerprint.toUpperCase(),"none, none","abc"})
            fails(400,()->ConfigServer.expectedFingerprint(request(invalid),false),"Malformed or broad preconditions are rejected");
        check(HttpProtocol.reason(409).equals("Conflict"),"Conflicts have a real HTTP 409 reason");
        MemoryPreferences prefs=new MemoryPreferences();
        check(ConfigServer.configurationFingerprint(prefs.getString("layout","")).equals("none"),"Empty store has a stable first-save precondition");
        check(ConfigServer.saveConfiguration(prefs,original,false,"none").equals(original.fingerprint),"First conditional save returns its exact fingerprint");
        check(prefs.getString("layout","").equals(original.raw)&&!prefs.getBoolean("autoStart",true),"Writer commits layout and preference together");
        int edits=prefs.editCalls,commits=prefs.commits;
        fails(409,()->ConfigServer.saveConfiguration(prefs,newer,true,"none"),"A stale initial page cannot replace a configured device");
        check(prefs.editCalls==edits&&prefs.commits==commits&&prefs.getString("layout","").equals(original.raw),"Rejected CAS never opens an editor or mutates saved state");
        ConfigServer.saveConfiguration(prefs,newer,true,original.fingerprint);
        check(prefs.getString("layout","").equals(newer.raw)&&prefs.getBoolean("autoStart",false),"Matching conditional replacement succeeds");
        fails(409,()->ConfigServer.saveConfiguration(prefs,rival,false,original.fingerprint),"A rival old page cannot overwrite the newer document");
        check(prefs.getString("layout","").equals(newer.raw),"Conflict preserves the winning document");
        ConfigServer.saveConfiguration(prefs,original,false,null);
        check(prefs.getString("layout","").equals(original.raw),"Explicit legacy saves keep backward-compatible behavior");
        prefs.rejectCommit=true;
        try{ConfigServer.saveConfiguration(prefs,newer,true,original.fingerprint);throw new AssertionError("Write failure hidden");}catch(IOException expected){checks++;}
        check(prefs.getString("layout","").equals(original.raw),"Failed storage commit is never reported as a successful save");prefs.rejectCommit=false;

        // Two browser workers with the same baseline race to write different configurations.
        // Exactly one succeeds; the second sees the winner while still inside CONFIG_LOCK.
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger(),conflicts=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread[] workers=new Thread[2];LayoutConfig[] candidates={newer,rival};
        for(int i=0;i<workers.length;i++){final LayoutConfig candidate=candidates[i];workers[i]=new Thread(()->{
            ready.countDown();try{start.await();ConfigServer.saveConfiguration(prefs,candidate,true,original.fingerprint);winners.incrementAndGet();}
            catch(HttpProtocol.Failure conflict){if(conflict.status==409)conflicts.incrementAndGet();else failure.set(conflict);}
            catch(Throwable unexpected){failure.set(unexpected);}
        });workers[i].start();}
        ready.await();start.countDown();for(Thread worker:workers)worker.join(3000);
        check(failure.get()==null&&winners.get()==1&&conflicts.get()==1,"Concurrent stale-baseline saves have one winner and one 409 conflict");
        check(!workers[0].isAlive()&&!workers[1].isAlive(),"Conditional workers finish without a lock deadlock");
        String savedRaw=prefs.getString("layout","");check(savedRaw.equals(newer.raw)||savedRaw.equals(rival.raw),"Saved state is one complete winning document");

        check(AmbientService.configurationCommandAllowed(newer.fingerprint,newer.raw,true,true),"Queued apply can reload its own still-current enabled configuration");
        check(!AmbientService.configurationCommandAllowed(newer.fingerprint,newer.raw,false,true),"A user turning synchronization off invalidates a queued automatic apply");
        check(!AmbientService.configurationCommandAllowed(newer.fingerprint,rival.raw,true,true),"A rival save invalidates an already queued apply");
        check(!AmbientService.configurationCommandAllowed(original.fingerprint,rival.raw,false,true),"A stale queued apply cannot enable or replace a disabled configuration");
        check(AmbientService.configurationCommandAllowed(newer.fingerprint,newer.raw,false,false),"Explicit conditional start may enable the matching saved configuration");
        check(!AmbientService.configurationCommandAllowed(original.fingerprint,rival.raw,false,false),"Explicit bound start must still target the current saved fingerprint");
        check(!AmbientService.configurationCommandAllowed(null,newer.raw,true,true),"Missing targets cannot become an automatic reload");
        check(!prefs.contains("enabled"),"Saving layout and checking queued commands never create or change the synchronization switch");
        System.out.println("ConditionalSaveContractTest PASS ("+checks+" checks, two real conditional writer threads)");
    }
}
