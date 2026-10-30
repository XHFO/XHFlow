package net.xhfo.tvwled;

import java.util.Arrays;

/** Full mapping/calibration/spatial/temporal path: black screen regions must stay dark. */
public final class DarkColorContractTest {
    private static int checks;
    private static void check(boolean good,String label){checks++;if(!good)throw new AssertionError(label);}
    private static void equal(int[] expected,int[] actual,String label){check(Arrays.equals(expected,actual),label+" expected="+Arrays.toString(expected)+" actual="+Arrays.toString(actual));}
    private static int peak(int[] rgb,int index){int base=index*3;return Math.max(rgb[base],Math.max(rgb[base+1],rgb[base+2]));}
    private static int[] pixel(int[] rgb,int index){return Arrays.copyOfRange(rgb,index*3,index*3+3);}
    private static int[][] capture(int points,int... rgb){int[][] colors=new int[points*4][];for(int i=0;i<colors.length;i++)colors[i]=rgb.clone();return colors;}
    private static void edge(int[][] colors,int points,int edge,int... rgb){for(int i=0;i<points;i++)colors[edge*points+i]=rgb.clone();}
    private static EdgeColorEngine single(int points){return new EdgeColorEngine(1,new int[]{0},new String[]{"top"},new double[]{.5},false,points);}
    private static ColorCorrection correction(int count,int[] assigned,int style){
        if(style==1)return new ColorCorrection(count,assigned,1.41,1.17,13,1,1,1,1);
        if(style==2)return new ColorCorrection(count,assigned,2,2,64,3,2,2,2);
        return new ColorCorrection(count,assigned,1,1,0,1,1,1,1);
    }
    private static int[] emit(EdgeColorEngine engine,int[][] colors,ColorCorrection correction,double brightness,boolean spatial,double strength){
        return engine.smooth(engine.output(colors,correction,brightness,spatial,strength),1000,false,0);
    }
    private static int[] legacy(EdgeColorEngine engine,int[][] colors,ColorCorrection correction,double brightness,boolean spatial,double strength){
        int[] mapped=engine.map(colors,correction.isIdentity()?brightness:1,spatial,strength);
        return correction.isIdentity()?mapped:correction.apply(mapped,brightness);
    }
    private static double limit(int peak,double brightness){
        if(peak<=24)return peak*(4.0/24)*brightness;
        if(peak>=40)return 255;
        double position=(peak-24)/16.0;
        return (4+251*position*position*(3-2*position))*brightness;
    }
    public static void main(String[] args){
        for(int points:new int[]{5,10}){
            for(int style=0;style<3;style++){
                ColorCorrection calibration=correction(1,new int[]{0},style);
                for(double brightness:new double[]{0,.6,1}){
                    for(int value=0;value<=24;value++){
                        int[][][] dark={capture(points,value,value,value),capture(points,value,value/2,0),capture(points,0,value/2,value)};
                        for(int[][] colors:dark){
                            EdgeColorEngine engine=single(points);
                            int[] out=emit(engine,colors,calibration,brightness,true,1);
                            check(peak(out,0)<5,"Near-black remains below five, points="+points+" calibration="+style+" source="+value);
                            check(peak(out,0)<=Math.round(value*(4.0/24)*brightness),"Black cap follows global brightness");
                            if(value==0||brightness==0)equal(new int[3],out,"Pure black and zero brightness remain fully off");
                        }
                    }
                }
                for(int[] color:new int[][]{{40,0,0},{0,40,0},{0,0,40},{0,0,96},{40,20,60},{128,128,128},{255,255,255}}){
                    EdgeColorEngine engine=single(points);int[][] colors=capture(points,color);
                    equal(legacy(engine,colors,calibration,1,true,.6),emit(engine,colors,calibration,1,true,.6),"Ordinary colors, including saturated deep blue, retain calibrated output");
                }
            }

            EdgeColorEngine blue=single(points);ColorCorrection current=correction(1,new int[]{0},1);
            int[][] deepBlue=capture(points,0,0,32);
            equal(legacy(blue,deepBlue,current,1,true,.6),emit(blue,deepBlue,current,1,true,.6),"Current user calibration preserves deep blue at peak 32 inside the shoulder");

            // The transition between protected blacks and ordinary colors must not flash at a threshold.
            for(double brightness:new double[]{.25,.6,1}){
                int previous=-1;
                for(int value=0;value<=64;value++){
                    int[] out=emit(single(points),capture(points,value,value,value),correction(1,new int[]{0},2),brightness,false,0);
                    int maximum=peak(out,0);
                    check(maximum>=previous,"Increasing gray input has monotonic output across the black shoulder");
                    check(maximum<=Math.round(limit(value,brightness)),"Output respects independently calculated smoothstep ceiling");
                    previous=maximum;
                }
            }

            int[] indices={5,1,3,0};String[] edges={"top","right","bottom","left"};double[] positions={.5,.5,.5,.5};
            int[][] mixed=capture(points,255,255,255);edge(mixed,points,0,0,0,0);edge(mixed,points,1,0,0,96);edge(mixed,points,2,24,24,24);
            for(boolean closed:new boolean[]{false,true}){
                EdgeColorEngine engine=new EdgeColorEngine(7,indices,edges,positions,closed,points);
                int[] out=emit(engine,mixed,correction(7,indices,1),1,true,1);
                equal(new int[3],pixel(out,5),"Neighboring white cannot lift a mapped pure-black LED");
                check(peak(out,3)<5,"Neighboring colors cannot lift a mapped near-black LED");
                check(peak(out,1)>=32,"Saturated blue remains eligible for ordinary output");
                for(int unused:new int[]{2,4,6})equal(new int[3],pixel(out,unused),"Positive offset leaves unassigned physical indices off");
                equal(new int[21],emit(engine,mixed,correction(7,indices,2),0,true,1),"Zero brightness suppresses the complete sparse strip");
            }

            // Preserve the established C versus closed-strip neighbor rules outside black regions.
            int[][] boundary=capture(points,0,0,0);edge(boundary,points,1,0,0,64);edge(boundary,points,2,128,128,128);
            for(boolean closed:new boolean[]{false,true}){
                EdgeColorEngine engine=new EdgeColorEngine(3,new int[]{0,1,2},new String[]{"top","right","bottom"},new double[]{.5,.5,.5},closed,points);
                int[] out=emit(engine,boundary,correction(3,new int[]{0,1,2},0),1,true,1);
                equal(new int[3],pixel(out,0),"C and closed-strip black endpoints reject neighbor leakage");
                equal(new int[]{32,32,64},pixel(out,1),"Interior spatial filtering retains established weights");
                equal(closed?new int[]{64,64,80}:new int[]{96,96,112},pixel(out,2),"Physical cut keeps the correct neighbor semantics");
            }

            EdgeColorEngine history=single(points);ColorCorrection calibration=correction(1,new int[]{0},1);
            history.smooth(history.output(capture(points,255,255,255),calibration,1,false,0),1000,false,0);
            int[] blackout=history.smooth(history.output(capture(points,0,0,0),calibration,1,true,1),1033,true,1);
            equal(new int[3],blackout,"Bright-to-black clears immediately despite maximum temporal softening");
            int[] next=history.smooth(history.output(capture(points,255,255,255),calibration,1,false,0),1066,true,1);
            EdgeColorEngine fresh=single(points);
            equal(fresh.smooth(fresh.output(capture(points,255,255,255),calibration,1,false,0),1066,true,1),next,"Re-lighting begins from cleared history without old white returning");

            // Capture, published frame, and sender output cannot mutate one another.
            int[][] input=capture(points,0,0,24);EdgeColorEngine immutable=single(points);
            LedColorFrame frame=immutable.output(input,calibration,1,false,0);
            int[] expected=immutable.smooth(frame,1000,false,0);input[0][0]=255;
            for(int[] sample:input)Arrays.fill(sample,255);
            int[] returned=immutable.smooth(frame,1033,false,0);equal(expected,returned,"Published goal and cap do not depend on later capture mutations");
            Arrays.fill(returned,255);
            equal(expected,immutable.smooth(frame,1066,false,0),"Sender arrays cannot change the published frame");

            EdgeColorEngine interpolated=new EdgeColorEngine(3,new int[]{0,1,2},new String[]{"top","top","top"},new double[]{0,.5,1},false,points);
            int[][] ramp=capture(points,0,0,0);
            for(int i=0;i<points;i++){int value=(int)Math.round(i*255.0/(points-1));ramp[i]=new int[]{value,value,value};}
            int[] mapped=emit(interpolated,ramp,correction(3,new int[]{0,1,2},0),1,false,0);
            equal(new int[3],pixel(mapped,0),"Interpolated edge starts with protected black");
            check(peak(mapped,1)>=127&&peak(mapped,1)<=128,"Interpolation still spans the sample edge");
            equal(new int[]{255,255,255},pixel(mapped,2),"Interpolated edge keeps its white endpoint");
        }
        System.out.println("DarkColorContractTest PASS ("+checks+" checks)");
    }
}
