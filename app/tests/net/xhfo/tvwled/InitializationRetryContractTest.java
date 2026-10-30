package net.xhfo.tvwled;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;

/** Exercises network recovery timing and cancellation without TV, WLED, timers, or real sockets. */
public final class InitializationRetryContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static boolean accepts(InitializationRetryPolicy policy,InitializationRetryPolicy.Ticket ticket,Object config,long generation){
        return policy.accepts(ticket,config,generation,true,true,true,false);
    }
    public static void main(String[] args){
        Throwable[] temporary={new SocketTimeoutException("WLED is still waking"),new ConnectException("Connection refused"),new NoRouteToHostException("Network not ready"),
            new SocketException("Connection reset"),new EOFException("Partial HTTP body"),new IOException("Temporary stream failure"),new Exception(new SocketTimeoutException())};
        for(Throwable failure:temporary)check(InitializationRetryPolicy.isTransient(failure),"Network/stream failures may retry: "+failure.getClass().getSimpleName());
        Throwable[] permanent={new IllegalStateException("WLED LED count mismatch"),new IllegalStateException("Vendor capture unsupported"),new IllegalArgumentException("Bad address"),
            new SecurityException("Service permission denied"),new ProtocolException("Invalid HTTP protocol"),new MalformedURLException("Bad URL"),new UnknownServiceException("Unsupported protocol"),
            new FileNotFoundException("Not a WLED endpoint"),new RuntimeException("Malformed JSON"),new IllegalStateException("Permanent contract failure",new SocketTimeoutException())};
        for(Throwable failure:permanent)check(!InitializationRetryPolicy.isTransient(failure),"Configuration, permissions, protocol and unsupported capture do not loop: "+failure.getClass().getSimpleName());
        InitializationRetryPolicy policy=new InitializationRetryPolicy();Object applied=new String("same saved document"),unappliedDraft=new String("same saved document");
        check(applied.equals(unappliedDraft)&&applied!=unappliedDraft,"Fixture separates value equality from applied-object identity");
        long[] delays={2000,5000,10000,30000,30000,30000};
        InitializationRetryPolicy.Ticket ticket=null;
        for(int i=0;i<delays.length;i++){
            ticket=policy.failed(applied,7);
            check(ticket.attempt==i+1&&ticket.delayMs==delays[i],"Retry delay backs off and caps at thirty seconds");
            check(accepts(policy,ticket,applied,7),"The original applied configuration remains eligible after a temporary failure");
        }
        check(!accepts(policy,ticket,unappliedDraft,7),"A retry cannot load even an equal-valued unapplied draft");
        check(!accepts(policy,ticket,applied,8),"A new apply generation invalidates an old connection attempt");
        for(boolean enabled:new boolean[]{false,true})for(boolean awake:new boolean[]{false,true})for(boolean source:new boolean[]{false,true})for(boolean stopping:new boolean[]{false,true}){
            check(policy.accepts(ticket,applied,7,enabled,awake,source,stopping)==(enabled&&awake&&source&&!stopping),"Every delayed callback rechecks master intent, awake display, selected source and shutdown");
        }
        InitializationRetryPolicy.Ticket old=ticket;
        policy.cancel();check(!accepts(policy,old,applied,7),"Stopping, sleeping or leaving a selected source invalidates its queued timer");
        InitializationRetryPolicy.Ticket resumed=policy.failed(applied,7);
        check(resumed.attempt==1&&resumed.delayMs==2000&&accepts(policy,resumed,applied,7),"Returning to eligible conditions begins a fresh retry cycle");
        check(!accepts(policy,old,applied,7),"An old callback cannot revive after a new cycle starts with the same config and generation");
        policy.cancel();InitializationRetryPolicy.Ticket afterSuccess=policy.failed(applied,7);
        check(afterSuccess.attempt==1&&afterSuccess.delayMs==2000,"A successful initialization resets the backoff for a later independent outage");
        check(!policy.accepts(null,applied,7,true,true,true,false),"No retry exists without a failure ticket");
        System.out.println("InitializationRetryContractTest PASS ("+checks+" checks, no network/device access)");
    }
}
