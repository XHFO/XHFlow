package net.xhfo.tvwled;

import java.util.Arrays;

/** Host-side behavioral tests: no Android, device, network or external dependency. */
public final class EffectsContractTest {
    private static int checks;
    private interface Image { int[] pixel(double x,double y); }
    private interface Work { void run(); }
    private static void check(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
    private static void near(double a,double b,double epsilon,String message){check(Math.abs(a-b)<=epsilon,message+" ("+a+" vs "+b+")");}
    private static void rejects(Work run,String message){try{run.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static int[][] edgeColors(){
        int[][] colors=new int[20][3];int[][] bases={{255,0,0},{0,255,0},{0,0,255},{255,255,255}};
        for(int i=0;i<colors.length;i++)colors[i]=bases[i/5].clone();return colors;
    }
    private static EdgeColorEngine engine(int count,int[] indices,String[] edges,double[] u,boolean closed){return new EdgeColorEngine(count,indices,edges,u,closed);}
    private static int[][] read(BlackBarDetector.Probe probe,Image image){
        int[][] out=new int[20][];
        for(int i=0;i<out.length;i++)out[i]=image.pixel(probe.coordinates[i][0]/3839.0,probe.coordinates[i][1]/2159.0);
        return out;
    }
    private static void feed(BlackBarDetector detector,Image image,int count){
        for(int i=0;i<count;i++){BlackBarDetector.Probe p=detector.nextProbe(3840,2160);detector.observe(p,read(p,image));}
    }
    private static final Image FULL=(x,y)->new int[]{80,140,220};
    private static final Image DARK=(x,y)->new int[]{0,0,0};
    private static final Image LETTERBOX=(x,y)->y<0.125||y>0.875?new int[]{0,0,0}:new int[]{80,140,220};
    private static final Image PILLARBOX=(x,y)->x<0.12||x>0.88?new int[]{3,3,3}:new int[]{200,90,40};
    public static void main(String[] args){
        EdgeColorEngine.Point[] full=EdgeColorEngine.plan(EdgeColorEngine.Region.FULL);
        check(full.length==20,"all four edges sampled with five points");
        for(int i=0;i<20;i++){check(full[i].edge.equals(EdgeColorEngine.EDGES[i/5]),"canonical sample edge");check(full[i].x>0&&full[i].x<1&&full[i].y>0&&full[i].y<1,"samples stay inside panel");}
        near(full[0].x,.1,1e-9,"top starts left");near(full[10].x,.9,1e-9,"bottom starts right");near(full[15].y,.9,1e-9,"left starts bottom");
        EdgeColorEngine.Region crop=new EdgeColorEngine.Region(.1,.15,.9,.85);
        EdgeColorEngine.Point[] cp=EdgeColorEngine.plan(crop);int[][] px=EdgeColorEngine.coordinates(cp,3840,2160);
        check(cp[0].y>crop.top&&cp[10].y<crop.bottom,"samples exclude detected horizontal bars");
        check(cp[5].x<crop.right&&cp[15].x>crop.left,"samples exclude detected vertical bars");
        check(px[0][0]>0&&px[19][1]<2160,"pixel coordinates bounded");
        rejects(()->new EdgeColorEngine.Region(0,.4,1,.6),"too narrow content rejected");
        rejects(()->EdgeColorEngine.coordinates(full,1,1),"panel size rejected");
        EdgeColorEngine e=engine(6,new int[]{5,1,3,0},new String[]{"top","right","bottom","left"},new double[]{.5,.5,.5,.5},false);
        int[][] input=edgeColors();int[] mapped=e.map(input,1,false,0);
        check(Arrays.equals(Arrays.copyOfRange(mapped,15,18),new int[]{255,0,0}),"top color assigned to physical index five");
        check(Arrays.equals(Arrays.copyOfRange(mapped,3,6),new int[]{0,255,0}),"right color independent of adjacent edge");
        check(Arrays.equals(Arrays.copyOfRange(mapped,9,12),new int[]{0,0,255}),"bottom color assigned");
        check(Arrays.equals(Arrays.copyOfRange(mapped,0,3),new int[]{255,255,255}),"left color assigned");
        check(Arrays.equals(Arrays.copyOfRange(mapped,6,9),new int[]{0,0,0}),"unassigned physical index remains black");
        check(input[0][0]==255&&input[5][1]==255,"mapping does not mutate capture input");
        check(e.map(input,0,false,0)[15]==0,"zero brightness supported");
        check(e.map(input,.5,false,0)[15]==128,"brightness applied once");
        int[][] gradient=edgeColors();for(int i=0;i<5;i++)gradient[i]=new int[]{i*50,0,0};
        EdgeColorEngine g=engine(3,new int[]{0,1,2},new String[]{"top","top","top"},new double[]{0,.4,1},false);
        int[] linear=g.map(gradient,1,false,0);check(linear[0]==0&&linear[3]==75&&linear[6]==200,"interpolates within edge and clamps its endpoints");
        EdgeColorEngine c=engine(3,new int[]{0,1,2},new String[]{"top","right","bottom"},new double[]{.5,.5,.5},false);
        EdgeColorEngine ring=engine(3,new int[]{0,1,2},new String[]{"top","right","bottom"},new double[]{.5,.5,.5},true);
        int[] softened=c.map(input,1,true,1),wrapped=ring.map(input,1,true,1);
        check(softened[0]==191&&softened[1]==64&&softened[2]==0,"C endpoints do not blur across physical gap");
        check(wrapped[2]==64,"closed layout blurs across its physical cut");
        EdgeColorEngine single=engine(1,new int[]{0},new String[]{"left"},new double[]{.5},true);
        check(single.map(input,1,true,1)[0]==255,"one LED mapping and spatial filter supported");
        int[] goal={255,0,0};EdgeColorEngine temporal=engine(1,new int[]{0},new String[]{"top"},new double[]{.5},false);
        int[] early=temporal.smooth(goal,1000,true,.5),later=temporal.smooth(goal,1200,true,.5);
        check(early[0]>0&&early[0]<100&&later[0]>early[0]&&later[0]<255,"softening approaches new color rather than flashing");
        check(temporal.smooth(goal,1233,false,.5)[0]==255,"disabled softening applies exact input immediately");
        EdgeColorEngine rateA=engine(1,new int[]{0},new String[]{"top"},new double[]{.5},false),rateB=engine(1,new int[]{0},new String[]{"top"},new double[]{.5},false);
        rateA.smooth(goal,1000,true,.5);rateB.smooth(goal,1000,true,.5);
        rateA.smooth(goal,1033,true,.5);int a=rateA.smooth(goal,1066,true,.5)[0],b=rateB.smooth(goal,1066,true,.5)[0];
        check(Math.abs(a-b)<=1,"temporal response independent of send frame subdivision");
        check(rateA.smooth(goal,1100,true,0)[0]==255,"zero strength behaves as passthrough");
        rejects(()->engine(2,new int[]{0,0},new String[]{"top","left"},new double[]{.1,.2},false),"duplicate physical indices rejected");
        rejects(()->g.map(new int[19][3],1,true,.5),"incomplete primary capture rejected");
        int[][] corrupt=edgeColors();corrupt[4][1]=256;rejects(()->g.map(corrupt,1,false,0),"invalid RGB rejected");
        BlackBarDetector detector=new BlackBarDetector();
        feed(detector,DARK,10);check(detector.region()==EdgeColorEngine.Region.FULL,"full blackout never creates bars");
        detector.reset();feed(detector,LETTERBOX,4);check(detector.region().top==0,"crop waits for three same-axis observations");
        feed(detector,LETTERBOX,1);EdgeColorEngine.Region letter=detector.region();
        check(letter.top>0&&letter.top<.2&&letter.bottom<1,"stable letterbox detected");
        check(letter.left==0&&letter.right==1,"letterbox does not create vertical crop");
        EdgeColorEngine.Point[] letterPoints=EdgeColorEngine.plan(letter);check(letterPoints[0].y>.125&&letterPoints[10].y<.875,"detected crop places actual edge probes into picture");
        feed(detector,DARK,12);check(detector.region()==letter,"black fade holds prior region without growth");
        feed(detector,(x,y)->new int[]{20,20,20},8);check(detector.region()==letter,"dim ambiguous scene does not reset crop");
        feed(detector,FULL,6);check(detector.region().top==0&&detector.region().bottom==1,"full-frame bright picture clears crop after hysteresis");
        detector.reset();feed(detector,PILLARBOX,6);EdgeColorEngine.Region pillar=detector.region();
        check(pillar.left>0&&pillar.left<.2&&pillar.top==0,"stable pillarbox detected independently");
        EdgeColorEngine.Point[] pillarPoints=EdgeColorEngine.plan(pillar);check(pillarPoints[15].x>.12&&pillarPoints[5].x<.88,"pillarbox edge probes reach picture");
        detector.reset();feed(detector,(x,y)->y<.125?new int[]{0,0,0}:new int[]{80,140,220},10);check(detector.region().top==0,"single dark band rejected as asymmetric content");
        detector.reset();feed(detector,(x,y)->y<.125||y>.875?new int[]{18,18,18}:new int[]{80,140,220},10);check(detector.region().top==0,"gray dim borders do not silently become black crop");
        detector.reset();feed(detector,LETTERBOX,2);feed(detector,DARK,2);feed(detector,LETTERBOX,4);check(detector.region().top==0,"unknown observations break unconfirmed crop streak");
        detector.reset();feed(detector,(x,y)->y<.125||y>.875?(x<.5?new int[]{13,0,0}:new int[]{0,0,13}):new int[]{80,140,220},10);check(detector.region().top==0,"dark colored picture edges are not uniform black bars");
        detector.reset();feed(detector,LETTERBOX,4);detector.nextProbe(3840,2160);BlackBarDetector.Probe resume=detector.nextProbe(3840,2160);detector.observe(resume,read(resume,LETTERBOX));feed(detector,LETTERBOX,1);check(detector.region().top==0,"missed capture breaks candidate streak");
        detector.reset();feed(detector,LETTERBOX,4);BlackBarDetector.Probe invalid=detector.nextProbe(3840,2160);rejects(()->detector.observe(invalid,new int[19][3]),"failed capture rejected");feed(detector,LETTERBOX,2);check(detector.region().top==0,"invalid capture breaks candidate streak");
        BlackBarDetector.Probe old=detector.nextProbe(3840,2160),latest=detector.nextProbe(3840,2160);
        rejects(()->detector.observe(old,read(old,FULL)),"out-of-order probe rejected");
        detector.observe(latest,read(latest,FULL));
        System.out.println("EffectsContractTest PASS ("+checks+" checks)");
    }
}
