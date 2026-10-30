package net.xhfo.tvwled;

import java.util.Arrays;

/** Sparse-black replacement through the real mapper, calibration and output smoother. */
public final class SparseBlackFilterContractTest {
    private static int checks;
    private static void check(boolean good,String label){checks++;if(!good)throw new AssertionError(label);}
    private static void equal(int[] expected,int[] actual,String label){check(Arrays.equals(expected,actual),label+" expected="+Arrays.toString(expected)+" actual="+Arrays.toString(actual));}
    private static void near(double expected,double actual,String label){check(Math.abs(expected-actual)<1e-9,label+" expected="+expected+" actual="+actual);}
    private static int peak(int[] output,int index){int base=index*3;return Math.max(output[base],Math.max(output[base+1],output[base+2]));}
    private static int[] pixel(int[] output,int index){return Arrays.copyOfRange(output,index*3,index*3+3);}
    private static int distance(int[] a,int[] b){int out=0;for(int i=0;i<a.length;i++)out=Math.max(out,Math.abs(a[i]-b[i]));return out;}
    private static int[][] picture(int count,int... color){int[][] out=new int[count][];for(int i=0;i<count;i++)out[i]=color.clone();return out;}
    private static final class Fixture {
        final int points,count,total;
        final int[] indices;
        final EdgeColorEngine engine;
        final ColorCorrection neutral,current,extreme;
        Fixture(int points,boolean closed,boolean sparse){
            this.points=points;count=points*4;total=sparse?count*2+2:count;
            indices=new int[count];String[] edges=new String[count];double[] positions=new double[count];
            for(int i=0;i<count;i++){indices[i]=sparse?i*2+1:i;edges[i]=EdgeColorEngine.EDGES[i/points];positions[i]=((i%points)+.5)/points;}
            engine=new EdgeColorEngine(total,indices,edges,positions,closed,points);
            neutral=new ColorCorrection(total,indices,1,1,0,1,1,1,1);
            current=new ColorCorrection(total,indices,1.41,1.17,13,1,1,1,1);
            extreme=new ColorCorrection(total,indices,2,2,64,3,2,2,2);
        }
        LedColorFrame frame(int[][] colors,ColorCorrection correction,double brightness,boolean spatial,double strength,boolean enabled,double threshold){
            return engine.output(colors,correction,brightness,spatial,strength,enabled,threshold);
        }
        int[] emit(int[][] colors,ColorCorrection correction,double brightness,boolean enabled,double threshold){
            return engine.smooth(frame(colors,correction,brightness,false,0,enabled,threshold),1000,false,0);
        }
        int[] emit(int[][] colors){return emit(colors,neutral,1,true,.15);}
    }

