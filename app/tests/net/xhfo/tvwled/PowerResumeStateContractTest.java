package net.xhfo.tvwled;

/** Crash/restart and condition transitions; no Android runtime or controller is contacted. */
public final class PowerResumeStateContractTest {
    private static final String A="192.168.1.50",B="192.168.1.51";
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static PowerResumeState restart(PowerResumeState state){return PowerResumeState.decode(state.encode());}
    private static void rejects(Runnable action,String message){
        try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}
    }
    public static void main(String[] args){
        PowerResumeState fresh=PowerResumeState.empty().forAddress(A);
        check(!fresh.outputOwned&&fresh.restoreReason.isEmpty(),"opening the app alone owns no output or power");
        check(!restart(fresh).shouldRestore(A,true,true,true),"a fresh process never invents an ON command");
        PowerResumeState streaming=fresh.afterOutput();
        check(restart(streaming).outputOwned,"actual output ownership survives process death");
        PowerResumeState beforeOff=streaming.beforeOff(PowerResumeState.SLEEP);
        check(PowerResumeState.SLEEP.equals(restart(beforeOff).restoreReason),"persisted before-OFF intention survives death before the HTTP callback");
        check(restart(beforeOff).shouldRestore(A,true,true,true),"death after OFF but before readback still restores");
        check(restart(beforeOff).maySleepOff(A,true,true,true,true,false,false),"restart asleep with temporarily unknown input keeps previous output ownership");
        check(!restart(beforeOff).maySleepOff(B,true,true,true,true,false,false),"sleep ownership cannot transfer to a different controller");
        for(int enabled=0;enabled<2;enabled++)for(int awake=0;awake<2;awake++)for(int eligible=0;eligible<2;eligible++){
            boolean expected=enabled==1&&awake==1&&eligible==1;
            check(restart(beforeOff).shouldRestore(A,enabled==1,awake==1,eligible==1)==expected,"restore requires all current conditions");
            check(!restart(beforeOff).shouldRestore(B,enabled==1,awake==1,eligible==1),"address mismatch always blocks restore");
        }
        PowerResumeState stopped=restart(beforeOff.relinquishOutput());
        check(!stopped.outputOwned&&!stopped.restoreReason.isEmpty(),"explicit master STOP retains the previous OFF debt but drops output ownership");
        check(!stopped.shouldRestore(A,false,true,true),"STOP cannot be undone by process recovery");
        check(stopped.shouldRestore(A,true,true,true),"a later explicit START can repay the earlier OFF debt");
        check(!stopped.maySleepOff(A,true,true,true,true,false,false),"a stopped session cannot lend old output ownership to an unknown input");
        PowerResumeState excluded=restart(beforeOff.relinquishOutput().beforeOff(PowerResumeState.SOURCE_EXCLUDED));
        check(!excluded.outputOwned&&PowerResumeState.SOURCE_EXCLUDED.equals(excluded.restoreReason),"source exclusion safely replaces the pending reason without owning output");
        check(!excluded.shouldRestore(A,true,true,false),"an excluded input never wakes the controller");
        check(excluded.shouldRestore(A,true,true,true),"returning to an allowed input restores an app-issued OFF");
        check(!excluded.maySleepOff(A,true,true,true,true,false,false),"exclusion then sleep cannot reuse ownership from the prior allowed input");
        PowerResumeState excludedWithoutOff=restart(beforeOff.relinquishOutput());
        check(!excludedWithoutOff.maySleepOff(A,true,true,true,true,true,false),"confirmed excluded input blocks sleep OFF even with older restore debt");
        check(excludedWithoutOff.shouldRestore(A,true,true,true),"changing inputs does not erase an already-issued sleep OFF");
        PowerResumeState confirmed=restart(beforeOff.afterOnConfirmed());
        check(confirmed.restoreReason.isEmpty()&&confirmed.outputOwned,"ON readback clears only the power debt");
        check(!confirmed.shouldRestore(A,true,true,true),"successful persisted ON is not repeated after restart");
        check(restart(beforeOff).shouldRestore(A,true,true,true),"crash after ON readback but before persistence safely permits an idempotent ON");
        PowerResumeState changed=restart(beforeOff.forAddress(B));
        check(B.equals(changed.address)&&!changed.outputOwned&&changed.restoreReason.isEmpty(),"controller change clears all ownership and pending actions");
        check(!changed.forAddress(A).shouldRestore(A,true,true,true),"changing back cannot resurrect the former controller's debt");
        check(beforeOff.forAddress(A)==beforeOff,"same-controller geometry edits retain state");
        check(PowerResumeState.empty().encode().isEmpty(),"explicit shutdown can atomically clear the persisted value");
        check(!restart(PowerResumeState.empty()).shouldRestore(A,true,true,true),"cleared state grants no restore");
        for(int enabled=0;enabled<2;enabled++)for(int sleepOff=0;sleepOff<2;sleepOff++)for(int asleep=0;asleep<2;asleep++)
            check(streaming.maySleepOff(A,enabled==1,sleepOff==1,asleep==1,true,true,true)==(enabled==1&&sleepOff==1&&asleep==1),"sleep action requires enabled intent, sleep policy and sleep state");
        check(!fresh.maySleepOff(A,true,true,true,true,true,true),"filtered sleep OFF requires actual previous output");
        check(fresh.maySleepOff(A,true,true,true,false,false,false),"unfiltered sleep policy retains its global behavior");
        for(String reason:new String[]{"input_unknown","input_not_allowed","config_invalid","","display_sleep"})
            for(int enabled=0;enabled<2;enabled++)for(int filter=0;filter<2;filter++)for(int off=0;off<2;off++)
                check(PowerResumeState.maySourceOff(enabled==1,filter==1,off==1,reason)==
                        (enabled==1&&filter==1&&off==1&&("input_unknown".equals(reason)||"input_not_allowed".equals(reason))),"source OFF requires explicit policy and a genuine excluded condition");
        for(String bad:new String[]{"2|"+A+"|1|sleep","1|"+A+"|yes|sleep","1|"+A+"|1|other","1|"+A+"|1|sleep|extra","1|8.8.8.8|1|sleep","1|192.168.1.999|1|sleep","1|hostname.local|1|sleep","1||1|sleep","garbage","",null}){
            PowerResumeState parsed=PowerResumeState.decode(bad);
            check(parsed.address.isEmpty()&&!parsed.outputOwned&&parsed.restoreReason.isEmpty(),"malformed state fails closed");
        }
        rejects(()->fresh.beforeOff(""),"empty OFF reason rejected");
        rejects(()->PowerResumeState.empty().beforeOff(PowerResumeState.SLEEP),"OFF needs a controller");
        rejects(()->PowerResumeState.empty().afterOutput(),"output needs a controller");
        rejects(()->fresh.forAddress("8.8.8.8"),"non-LAN state rejected");
        check(restart(fresh.forAddress("10.1.2.3").afterOutput()).outputOwned,"10/8 controller round-trips");
        check(restart(fresh.forAddress("172.31.2.3").beforeOff(PowerResumeState.SLEEP)).shouldRestore("172.31.2.3",true,true,true),"172.16/12 controller round-trips");
        System.out.println("PowerResumeStateContractTest PASS ("+checks+" checks)");
    }
}
