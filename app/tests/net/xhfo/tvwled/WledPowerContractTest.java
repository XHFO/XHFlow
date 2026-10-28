package net.xhfo.tvwled;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Host-only fake transport tests; all JSON parsing uses the real org.json implementation. */
public final class WledPowerContractTest {
    private static final String ADDRESS="192.168.1.50";
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Call {
        final String address,path,json;
        Call(String address,String path,String json){this.address=address;this.path=path;this.json=json;}
    }
    private static class Fake implements WledPowerClient.Transport {
        final List<Call> calls=new ArrayList<>();
        final Object[] replies;
        int position,closes;
        Runnable afterRequest;
        Fake(Object... replies){this.replies=replies;}
        @Override public String request(String address,String path,String json)throws Exception{
            calls.add(new Call(address,path,json));
            if(position>=replies.length)throw new AssertionError("Unexpected request "+path);
            Object reply=replies[position++];
            if(afterRequest!=null)afterRequest.run();
            if(reply instanceof Exception)throw (Exception)reply;
            return (String)reply;
        }
        @Override public void close(){closes++;}
    }
    private static void failed(WledPowerClient.Result result,boolean on,String label){
        check(!result.success,label+": must not confirm");
        check(result.requestedOn==on,label+": preserve requested state");
        check(result.error!=null&&!result.error.isEmpty(),label+": explanation provided");
        check(result.durationMs>=0,label+": nonnegative duration");
    }
    private static void command(Fake fake,boolean on)throws Exception{
        Call call=fake.calls.get(0);
        check(ADDRESS.equals(call.address),"Use configured private WLED address");
        check("/json/state".equals(call.path)&&call.json!=null,"POST command to state endpoint");
        JSONObject json=new JSONObject(call.json);
        check(json.keySet().equals(new HashSet<>(on?Arrays.asList("on","tt"):Arrays.asList("on","tt","live"))),"Exact minimal command keys; no persistent transition/brightness/preset changes");
        check(json.get("on") instanceof Boolean&&json.getBoolean("on")==on,"Strict requested on boolean");
        check(json.get("tt") instanceof Number&&json.getInt("tt")==0,"tt=0 applies only to this transaction");
        if(!on)check(json.get("live") instanceof Boolean&&!json.getBoolean("live"),"Off releases realtime input");
        check(fake.calls.get(1).json==null&&"/json/state".equals(fake.calls.get(1).path),"GET persisted state after POST");
    }
    public static void main(String[] args)throws Exception{
        Fake off=new Fake("HTTP acknowledged","{\"on\":false}","{\"live\":false}");
        WledPowerClient client=new WledPowerClient(off);
        WledPowerClient.Result result=client.setPower(ADDRESS,false);
        check(result.success&&!result.requestedOn,"Off is confirmed by both readbacks");
        check(Boolean.FALSE.equals(result.readbackOn)&&Boolean.FALSE.equals(result.readbackLive),"Off returns exact readbacks");
        check(result.error.isEmpty()&&result.durationMs>=0,"Success has no error and records elapsed time");
        command(off,false);check(off.calls.size()==3,"Off uses exactly three requests");
        check(off.calls.get(2).json==null&&"/json/info".equals(off.calls.get(2).path),"Off reads realtime status from info");
        client.close();check(off.closes==1,"Close reaches transport");

        Fake on=new Fake("{}","{\"on\":true}");
        result=new WledPowerClient(on).setPower(ADDRESS,true);
        check(result.success&&Boolean.TRUE.equals(result.readbackOn)&&result.readbackLive==null,"On confirmation never invents realtime state");
        command(on,true);check(on.calls.size()==2,"On requires state readback only");

        for(String bad:new String[]{"{}","{\"on\":\"false\"}","{\"on\":0}","{\"on\":null}","not json","[]"}){
            Fake fake=new Fake("{}",bad);result=new WledPowerClient(fake).setPower(ADDRESS,false);
            failed(result,false,"Malformed state "+bad);check(result.readbackOn==null&&result.readbackLive==null,"Malformed state gives no invented readback");check(fake.calls.size()==2,"Malformed state halts before info");
        }
        for(boolean requested:new boolean[]{false,true}){
            Fake fake=new Fake("{}","{\"on\":"+!requested+"}");result=new WledPowerClient(fake).setPower(ADDRESS,requested);
            failed(result,requested,"State mismatch");check(result.readbackOn==!requested,"Keep actual mismatching readback");check(fake.calls.size()==2,"Mismatch halts before additional requests");
        }
        for(String bad:new String[]{"{}","{\"live\":\"false\"}","{\"live\":0}","{\"live\":null}","broken"}){
            Fake fake=new Fake("{}","{\"on\":false}",bad);result=new WledPowerClient(fake).setPower(ADDRESS,false);
            failed(result,false,"Malformed realtime "+bad);check(Boolean.FALSE.equals(result.readbackOn)&&result.readbackLive==null,"Partial state retained, realtime not guessed");
        }
        Fake live=new Fake("{}","{\"on\":false}","{\"live\":true}");result=new WledPowerClient(live).setPower(ADDRESS,false);
        failed(result,false,"Realtime remains active");check(Boolean.TRUE.equals(result.readbackLive),"Retain actual live=true evidence");

        for(int failAt=0;failAt<3;failAt++){
            Object[] replies={"{}","{\"on\":false}","{\"live\":false}"};replies[failAt]=new IOException("HTTP 500 / read timeout");
            Fake fake=new Fake(replies);result=new WledPowerClient(fake).setPower(ADDRESS,false);
            failed(result,false,"Transport failure stage "+failAt);check(fake.calls.size()==failAt+1,"Failure does not continue transaction");check(result.error.contains("HTTP 500"),"Transport error preserved");
        }
        for(String invalid:new String[]{"127.0.0.1","8.8.8.8","192.168.1.300","host.local","192.168.1.2:80","239.1.1.1"}){
            Fake fake=new Fake();result=new WledPowerClient(fake).setPower(invalid,false);
            failed(result,false,"Invalid destination "+invalid);check(fake.calls.isEmpty(),"Invalid address never reaches transport");
        }
        Fake before=new Fake();client=new WledPowerClient(before);client.close();result=client.setPower(ADDRESS,false);
        failed(result,false,"Canceled before command");check(before.calls.isEmpty(),"Canceled client never sends command");
        for(int closeAt=1;closeAt<=3;closeAt++){
            final int at=closeAt;Fake fake=new Fake("{}","{\"on\":false}","{\"live\":false}");final WledPowerClient cancel=new WledPowerClient(fake);
            fake.afterRequest=()->{if(fake.calls.size()==at)cancel.close();};
            result=cancel.setPower(ADDRESS,false);failed(result,false,"Canceled after transport stage "+at);check(fake.calls.size()==at,"No extra requests after cancellation");
        }
        Fake interrupted=new Fake();
        Thread.currentThread().interrupt();
        try{result=new WledPowerClient(interrupted).setPower(ADDRESS,true);failed(result,true,"Interrupted before command");check(interrupted.calls.isEmpty(),"Interrupted operation does not send");}
        finally{Thread.interrupted();}

        final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);
        final WledPowerClient.Result[] outcome=new WledPowerClient.Result[1];
        WledPowerClient.Transport blocking=new WledPowerClient.Transport(){
            public String request(String address,String path,String json)throws Exception{entered.countDown();if(!released.await(2,TimeUnit.SECONDS))throw new IOException("Fixture timeout");return "{}";}
            public void close(){released.countDown();}
        };
        final WledPowerClient cancel=new WledPowerClient(blocking);
        Thread worker=new Thread(()->outcome[0]=cancel.setPower(ADDRESS,false),"power-cancel-contract");worker.start();
        check(entered.await(1,TimeUnit.SECONDS),"Blocking fake entered");cancel.close();worker.join(1000);
        check(!worker.isAlive(),"Close unblocks active transaction without holding workflow monitor");failed(outcome[0],false,"Canceled while request in progress");
        System.out.println("WledPowerContractTest PASS ("+checks+" checks, no network/device access)");
    }
}
