package net.xhfo.tvwled;

/**
 * Durable ownership of one controller, independent of a service or HTTP request lifecycle.
 * Persist beforeOff synchronously before sending OFF, and persist afterOnConfirmed only after
 * a successful ON readback. A timeout cannot prove that an OFF command did not reach the device.
 */
final class PowerResumeState {
    static final String PREF_KEY="powerResumeState.v1";
    static final String SLEEP="sleep",SOURCE_EXCLUDED="source_excluded";
    final String address,restoreReason;
    final boolean outputOwned;

    private PowerResumeState(String address,boolean owned,String reason){
        this.address=address;outputOwned=owned;restoreReason=reason;
    }
    static PowerResumeState empty(){return new PowerResumeState("",false,"");}

    /** Unknown, corrupt, or future versions grant no power ownership. No DNS or network I/O. */
    static PowerResumeState decode(String encoded){
        if(encoded==null||encoded.isEmpty()||encoded.length()>128)return empty();
        String[] fields=encoded.split("\\|",-1);
        if(fields.length!=4||!"1".equals(fields[0])||!validAddress(fields[1])||
                !("0".equals(fields[2])||"1".equals(fields[2]))||!validReason(fields[3]))return empty();
        return new PowerResumeState(fields[1],"1".equals(fields[2]),fields[3]);
    }
    String encode(){return address.isEmpty()?"":"1|"+address+"|"+(outputOwned?"1":"0")+"|"+restoreReason;}

    /** Layout edits on the same controller retain a pending restore; another controller cannot. */
    PowerResumeState forAddress(String nextAddress){
        if(nextAddress==null)throw new IllegalArgumentException("Controller address required");
        String next=nextAddress.trim();
        if(!validAddress(next))throw new IllegalArgumentException("Private IPv4 controller required");
        return address.equals(next)?this:new PowerResumeState(next,false,"");
    }
    /** Call only after a current session actually sends its first DDP frame. */
    PowerResumeState afterOutput(){
        requireAddress();return outputOwned?this:new PowerResumeState(address,true,restoreReason);
    }
    /** STOP or a confirmed excluded source ends output ownership, but cannot erase an OFF debt. */
    PowerResumeState relinquishOutput(){return outputOwned?new PowerResumeState(address,false,restoreReason):this;}
    PowerResumeState beforeOff(String reason){
        requireAddress();
        if(!SLEEP.equals(reason)&&!SOURCE_EXCLUDED.equals(reason))throw new IllegalArgumentException("OFF reason required");
        return restoreReason.equals(reason)?this:new PowerResumeState(address,outputOwned,reason);
    }
    PowerResumeState afterOnConfirmed(){
        return restoreReason.isEmpty()?this:new PowerResumeState(address,outputOwned,"");
    }
    boolean shouldRestore(String currentAddress,boolean enabled,boolean awake,boolean sourceEligible){
        return enabled&&awake&&sourceEligible&&matches(currentAddress)&&!restoreReason.isEmpty();
    }
    boolean maySleepOff(String currentAddress,boolean enabled,boolean sleepOff,boolean sleeping,
                        boolean sourceFilterEnabled,boolean inputKnown,boolean sourceEligible){
        return enabled&&sleepOff&&sleeping&&(!sourceFilterEnabled||
                (matches(currentAddress)&&outputOwned&&(!inputKnown||sourceEligible)));
    }
    static boolean maySourceOff(boolean enabled,boolean sourceFilterEnabled,boolean offWhenExcluded,String blockReason){
        return enabled&&sourceFilterEnabled&&offWhenExcluded&&
                ("input_unknown".equals(blockReason)||"input_not_allowed".equals(blockReason));
    }
    private boolean matches(String currentAddress){return !address.isEmpty()&&address.equals(currentAddress);}
    private void requireAddress(){if(address.isEmpty())throw new IllegalStateException("Controller address required");}
    private static boolean validReason(String value){return value.isEmpty()||SLEEP.equals(value)||SOURCE_EXCLUDED.equals(value);}
    private static boolean validAddress(String value){
        if(value==null||!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))return false;
        String[] parts=value.split("\\.");int[] ip=new int[4];
        for(int i=0;i<4;i++){ip[i]=Integer.parseInt(parts[i]);if(ip[i]>255)return false;}
        return ip[0]==10||(ip[0]==172&&ip[1]>=16&&ip[1]<=31)||(ip[0]==192&&ip[1]==168);
    }
}
