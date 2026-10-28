package net.xhfo.tvwled;

import java.util.Arrays;

/** Independent reference vectors and sparse-output invariants for color correction. */
public final class ColorCorrectionContractTest {
    private static int checks;
    private interface Work {void run();}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void equal(int[] expected,int[] actual,String label){check(Arrays.equals(expected,actual),label+" expected="+Arrays.toString(expected)+" actual="+Arrays.toString(actual));}
    private static void rejects(Work work,String label){try{work.run();throw new AssertionError(label+" accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static ColorCorrection neutral(int count,int... assigned){return new ColorCorrection(count,assigned,1,1,0,1,1,1,1);}
    private static ColorCorrection one(double saturation,double gain,double offset,double gamma,double red,double green,double blue){return new ColorCorrection(1,new int[]{0},saturation,gain,offset,gamma,red,green,blue);}
    public static void main(String[] args){
        ColorCorrection identity=neutral(1,0);
        check(identity.isIdentity(),"Neutral settings expose the original mapping path");
        equal(new int[]{0,127,255},identity.apply(new int[]{0,127,255},1),"Identity is exact");
        equal(new int[]{0,64,128},identity.apply(new int[]{0,127,255},.5),"Brightness uses nearest rounding, last");
        for(int value=0;value<=255;value++){
            int[] input={value,255-value,value/2};
            int[] expected={(int)Math.round(value*.6),(int)Math.round((255-value)*.6),(int)Math.round((value/2)*.6)};
            equal(expected,identity.apply(input,.6),"Default brightness exact over all channel levels "+value);
            equal(new int[]{value,255-value,value/2},input,"Source remains unchanged "+value);
        }
        ColorCorrection gray=one(0,1,0,1,1,1,1);
        equal(new int[]{54,54,54},gray.apply(new int[]{255,0,0},1),"Rec709 red luma");
        equal(new int[]{182,182,182},gray.apply(new int[]{0,255,0},1),"Rec709 green luma");
        equal(new int[]{18,18,18},gray.apply(new int[]{0,0,255},1),"Rec709 blue luma");
        equal(new int[]{100,100,100},one(2,1,0,1,1,1,1).apply(new int[]{100,100,100},1),"Gray is saturation invariant");
        equal(new int[]{182,22,62},one(2,1,0,1,1,1,1).apply(new int[]{120,40,60},1),"Double saturation reference");
        equal(new int[]{100,160,240},one(1,2,0,1,1,1,1).apply(new int[]{50,80,120},1),"Global gain");
        equal(new int[]{200,100,50},one(1,1,0,1,2,1,.5).apply(new int[]{100,100,100},1),"RGB channel balance");
        equal(new int[]{255,255,255},one(1,2,64,1,2,2,2).apply(new int[]{200,200,200},1),"Upper clipping");
        equal(new int[]{0,0,0},one(1,1,-64,1,1,1,1).apply(new int[]{0,32,64},1),"Negative offset clips to black");
        equal(new int[]{64,84,164},one(1,1,64,1,1,1,1).apply(new int[]{0,20,100},1),"Positive offset levels");
        equal(new int[]{20,30,40},one(1,2,10,1,1,1,1).apply(new int[]{5,10,15},1),"Offset follows gain");
        equal(new int[]{0,64,255},one(1,1,0,.5,1,1,1).apply(new int[]{0,128,255},1),"Gamma half squares normalized input");
        equal(new int[]{0,128,255},one(1,1,0,2,1,1,1).apply(new int[]{0,64,255},1),"Gamma two square root reference");
        equal(new int[]{64,64,64},one(1,1,0,2,1,1,1).apply(new int[]{64,64,64},.5),"Brightness follows gamma");
        equal(new int[]{128,128,128},one(1,2,64,3,2,2,2).apply(new int[]{255,255,255},.5),"Brightness follows upper clamp");
        equal(new int[]{0,0,0},one(2,2,64,3,2,2,2).apply(new int[]{255,128,64},0),"Brightness zero always black");
        int[] assigned={2,0};
        ColorCorrection sparse=new ColorCorrection(4,assigned,1,1,64,1,1,1,1);assigned[0]=1;
        int[] allBlack=new int[12];
        equal(new int[]{64,64,64,0,0,0,64,64,64,0,0,0},sparse.apply(allBlack,1),"Offset touches assigned indices only and mapping is copied");
        equal(new int[12],neutral(4).apply(new int[12],1),"Empty mapping is dark");
        equal(new int[]{1,2,3,0,0,0},neutral(2,0).apply(new int[]{1,2,3,255,255,255},1),"Unused input pixels remain dark");
        int[] once=identity.apply(new int[]{1,2,3},1);once[0]=99;
        equal(new int[]{1,2,3},identity.apply(new int[]{1,2,3},1),"Output has no shared mutable history");
        check(!one(1,1,0,1,1,1,.999).isIdentity(),"Channel change disables original path");
        rejects(()->neutral(0,0),"Zero LED count");rejects(()->neutral(481,0),"Beyond one-packet limit");
        rejects(()->new ColorCorrection(1,null,1,1,0,1,1,1,1),"Null assigned mapping");
        rejects(()->neutral(1,-1),"Negative assigned index");rejects(()->neutral(1,1),"Assigned index beyond strip");rejects(()->neutral(2,0,0),"Duplicate assigned index");
        rejects(()->identity.apply(null,1),"Null input");rejects(()->identity.apply(new int[4],1),"Non-RGB length");
        rejects(()->identity.apply(new int[]{-1,0,0},1),"Negative input channel");rejects(()->identity.apply(new int[]{0,256,0},0),"Input validation remains active at zero brightness");
        rejects(()->identity.apply(new int[3],-1),"Negative brightness");rejects(()->identity.apply(new int[3],1.001),"Excess brightness");rejects(()->identity.apply(new int[3],Double.NaN),"NaN brightness");
        for(int parameter=0;parameter<7;parameter++){
            final int at=parameter;
            for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
                final double bad=invalid;
                rejects(()->{double[] p={1,1,0,1,1,1,1};p[at]=bad;one(p[0],p[1],p[2],p[3],p[4],p[5],p[6]);},"Nonfinite parameter "+at);
            }
        }
        rejects(()->one(-.01,1,0,1,1,1,1),"Negative saturation");rejects(()->one(2.01,1,0,1,1,1,1),"Excess saturation");
        rejects(()->one(1,-.01,0,1,1,1,1),"Negative gain");rejects(()->one(1,2.01,0,1,1,1,1),"Excess gain");
        rejects(()->one(1,1,-64.01,1,1,1,1),"Low offset");rejects(()->one(1,1,64.01,1,1,1,1),"High offset");
        rejects(()->one(1,1,0,.49,1,1,1),"Low gamma");rejects(()->one(1,1,0,3.01,1,1,1),"High gamma");
        rejects(()->one(1,1,0,1,-.01,1,1),"Negative red gain");rejects(()->one(1,1,0,1,1,2.01,1),"Excess green gain");rejects(()->one(1,1,0,1,1,1,2.01),"Excess blue gain");
        one(0,0,-64,.5,0,0,0);one(2,2,64,3,2,2,2);checks+=2;
        System.out.println("ColorCorrectionContractTest PASS ("+checks+" checks)");
    }
}
