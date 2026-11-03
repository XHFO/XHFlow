package net.xhfo.tvwled;

/** Host-only contract for the asymmetric release and the long fade to black. */
public final class FadeContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Work {void run() throws Exception;}
    private static void rejects(Work work,String message) throws Exception {try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static EdgeColorEngine engine(){
        return new EdgeColorEngine(1,new int[]{0},new String[]{"top"},new double[]{.5},true,5);
    }
    private static final String BASE="{\"schema\":\"xh-tv-wled-layout/v1\",\"coordinateSystem\":\"screen-front-normalized\","
        +"\"controller\":{\"address\":\"192.168.1.50\",\"ledCount\":1},"
        +"\"leds\":[{\"wledIndex\":0,\"edge\":\"top\",\"sample\":{\"x\":.5,\"y\":.05}}]}";
    private static String withOutput(String output){return BASE.substring(0,BASE.length()-1)+",\"output\":"+output+"}";}
    private static int peak(int[] frame){int max=0;for(int value:frame)max=Math.max(max,value);return max;}
    /** Sixty 33 ms steps carry the engine to full brightness. */
    private static void warm(EdgeColorEngine engine){
        for(int step=0;step<60;step++)engine.smooth(new int[]{255,255,255},step*33,true,.35,0);
    }
    private static int fall(EdgeColorEngine engine,int startMs,int steps,int darkFadeMs){
        int peak=0;
        for(int step=0;step<steps;step++)peak=peak(engine.smooth(new int[]{0,0,0},startMs+step*33,true,.35,darkFadeMs));
        return peak;
    }

    public static void main(String[] args) throws Exception {
        // Rising is quicker than falling: a single 33 ms step up moves further than a step down.
        EdgeColorEngine up=engine();
        int[] goal=new int[]{255,255,255};
        int firstUp=peak(up.smooth(goal,33,true,.35,0));
        EdgeColorEngine down=engine();
        down.smooth(goal,33,true,.35,0);
        int firstDown=peak(down.smooth(new int[]{0,0,0},66,true,.35,0));
        check(firstUp>firstDown,"release is slower than attack");
        check(firstUp>20&&firstUp<90,"the attack still reaches the frame in a few steps");

        // A bright frame falling to black holds briefly, then walks down over two seconds.
        EdgeColorEngine slow=engine();warm(slow);
        check(peak(slow.smooth(goal,2033,true,.35,2000))>=250,"the strip is bright before the cut");
        check(fall(slow,2033,3,2000)>200,"the first frames of a cut do not snap to black");
        int atOneSecond=fall(slow,2132,24,2000);
        check(atOneSecond>40&&atOneSecond<200,"about one second in, the fade is still on its way down");
        int atTwoSeconds=fall(slow,2924,30,2000);
        check(atTwoSeconds<40,"after two seconds of fade the strip is near black");

        // Without a fade the drop is still smoothed, but far quicker than the walk down.
        EdgeColorEngine quick=engine();warm(quick);
        check(fall(quick,2066,10,0)<200,"a zero fade releases quickly");

        // A dim scene is not a fade to black and keeps the normal release.
        EdgeColorEngine dim=engine();warm(dim);
        check(peak(dim.smooth(new int[]{40,40,40},2033,true,.35,2000))>200,"dimming to a dark scene keeps the normal release");

        // A cut back to bright leaves the fade immediately, but the rise is rate limited.
        EdgeColorEngine recover=engine();warm(recover);
        fall(recover,2066,60,2000);
        int recovered=peak(recover.smooth(goal,2066+60*33,true,.35,2000));
        check(recovered>30,"bright content interrupts the fade");
        check(recovered<120,"interrupting a dark fade does not slam to full brightness in one frame");

        // Black-background animation that alternates across the threshold must not strobe: the
        // hysteresis holds each state, so a fade never starts and the output settles mid-scale.
        EdgeColorEngine chatter=engine();
        for(int frame=0;frame<60;frame++)chatter.smooth(new int[]{5,5,5},frame*33,true,.35,2000);
        int chatterPeak=0;
        for(int frame=0;frame<40;frame++){
            int[] out=chatter.smooth(frame%2==0?new int[]{5,5,5}:new int[]{30,30,30},2000+frame*33,true,.35,2000);
            chatterPeak=Math.max(chatterPeak,peak(out));
        }
        check(chatterPeak<80,"alternating dark frames never restart a full-brightness fade");
        check(peak(chatter.smooth(new int[]{5,5,5},3400,true,.35,2000))>0,"the strip keeps a stable low level instead of switching off");

        // Rate ceilings bound a single frame.
        EdgeColorEngine step=engine();
        check(peak(step.smooth(goal,33,true,.35,2000))<=1.4*33+2,"a single frame cannot jump more than the rise ceiling");

        // Configuration bounds.
        check(new LayoutConfig(BASE).fadeDarkMs==2000,"the dark fade defaults to two seconds");
        check(new LayoutConfig(withOutput("{\"fade\":{\"darkMs\":3200}}")).fadeDarkMs==3200,"explicit fade duration is kept");
        check(new LayoutConfig(withOutput("{\"fade\":{\"darkMs\":0}}")).fadeDarkMs==0,"a zero fade disables the long walk down");
        rejects(()->new LayoutConfig(withOutput("{\"fade\":{\"darkMs\":-1}}")),"negative fade rejected");
        rejects(()->new LayoutConfig(withOutput("{\"fade\":{\"darkMs\":5001}}")),"fade above five seconds rejected");
        rejects(()->new LayoutConfig(withOutput("{\"fade\":{\"darkMs\":\"2000\"}}")),"fade must be numeric");
        rejects(()->new LayoutConfig(withOutput("{\"fade\":5}")),"fade must be an object");

        System.out.println("FadeContractTest PASS ("+checks+" checks, no network or device access)");
    }
}