    public static void main(String[] args){
        for(int points:new int[]{5,10}){
            for(boolean sparse:new boolean[]{false,true}){
                Fixture f=new Fixture(points,false,sparse);
                int[][] blue=picture(f.count,0,0,120);blue[3]=new int[]{0,0,0};
                LedColorFrame frame=f.frame(blue,f.neutral,1,false,0,true,.15);
                int[] replaced=f.engine.smooth(frame,1000,false,0);
                equal(new int[]{0,0,120},pixel(replaced,f.indices[3]),"A first isolated black point immediately uses nearby blue");
                check(frame.filledLeds>0,"Sparse replacement is visible in published frame metadata");
                near(1.0/f.count,frame.blackRatio,"Occupancy counts assigned LEDs rather than unused controller pixels");
                int[] steady=f.engine.smooth(f.frame(picture(f.count,0,0,120),f.current,1,true,1,true,.15),2000,false,0);
                int[] firstDark=f.engine.smooth(f.frame(blue,f.current,1,true,1,true,.15),2033,true,1);
                equal(steady,firstDark,"First sparse black capture stays steady even with maximum temporal softening");
                for(int i=0;i<f.total;i++){
                    boolean assigned=false;for(int at:f.indices)assigned|=at==i;
                    if(!assigned)equal(new int[3],pixel(replaced,i),"Unassigned physical pixels remain black");
                }

                int[] disabled=f.emit(blue,f.neutral,1,false,.15);
                equal(new int[3],pixel(disabled,f.indices[3]),"Disabling replacement retains the protected black pixel");
                equal(new int[3],pixel(f.emit(blue,f.neutral,1,true,.01),f.indices[3]),"The configured minimum threshold can reject the same sparse patch");
                int[] legacy=f.engine.smooth(f.engine.output(blue,f.neutral,1,false,0),1100,false,0);
                equal(disabled,legacy,"Five-argument output remains the 0.5.3 black-protection path");
                equal(new int[f.total*3],f.emit(blue,f.extreme,0,true,.15),"Zero global brightness wins over replacement and extreme calibration");

                // At and above the configured maximum, black is intentional rather than noise.
                int[][] broad=picture(f.count,0,0,120);int dark=f.count/5;
                for(int i=0;i<dark;i++)broad[i]=new int[]{0,0,0};
                for(double threshold:new double[]{.15,.20}){
                    LedColorFrame broadFrame=f.frame(broad,f.current,1,true,1,true,threshold);
                    int[] out=f.engine.smooth(broadFrame,1200,false,0);
                    check(broadFrame.filledLeds==0,"At/above the proportion threshold no black replacement is performed");
                    for(int i=0;i<dark;i++)equal(new int[3],pixel(out,f.indices[i]),"Large black areas remain off despite positive offset and neighboring light");
                }

                // A black frame must clear even an already-running bright temporal filter.
                f.engine.smooth(f.frame(picture(f.count,255,255,255),f.extreme,1,true,1,true,.5),2000,false,0);
                LedColorFrame blackout=f.frame(picture(f.count,0,0,0),f.extreme,1,true,1,true,.5);
                equal(new int[f.total*3],f.engine.smooth(blackout,2033,true,1),"A whole-screen blackout clears immediately at the largest supported threshold");
                near(1,blackout.blackRatio,"Whole black frame reports full black occupancy");
                check(blackout.filledLeds==0,"Blackout never fabricates a donor color");
                LedColorFrame noDonor=f.frame(picture(f.count,39,39,39),f.current,1,false,0,true,.5);
                check(noDonor.filledLeds==0,"Uniform shoulder-level darkness cannot borrow colors from its own replacements");

                // Small patches that alternate around the black cutoff must not switch off.
                int[][] patch=picture(f.count,0,0,120);patch[3]=new int[]{24,24,24};
                int[] at24=f.emit(patch);patch[3]=new int[]{25,25,25};int[] at25=f.emit(patch);
                check(distance(pixel(at24,f.indices[3]),pixel(at25,f.indices[3]))<=4,"24/25 cutoff jitter blends locally rather than dropping into black protection");
                check(peak(at24,f.indices[3])>100&&peak(at25,f.indices[3])>100,"Both sides of the old cutoff keep a stable nearby color");
                int[] previous=pixel(at24,f.indices[3]);
                for(int value=25;value<=40;value++){
                    patch[3]=new int[]{value,value,value};int[] next=pixel(f.emit(patch),f.indices[3]);
                    check(distance(previous,next)<=22,"The local shoulder transitions smoothly back to the real sample");previous=next;
                }

                // Neighbor lookups must use the original sources, not cascade new colors.
                int[][] run=picture(f.count,0,120,0);run[2]=new int[]{120,0,0};run[3]=new int[3];run[4]=new int[3];run[5]=new int[]{0,0,120};
                int[] gradient=f.emit(run,f.neutral,1,true,.5);
                equal(new int[]{120,0,60},pixel(gradient,f.indices[3]),"First point in a black run interpolates original bounding colors");
                equal(new int[]{60,0,120},pixel(gradient,f.indices[4]),"Second point does not recursively interpolate the first replacement");

                int[][] darkColor=picture(f.count,0,0,40);darkColor[2]=new int[]{40,0,0};darkColor[3]=new int[3];darkColor[4]=new int[]{0,0,40};
                int[] blended=pixel(f.emit(darkColor),f.indices[3]);
                check(blended[0]>=40&&blended[2]>=40,"Mixing dark red and blue donors does not fall back into the black guard");
                check(blended[1]==0,"Dark-color interpolation does not add an unrelated white channel");

                // Publishing protects both the source sample and the correction/limit pairing.
                int[][] input=picture(f.count,0,0,120);input[3]=new int[3];
                LedColorFrame immutable=f.frame(input,f.current,1,true,.4,true,.15);
                int[] baseline=f.engine.smooth(immutable,3000,false,0);
                for(int[] sample:input)Arrays.fill(sample,0);
                equal(baseline,f.engine.smooth(immutable,3033,false,0),"Mutating a later capture cannot change the published replacement and limits");
                Arrays.fill(baseline,255);
                check(peak(f.engine.smooth(immutable,3066,false,0),f.indices[3])<255,"Returned output arrays cannot mutate the protected frame");
            }

            Fixture open=new Fixture(points,false,false),closed=new Fixture(points,true,false);
            int[][] ends=picture(open.count,120,0,0);ends[0]=new int[3];ends[open.count-1]=new int[]{0,0,120};
            equal(new int[]{120,0,0},pixel(open.emit(ends),0),"C-shaped endpoint cannot borrow across its physical opening");
            equal(new int[]{120,0,120},pixel(closed.emit(ends),0),"Closed strip may interpolate the last and first physical neighbors");

            // Smooth occupancy removes a hard on/off boundary at the proportion threshold.
            Fixture transition=new Fixture(points,false,false);
            int[][] scene=picture(transition.count,0,0,120);
            int black=transition.count/10;
            for(int i=0;i<black;i++)scene[i*3+1]=new int[3];
            int shoulderCount=transition.count/20;
            int previous=-1;
            for(int value=24;value<=40;value++){
                for(int i=0;i<shoulderCount;i++)scene[transition.count-1-i]=new int[]{value,value,value};
                int out=peak(transition.emit(scene),1);
                check(out>=previous,"Shrinking occupancy does not reverse a stable neighbor color");
                if(previous>=0)check(out-previous<45,"A one-level shoulder change cannot cause an all-or-nothing proportion flash");
                previous=out;
            }
            check(previous==120,"Below the occupancy shoulder, sparse black receives the full nearby color");
        }
        System.out.println("SparseBlackFilterContractTest PASS ("+checks+" checks)");
    }
}
