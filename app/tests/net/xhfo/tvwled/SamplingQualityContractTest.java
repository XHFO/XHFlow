package net.xhfo.tvwled;

import java.util.Arrays;

/** Sampling quality contracts: legacy equivalence and independently checked 10-point edge mapping. */
public final class SamplingQualityContractTest {
    private static int checks;
    private interface Work {void run();}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void close(double expected,double actual,String label){check(Math.abs(expected-actual)<1e-12,label+" expected="+expected+" actual="+actual);}
    private static void rejects(Work work,String label){try{work.run();throw new AssertionError(label+" accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static EdgeColorEngine engine(int samples){return new EdgeColorEngine(4,new int[]{0,1,2,3},new String[]{"top","right","bottom","left"},new double[]{.05,.25,.5,.95},false,samples);}
    public static void main(String[] args){
        EdgeColorEngine.Region crop=new EdgeColorEngine.Region(.1,.125,.9,.875);
        EdgeColorEngine.Point[] defaults=EdgeColorEngine.plan(crop,.045),explicit=EdgeColorEngine.plan(crop,.045,5);
        check(defaults.length==20&&explicit.length==20,"Legacy/default quality remains 20 points");
        for(int i=0;i<20;i++){
            check(defaults[i].edge.equals(explicit[i].edge),"Legacy edge order "+i);
            close(defaults[i].position,explicit[i].position,"Legacy edge position "+i);
            close(defaults[i].x,explicit[i].x,"Legacy x "+i);close(defaults[i].y,explicit[i].y,"Legacy y "+i);
        }
        int[][] standard=new int[20][3];for(int i=0;i<20;i++){standard[i][0]=(i*13)%256;standard[i][1]=(i*7)%256;standard[i][2]=(i*3)%256;}
        EdgeColorEngine legacy=new EdgeColorEngine(4,new int[]{0,1,2,3},new String[]{"top","right","bottom","left"},new double[]{.05,.25,.5,.95},false);
        check(Arrays.equals(legacy.map(standard,.6,true,.35),engine(5).map(standard,.6,true,.35)),"Default mapping exactly matches explicit standard quality");

        EdgeColorEngine.Point[] fine=EdgeColorEngine.plan(EdgeColorEngine.Region.FULL,.1,10);
        check(fine.length==40,"Fine quality has exactly 40 primary points");
        for(int edge=0;edge<4;edge++)for(int point=0;point<10;point++){
            EdgeColorEngine.Point actual=fine[edge*10+point];double u=.05+.1*point;
            check(actual.edge.equals(EdgeColorEngine.EDGES[edge]),"Fine edge order "+edge+"/"+point);close(u,actual.position,"Fine position "+edge+"/"+point);
            if(edge==0){close(u,actual.x,"Top x");close(.1,actual.y,"Top inset");}
            else if(edge==1){close(.9,actual.x,"Right inset");close(u,actual.y,"Right y");}
            else if(edge==2){close(1-u,actual.x,"Bottom reversed x");close(.9,actual.y,"Bottom inset");}
            else{close(.1,actual.x,"Left inset");close(1-u,actual.y,"Left reversed y");}
        }
        EdgeColorEngine.Point[] cropped=EdgeColorEngine.plan(crop,.1,10);
        close(.14,cropped[0].x,"Fine crop then horizontal projection");close(.2,cropped[0].y,"Fine crop then top inset");
        close(.82,cropped[10].x,"Fine right inset applied inside crop");close(.1625,cropped[10].y,"Fine right start inside crop");
        int[][] coords=EdgeColorEngine.coordinates(cropped,3840,2160);
        check(coords.length==40,"All forty coordinates reach sampler");
        for(int[] coordinate:coords)check(coordinate[0]>=0&&coordinate[0]<3840&&coordinate[1]>=0&&coordinate[1]<2160,"Fine pixel coordinates stay on panel");

        int[][] colors=new int[40][3];
        for(int edge=0;edge<4;edge++)for(int point=0;point<10;point++)colors[edge*10+point]=new int[]{edge*50+point*5,point*20,200-edge*30};
        int[] mapped=engine(10).map(colors,1,false,0);
        check(Arrays.equals(mapped,new int[]{0,0,200,60,40,170,123,90,140,195,180,110}),"Ten-point interpolation follows each edge independently");
        EdgeColorEngine boundaries=new EdgeColorEngine(4,new int[]{0,1,2,3},new String[]{"top","top","right","left"},new double[]{0,1,.15,.35},false,10);
        check(Arrays.equals(boundaries.map(colors,1,false,0),new int[]{0,0,200,45,180,200,55,20,170,165,60,110}),"Endpoints clamp locally; exact sample centers retain their colors");
        EdgeColorEngine sparse=new EdgeColorEngine(3,new int[]{2},new String[]{"bottom"},new double[]{.25},true,10);
        check(Arrays.equals(sparse.map(colors,1,true,1),new int[]{0,0,0,0,0,0,110,40,140}),"Fine quality preserves sparse indices and single-LED softening");
        check(Arrays.equals(engine(10).map(colors,0,true,1),new int[12]),"Fine quality honors zero brightness");
        int[][] copy=new int[40][];for(int i=0;i<40;i++)copy[i]=colors[i].clone();engine(10).map(colors,.6,true,.35);check(Arrays.deepEquals(colors,copy),"Fine mapping does not change captured samples");
        rejects(()->engine(10).map(standard,1,false,0),"Fine engine rejects standard sample count");
        rejects(()->engine(5).map(colors,1,false,0),"Standard engine rejects fine sample count");
        for(int invalid:new int[]{0,1,4,6,20,40}){
            rejects(()->engine(invalid),"Invalid constructor quality "+invalid);rejects(()->EdgeColorEngine.plan(crop,.1,invalid),"Invalid plan quality "+invalid);
        }
        rejects(()->EdgeColorEngine.coordinates(new EdgeColorEngine.Point[41],3840,2160),"Sampling cap forty");
        rejects(()->EdgeColorEngine.plan(crop,-.1,10),"Fine inset rejects negative");rejects(()->EdgeColorEngine.plan(crop,Double.NaN,10),"Fine inset rejects NaN");
        BlackBarDetector detector=new BlackBarDetector();check(detector.nextProbe(3840,2160).coordinates.length==20,"Black-bar probe remains twenty points");
        System.out.println("SamplingQualityContractTest PASS ("+checks+" checks)");
    }
}
