package net.xhfo.tvwled;

/** Stable, point-based bar detection. Crop bounds always use the observed picture side. */
final class BlackBarDetector {
    static final int HORIZONTAL=0,VERTICAL=1;
    private static final int COLUMNS=3,DEPTH_COUNT=3,POINT_COUNT=18;
    private static final double MAX_DEPTH=.24;
    private static final int BLACK=24,NEUTRAL=6,BRIGHT=36,CONTRAST=20,STABLE=3;
    static final class Probe {
        final int axis;
        final int[][] coordinates;
        final double[][] depths;
        final double pixelSize;
        private Probe(int axis,int[][] coordinates,double[][] depths,double pixelSize){
            this.axis=axis;this.coordinates=coordinates;this.depths=depths;this.pixelSize=pixelSize;
        }
    }
    private static final class Evidence {
        final double low,high;
        Evidence(double low,double high){this.low=low;this.high=high;}
    }
    private final double[][] lower=new double[2][2],upper={{MAX_DEPTH,MAX_DEPTH},{MAX_DEPTH,MAX_DEPTH}};
    private final double[] pendingLow={Double.NaN,Double.NaN},pendingHigh={Double.NaN,Double.NaN};
    private final int[] stable={0,0};
    private int nextAxis;
    private Probe expected;
    private EdgeColorEngine.Region region=EdgeColorEngine.Region.FULL;
    EdgeColorEngine.Region region(){return region;}
    void reset(){
        region=EdgeColorEngine.Region.FULL;nextAxis=0;expected=null;
        for(int axis=0;axis<2;axis++){clearPending(axis);for(int side=0;side<2;side++)resetBracket(axis,side);}
    }
    private void clearPending(int axis){pendingLow[axis]=pendingHigh[axis]=Double.NaN;stable[axis]=0;}
    private void resetBracket(int axis,int side){lower[axis][side]=0;upper[axis][side]=MAX_DEPTH;}
    /** Eighteen points per transaction: three depths, opposite edges, three-point consensus. */
    Probe nextProbe(int width,int height){
        if(width<4||height<4)throw new IllegalArgumentException("Invalid panel size");
        if(expected!=null){clearPending(expected.axis);for(int side=0;side<2;side++)resetBracket(expected.axis,side);}
        int axis=nextAxis;nextAxis=1-nextAxis;
        double pixelSize=1.0/((axis==HORIZONTAL?height:width)-1);
        double[][] depths=new double[2][DEPTH_COUNT];int[][] points=new int[POINT_COUNT][2];
        for(int side=0;side<2;side++){
            depths[side][0]=lower[axis][side];depths[side][2]=upper[axis][side];
            depths[side][1]=(depths[side][0]+depths[side][2])*.5;
            for(int depth=0;depth<DEPTH_COUNT;depth++)for(int column=0;column<COLUMNS;column++){
                double along=.2+.3*column,across=side==0?depths[side][depth]:1-depths[side][depth];
                int index=depth*6+side*3+column;
                points[index][0]=EdgeColorEngine.pixel(axis==HORIZONTAL?along:across,width);
                points[index][1]=EdgeColorEngine.pixel(axis==HORIZONTAL?across:along,height);
            }
        }
        expected=new Probe(axis,points,depths,pixelSize);return expected;
    }
    /** Unknown scenes hold the last accepted region; contradictory evidence starts a new streak. */
    EdgeColorEngine.Region observe(Probe probe,int[][] rgb){
        if(probe==null||probe!=expected)throw new IllegalArgumentException("Out-of-order bar probe");
        expected=null;
        try{EdgeColorEngine.validateColors(rgb,POINT_COUNT);}catch(IllegalArgumentException invalid){
            clearPending(probe.axis);for(int side=0;side<2;side++)resetBracket(probe.axis,side);throw invalid;
        }
        Evidence a=side(probe,rgb,0),b=side(probe,rgb,1);int axis=probe.axis;
        if(a==null||b==null||(a.high==0)!=(b.high==0)
                ||Math.max(a.low,b.low)>Math.min(a.high,b.high)+.04){clearPending(axis);return region;}
        double low=Math.max(a.low,b.low),high=Math.max(a.high,b.high);
        boolean compatible=!Double.isNaN(pendingHigh[axis])&&((high==0)==(pendingHigh[axis]==0))
            &&low<=pendingHigh[axis]+2*probe.pixelSize&&high>=pendingLow[axis]-2*probe.pixelSize;
        if(compatible){
            pendingLow[axis]=Math.max(pendingLow[axis],low);pendingHigh[axis]=Math.min(pendingHigh[axis],high);stable[axis]++;
        }else{pendingLow[axis]=low;pendingHigh[axis]=high;stable[axis]=1;}
        if(stable[axis]>=STABLE){
            stable[axis]=STABLE;
            // Never interpolate into the uncertain black-to-picture interval. A two-pixel pad
            // also avoids rounding back onto a bar when the user chooses zero sampling inset.
            double candidate=high==0?0:Math.min(.245,pendingHigh[axis]+2*probe.pixelSize);
            if(axis==HORIZONTAL){
                if(Math.abs(region.top-candidate)>probe.pixelSize||Math.abs(region.bottom-(1-candidate))>probe.pixelSize)
                    region=new EdgeColorEngine.Region(region.left,candidate,region.right,1-candidate);
            }else if(Math.abs(region.left-candidate)>probe.pixelSize||Math.abs(region.right-(1-candidate))>probe.pixelSize)
                region=new EdgeColorEngine.Region(candidate,region.top,1-candidate,region.bottom);
        }
        return region;
    }
    private Evidence side(Probe probe,int[][] rgb,int side){
        int barMin=255,barMax=0;double lastBlack=probe.depths[side][0];
        for(int depth=0;depth<DEPTH_COUNT;depth++){
            int first=depth*6+side*3,blackCount=0,contentCount=0,brightest=0;
            for(int column=0;column<COLUMNS;column++){
                int[] color=rgb[first+column];int max=maximum(color),min=minimum(color);brightest=Math.max(brightest,max);
                if(max<=BLACK&&max-min<=NEUTRAL){blackCount++;barMin=Math.min(barMin,min);barMax=Math.max(barMax,max);}
                else contentCount++;
            }
            if(blackCount>=2){
                if(barMax-barMin>NEUTRAL){resetBracket(probe.axis,side);return null;}
                lastBlack=probe.depths[side][depth];continue;
            }
            if(contentCount<2||brightest<BRIGHT||(depth>0&&brightest-barMax<CONTRAST)){
                resetBracket(probe.axis,side);return null;
            }
            double picture=probe.depths[side][depth];
            if(depth==0){
                resetBracket(probe.axis,side);
                // A previous inner bracket turning bright means the picture boundary moved.
                // Recheck the physical edge before declaring that the bars disappeared.
                return picture==0?new Evidence(0,0):null;
            }
            lower[probe.axis][side]=lastBlack;upper[probe.axis][side]=picture;
            return new Evidence(lastBlack,picture);
        }
        // Entire search path is dark: do not expand a crop on a fade or blackout.
        resetBracket(probe.axis,side);return null;
    }
    private static int maximum(int[] rgb){return Math.max(rgb[0],Math.max(rgb[1],rgb[2]));}
    private static int minimum(int[] rgb){return Math.min(rgb[0],Math.min(rgb[1],rgb[2]));}
}
