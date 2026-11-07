package net.xhfo.tvwled;

/** Host-only contract: the black-point ceiling must not flicker on a one-level sample jitter. */
public final class BlackLimitContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static EdgeColorEngine engine(){
        return new EdgeColorEngine(1,new int[]{0},new String[]{"top"},new double[]{.5},true,5);
    }
    private static int value(EdgeColorEngine engine,int source,int corrected,long now){
        int[] goal={corrected,corrected,corrected};
        LedColorFrame frame=new LedColorFrame(new int[]{source,source,source},goal,1);
        int[] out=engine.smooth(frame,now,true,.35,2000);
        return out[1];
    }

    public static void main(String[] args) throws Exception {
        // Alternating 24/25 used to move the ceiling between 4 and ~7 every frame.
        EdgeColorEngine jitter=engine();
        int low=0,high=0;
        for(int frame=0;frame<60;frame++){
            int source=frame%2==0?24:25;
            int out=value(jitter,source,255,frame*33);
            if(frame>30){low=low==0?out:Math.min(low,out);high=Math.max(high,out);}
        }
        check(high-low<=1,"a one-level jitter at the black knee is invisible");

        // A moderate change still eases open instead of stepping.
        EdgeColorEngine rising=engine();
        for(int frame=0;frame<60;frame++)value(rising,24,255,frame*33);
        int first=value(rising,60,60,1980),settled=0;
        for(int frame=0;frame<30;frame++)settled=value(rising,60,60,2013+frame*33);
        check(first<24,"a moderate ceiling change eases rather than steps");
        check(settled>40,"the ceiling still opens for a genuinely brighter picture");

        // A pure black frame must clear at once, not fade.
        EdgeColorEngine black=engine();
        for(int frame=0;frame<60;frame++)value(black,200,200,frame*33);
        check(value(black,0,0,1980)==0,"pure black clears the output immediately");

        System.out.println("BlackLimitContractTest PASS ("+checks+" checks, no network or device access)");
    }
}
