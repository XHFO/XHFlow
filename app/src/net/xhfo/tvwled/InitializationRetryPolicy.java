package net.xhfo.tvwled;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.UnknownServiceException;

/** Retries temporary connection failures without changing the applied configuration or intent. */
final class InitializationRetryPolicy {
    private long cycle;
    private int failures;
    static final class Ticket {
        final Object config;
        final long generation,cycle,delayMs;
        final int attempt;
        Ticket(Object value,long revision,long epoch,int count,long delay){config=value;generation=revision;cycle=epoch;attempt=count;delayMs=delay;}
    }
    Ticket failed(Object config,long generation){
        failures=Math.min(failures+1,1_000_000);
        return new Ticket(config,generation,cycle,failures,delayMs(failures));
    }
    void cancel(){cycle++;failures=0;}
    boolean accepts(Ticket ticket,Object config,long generation,boolean enabled,boolean awake,boolean sourceAllowed,boolean stopping){
        return ticket!=null&&ticket.cycle==cycle&&ticket.config==config&&ticket.generation==generation&&enabled&&awake&&sourceAllowed&&!stopping;
    }
    static long delayMs(int attempt){return attempt<=1?2000:attempt==2?5000:attempt==3?10000:30000;}
    static boolean isTransient(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            // A malformed configuration or unsupported/protocol response needs intervention.
            if(failure instanceof IllegalArgumentException||failure instanceof IllegalStateException||failure instanceof SecurityException)return false;
            if(failure instanceof MalformedURLException||failure instanceof ProtocolException||failure instanceof UnknownServiceException||failure instanceof FileNotFoundException)return false;
            if(failure instanceof IOException)return true;
        }
        return false;
    }
}
