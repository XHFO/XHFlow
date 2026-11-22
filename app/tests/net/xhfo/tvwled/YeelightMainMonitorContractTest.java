package net.xhfo.tvwled;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Loopback-only contracts: no real lamp, television, UDP session, or LAN device is contacted. */
public final class YeelightMainMonitorContractTest {
    private static int checks;
    private static final InetAddress LOOPBACK=loopback();
    private static InetAddress loopback(){try{return InetAddress.getByName("127.0.0.1");}catch(Exception impossible){throw new AssertionError(impossible);}}
    private static synchronized void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void until(BooleanSupplier condition,long timeout,String message) throws Exception {
        long deadline=System.nanoTime()+timeout*1000000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);
        check(condition.getAsBoolean(),message);
    }
    private static void await(CountDownLatch latch,String message) throws Exception {check(latch.await(5,TimeUnit.SECONDS),message);}
    private static void send(Socket socket,String message) throws Exception {
        socket.getOutputStream().write((message+"\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
    }
    private static int query(BufferedReader input) throws Exception {
        String text=input.readLine();if(text==null)throw new AssertionError("Expected read-only query");
        JSONObject value=new JSONObject(text);
        check("get_prop".equals(value.optString("method")),"the monitor only sends get_prop");
        check(!value.has("token"),"the status channel never creates or carries a UDP token");
        JSONArray params=value.getJSONArray("params");
        check(params.length()==2&&"bright".equals(params.getString(0))&&"ct".equals(params.getString(1)),"queries request exactly front brightness and colour temperature");
        check(value.getInt("id")>0,"queries have a positive receipt ID");
        return value.getInt("id");
    }
    private static BufferedReader reader(Socket socket) throws Exception {
        socket.setSoTimeout(6000);return new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
    }
    private static void disconnected(BufferedReader input,String message) throws Exception {
        boolean ended;
        try{ended=input.readLine()==null;}
        catch(SocketTimeoutException timeout){throw timeout;}
        catch(IOException reset){ended=true;}
        check(ended,message);
    }
    private static String result(int id,Object brightness,Object ct) throws Exception {
        return new JSONObject().put("id",id).put("result",new JSONArray().put(brightness).put(ct)).toString();
    }
    private static final class Events implements YeelightMainMonitor.Listener {
        final List<String> snapshots=Collections.synchronizedList(new ArrayList<>());
        final List<Long> queryAges=Collections.synchronizedList(new ArrayList<>());
        final List<String> changes=Collections.synchronizedList(new ArrayList<>());
        public void snapshot(int brightness,int ct){snapshots.add(brightness+":"+ct);}
        public void snapshot(int brightness,int ct,long age){queryAges.add(age);snapshot(brightness,ct);}
        public void changed(Integer brightness,Integer ct){changes.add(brightness+":"+ct);}
    }
    interface Script {void connected(Socket socket,int connection) throws Exception;}
    private static final class Lamp implements Closeable {
        final ServerSocket server;
        final Thread worker;
        final AtomicInteger accepted=new AtomicInteger();
        volatile boolean closed;
        volatile Throwable failure;
        Lamp(Script script) throws Exception {
            server=new ServerSocket(0,10,LOOPBACK);server.setSoTimeout(200);
            worker=new Thread(()->{
                while(!closed){
                    try(Socket socket=server.accept()){
                        script.connected(socket,accepted.incrementAndGet());
                    }catch(SocketTimeoutException retry){}
                    catch(Throwable error){if(!closed){failure=error;return;}}
                }
            },"synthetic-yeelight-lamp");worker.setDaemon(true);worker.start();
        }
        YeelightMainMonitor monitor(Events events){return new YeelightMainMonitor(LOOPBACK,server.getLocalPort(),events);}
        void healthy(){if(failure!=null)throw new AssertionError("Synthetic lamp failed",failure);checks++;}
        public void close(){closed=true;try{server.close();}catch(Exception ignored){}worker.interrupt();}
    }

    private static void notificationsAndReceipts() throws Exception {
        Events events=new Events();CountDownLatch notification=new CountDownLatch(1),allowSnapshot=new CountDownLatch(1),ended=new CountDownLatch(1);
        try(Lamp lamp=new Lamp((socket,connection)->{
            BufferedReader input=reader(socket);int first=query(input);
            send(socket,"{\"method\":\"props\",\"params\":{\"bright\":\"42\"}}");notification.countDown();
            if(!allowSnapshot.await(5,TimeUnit.SECONDS))throw new AssertionError("Snapshot gate timed out");
            send(socket,result(first+77,99,6500));
            send(socket,new JSONObject().put("id",first).put("result",new JSONArray().put("ok")).toString());
            send(socket,result(first,0,4000));
            Thread.sleep(80);
            send(socket,result(first,"70","4000"));
            send(socket,result(first,99,6500));
            send(socket,"{\"method\":\"props\",\"params\":{\"bright\":\"55\",\"ct\":\"bad\"}}");
            send(socket,"{\"method\":\"props\",\"params\":{\"bright\":true,\"ct\":\"2700\"}}");
            send(socket,"{\"method\":\"props\",\"params\":{\"bright\":1.5,\"ct\":7000,\"bg_bright\":90}}");
            send(socket,"{\"method\":\"props\",\"params\":{\"bg_bright\":3,\"bg_ct\":2500}}");
            send(socket,"{\"method\":\"properties\",\"params\":{\"bright\":100}}");
            send(socket,"not json");
            int second=query(input);check(second!=first,"successive polls use distinct receipt IDs");
            send(socket,result(first,33,3500));send(socket,result(second,60,3000));
            while(input.readLine()!=null)throw new AssertionError("No extra query is expected after close");
            ended.countDown();
        });YeelightMainMonitor monitor=lamp.monitor(events)){
            check(!monitor.ready(),"an unstarted monitor is not ready");monitor.start();monitor.start();
            await(notification,"the synthetic lamp received the initial query");
            until(()->events.changes.size()==1,1000,"a partial front-light notification is delivered");
            check(!monitor.ready()&&events.snapshots.isEmpty(),"a notification cannot masquerade as a full initial snapshot");
            check("42:null".equals(events.changes.get(0)),"missing notification fields stay absent");
            allowSnapshot.countDown();
            until(()->events.snapshots.size()==1&&events.changes.size()==3&&monitor.ready(),1500,"a complete matching reply makes the monitor ready");
            check("70:4000".equals(events.snapshots.get(0)),"numeric strings in full snapshots are accepted");
            check(events.queryAges.get(0)>=70,"a delayed snapshot carries its pending query age rather than appearing newly sampled");
            check("55:null".equals(events.changes.get(1))&&"null:2700".equals(events.changes.get(2)),"invalid fields are ignored independently in partial notifications");
            until(()->events.snapshots.size()==2,3500,"a complete status query is repeated after two seconds");
            check("60:3000".equals(events.snapshots.get(1)),"stale and duplicate receipts cannot replace the current poll");
            check(events.queryAges.size()==2&&events.queryAges.get(1)>=0,"every matched full snapshot carries a nonnegative query age");
            check(events.changes.size()==3,"back-light fields, malformed data and unsupported notifications are ignored");
            monitor.close();check(!monitor.ready(),"closing immediately revokes readiness");await(ended,"closing interrupts the active socket read");
            check(lamp.accepted.get()==1,"start is idempotent and close does not reconnect");lamp.healthy();
        }
    }

    private static void reconnectAndMissingReply() throws Exception {
        Events events=new Events();CountDownLatch disconnect=new CountDownLatch(1),secondNotification=new CountDownLatch(1),thirdDone=new CountDownLatch(1);
        try(Lamp lamp=new Lamp((socket,connection)->{
            BufferedReader input=reader(socket);int id=query(input);
            if(connection==1){
                send(socket,result(id,31,5000));if(!disconnect.await(5,TimeUnit.SECONDS))throw new AssertionError("Disconnect gate timed out");return;
            }
            if(connection==2){
                send(socket,"{\"method\":\"props\",\"params\":{\"bright\":20,\"ct\":3000}}");
                send(socket,result(id,20,""));
                secondNotification.countDown();
                check(input.readLine()==null,"missing complete reply eventually closes the connection despite notifications");return;
            }
            if(connection==3){
                send(socket,result(id,22,3200));while(input.readLine()!=null){}
                thirdDone.countDown();return;
            }
            throw new AssertionError("Unexpected reconnect after close");
        });YeelightMainMonitor monitor=lamp.monitor(events)){
            monitor.start();until(monitor::ready,1500,"initial status is ready");disconnect.countDown();
            await(secondNotification,"a dropped connection is retried");
            until(()->events.changes.size()==1,500,"reconnected notifications remain observable");
            check(!monitor.ready(),"old snapshots cannot grant control after reconnect");
            until(()->events.snapshots.size()==2&&monitor.ready(),3500,"a timed out poll reconnects and requires a new complete snapshot");
            check("22:3200".equals(events.snapshots.get(1)),"the third connection replaces the old snapshot");
            monitor.close();await(thirdDone,"close ends the recovered status socket");Thread.sleep(450);
            check(lamp.accepted.get()==3,"a closed monitor does not retry again");lamp.healthy();
        }
    }

    private static void boundedInput() throws Exception {
        Events events=new Events();CountDownLatch recoveredDone=new CountDownLatch(1);
        try(Lamp lamp=new Lamp((socket,connection)->{
            BufferedReader input=reader(socket);int id=query(input);
            if(connection==1){
                byte[] oversized=new byte[5000];java.util.Arrays.fill(oversized,(byte)'x');
                OutputStream output=socket.getOutputStream();output.write(oversized);output.flush();
                disconnected(input,"an overlong unterminated line is bounded and disconnected");return;
            }
            if(connection==2){
                socket.getOutputStream().write('{');socket.getOutputStream().flush();
                disconnected(input,"an incomplete line cannot hold a connection indefinitely");return;
            }
            if(connection==3){send(socket,result(id,45,4500));while(input.readLine()!=null){}recoveredDone.countDown();return;}
            throw new AssertionError("Unexpected input recovery attempt");
        });YeelightMainMonitor monitor=lamp.monitor(events)){
            monitor.start();until(()->monitor.ready()&&events.snapshots.size()==1,5000,"malformed and stalled connections recover without accepting invalid state");
            check(lamp.accepted.get()==3,"both invalid transports were independently retried");
            check("45:4500".equals(events.snapshots.get(0)),"only valid recovered data reaches the full-snapshot listener");
            monitor.close();await(recoveredDone,"recovered socket closes cleanly");lamp.healthy();
        }
    }

    private static void validationAndStoppedStart() throws Exception {
        Events events=new Events();
        for(String address:new String[]{"127.0.0.1","8.8.8.8","yeelight.local","192.168.1.999","http://192.168.1.20"}){
            try{new YeelightMainMonitor(address,events);throw new AssertionError("Expected private-IP validation");}
            catch(IllegalArgumentException expected){checks++;}
        }
        try(Lamp lamp=new Lamp((socket,connection)->{throw new AssertionError("Closed-before-start monitor connected");});YeelightMainMonitor monitor=lamp.monitor(events)){
            monitor.close();monitor.start();Thread.sleep(450);check(!monitor.ready()&&lamp.accepted.get()==0,"close-before-start cannot launch or connect");lamp.healthy();
        }
        int unused;try(ServerSocket temporary=new ServerSocket(0,1,LOOPBACK)){unused=temporary.getLocalPort();}
        YeelightMainMonitor absent=new YeelightMainMonitor(LOOPBACK,unused,events);absent.start();Thread.sleep(100);absent.close();
        check(!absent.ready(),"closing during connection failure or retry remains safe");
    }

    public static void main(String[] args) throws Exception {
        notificationsAndReceipts();reconnectAndMissingReply();boundedInput();validationAndStoppedStart();
        System.out.println("YeelightMainMonitorContractTest PASS ("+checks+" checks; loopback sockets only)");
    }
}
