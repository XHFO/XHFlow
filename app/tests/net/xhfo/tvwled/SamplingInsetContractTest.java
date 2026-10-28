package net.xhfo.tvwled;

import java.util.Arrays;

/** Verifies user-controlled inset moves actual capture points without altering edge mapping. */
public final class SamplingInsetContractTest {
    private static int checks;
    private static void check(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
    private static void near(double got,double expected,String message){check(Math.abs(got-expected)<1e-9,message);}
    private static void rejects(double inset){try{EdgeColorEngine.plan(EdgeColorEngine.Region.FULL,inset);throw new AssertionError("Invalid inset accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[]args){
        EdgeColorEngine.Point[] legacy=EdgeColorEngine.plan(EdgeColorEngine.Region.FULL),defaults=EdgeColorEngine.plan(EdgeColorEngine.Region.FULL,.045);
        for(int i=0;i<20;i++){near(defaults[i].x,legacy[i].x,"default x remains backward compatible");near(defaults[i].y,legacy[i].y,"default y remains backward compatible");}
        EdgeColorEngine.Point[] zero=EdgeColorEngine.plan(EdgeColorEngine.Region.FULL,0),deep=EdgeColorEngine.plan(EdgeColorEngine.Region.FULL,.2);
        near(zero[0].y,0,"zero inset reaches top edge");near(zero[5].x,1,"zero inset reaches right edge");near(zero[10].y,1,"zero inset reaches bottom edge");near(zero[15].x,0,"zero inset reaches left edge");
        near(deep[0].y,.2,"top inset is used");near(deep[5].x,.8,"right inset is used");near(deep[10].y,.8,"bottom inset is used");near(deep[15].x,.2,"left inset is used");
        for(int i=0;i<20;i++)near(deep[i].position,zero[i].position,"along-edge u independent of inset");
        EdgeColorEngine.Region region=new EdgeColorEngine.Region(.1,.15,.9,.85);
        EdgeColorEngine.Point[] crop=EdgeColorEngine.plan(region,.2);
        near(crop[0].y,.29,"inset applies after horizontal crop");near(crop[10].y,.71,"bottom inset applies inside active region");near(crop[5].x,.74,"right inset applies after vertical crop");near(crop[15].x,.26,"left inset applies inside active region");
        int[][] actual=EdgeColorEngine.coordinates(crop,3840,2160);
        check(actual[0][1]==626&&actual[5][0]==2841,"changed inset reaches HAL pixel coordinate plan");
        check(actual[10][1]==1533&&actual[15][0]==998,"opposite edges use corresponding pixel coordinates");
        BlackBarDetector a=new BlackBarDetector(),b=new BlackBarDetector();
        int[][] before=a.nextProbe(3840,2160).coordinates;EdgeColorEngine.plan(region,.2);int[][] after=b.nextProbe(3840,2160).coordinates;
        check(Arrays.deepEquals(before,after),"user inset cannot move full-panel black-bar detection probes");
        rejects(-.01);rejects(.20001);rejects(Double.NaN);
        System.out.println("SamplingInsetContractTest PASS ("+checks+" checks)");
    }
}
