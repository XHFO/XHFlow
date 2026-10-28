package net.xhfo.tvwled;

/** Conservative point-based bar detection, not a full-frame content classifier. */
final class BlackBarDetector {
    static final int HORIZONTAL=0,VERTICAL=1;
    private static final double[] DEPTHS={0.025,0.055,0.095,0.135,0.19};
    private static final int BLACK=14,BRIGHT=36,STABLE=3;
    static final class Probe {
        final int axis;
        final int[][] coordinates;
        private Probe(int axis,int[][] coordinates){this.axis=axis;this.coordinates=coordinates;}
    }
    private final double[] pending={Double.NaN,Double.NaN};
    private final int[] stable={0,0};
    private int nextAxis;
    private Probe expected;
    private EdgeColorEngine.Region region=EdgeColorEngine.Region.FULL;
    EdgeColorEngine.Region region(){return region;}
    void reset(){region=EdgeColorEngine.Region.FULL;nextAxis=0;expected=null;pending[0]=pending[1]=Double.NaN;stable[0]=stable[1]=0;}
    /** One axis per probe, twenty full-panel pixels. The caller schedules it at a low rate. */
    Probe nextProbe(int width,int height){
        if(width<4||height<4)throw new IllegalArgumentException("Invalid panel size");
        if(expected!=null){pending[expected.axis]=Double.NaN;stable[expected.axis]=0;}
        int axis=nextAxis;nextAxis=1-nextAxis;
        int[][] points=new int[20][2];
        for(int depth=0;depth<DEPTHS.length;depth++)for(int side=0;side<2;side++)for(int column=0;column<2;column++){
            double along=column==0?0.25:0.75,across=side==0?DEPTHS[depth]:1-DEPTHS[depth];
            int index=depth*4+side*2+column;
            points[index][0]=EdgeColorEngine.pixel(axis==HORIZONTAL?along:across,width);
            points[index][1]=EdgeColorEngine.pixel(axis==HORIZONTAL?across:along,height);
        }
        expected=new Probe(axis,points);return expected;
    }
    /** Unknown/dark observations retain the last region and reset unconfirmed candidate streaks. */
    EdgeColorEngine.Region observe(Probe probe,int[][] rgb){
        if(probe==null||probe!=expected)throw new IllegalArgumentException("Out-of-order bar probe");
        expected=null;
        try{EdgeColorEngine.validateColors(rgb,20);}catch(IllegalArgumentException invalid){pending[probe.axis]=Double.NaN;stable[probe.axis]=0;throw invalid;}
        double a=side(rgb,0),b=side(rgb,1),candidate=Double.NaN;
        // Both opposite edges must agree: a single dark picture area is not accepted as a bar.
        if(!Double.isNaN(a)&&!Double.isNaN(b)){
            if(a==0&&b==0)candidate=0;
            else if(a>0&&b>0&&Math.abs(a-b)<=0.04)candidate=Math.min(0.2,Math.max(a,b));
        }
        int axis=probe.axis;
        if(Double.isNaN(candidate)){pending[axis]=Double.NaN;stable[axis]=0;return region;}
        if(!Double.isNaN(pending[axis])&&Math.abs(candidate-pending[axis])<0.01)stable[axis]++;
        else{pending[axis]=candidate;stable[axis]=1;}
        if(stable[axis]>=STABLE){
            stable[axis]=STABLE;
            if(axis==HORIZONTAL){
                if(Math.abs(region.top-candidate)>0.001||Math.abs(region.bottom-(1-candidate))>0.001)
                    region=new EdgeColorEngine.Region(region.left,candidate,region.right,1-candidate);
            }else if(Math.abs(region.left-candidate)>0.001||Math.abs(region.right-(1-candidate))>0.001)
                region=new EdgeColorEngine.Region(candidate,region.top,1-candidate,region.bottom);
        }
        return region;
    }
    private double side(int[][] rgb,int side){
        int barMin=255,barMax=0;
        for(int depth=0;depth<DEPTHS.length;depth++){
            int first=depth*4+side*2;
            boolean black=maximum(rgb[first])<=BLACK&&maximum(rgb[first+1])<=BLACK;
            if(black){
                for(int i=first;i<first+2;i++)for(int value:rgb[i]){barMin=Math.min(barMin,value);barMax=Math.max(barMax,value);}
                if(barMax-barMin>6)return Double.NaN;
                continue;
            }
            // A dim first interior probe cannot establish that black pixels are letterboxing.
            if(Math.max(maximum(rgb[first]),maximum(rgb[first+1]))<BRIGHT)return Double.NaN;
            if(depth==0)return 0;
            // Midpoint plus the engine's inset places edge sampling beyond the observed transition.
            return (DEPTHS[depth-1]+DEPTHS[depth])*0.5;
        }
        // Entire probe path is black. Never grow crop on a fade, blackout or dark scene.
        return Double.NaN;
    }
    private static int maximum(int[] rgb){return Math.max(rgb[0],Math.max(rgb[1],rgb[2]));}
}
