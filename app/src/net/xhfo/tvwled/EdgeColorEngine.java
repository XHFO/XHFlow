package net.xhfo.tvwled;

/** Pure color mapping and output smoothing. No Android, I/O or capture permissions. */
final class EdgeColorEngine {
    static final int POINTS_PER_EDGE=5, POINT_COUNT=20, MAX_POINT_COUNT=40;
    static final String[] EDGES={"top","right","bottom","left"};
    private static final double INSET=0.045;
    static final class Region {
        static final Region FULL=new Region(0,0,1,1);
        final double left,top,right,bottom;
        Region(double left,double top,double right,double bottom){
            if(!finite(left)||!finite(top)||!finite(right)||!finite(bottom)||left<0||top<0||right>1||bottom>1||right-left<0.5||bottom-top<0.5)
                throw new IllegalArgumentException("Invalid active region");
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
    }
    static final class Point {
        final String edge;
        final double position,x,y;
        Point(String edge,double position,double x,double y){this.edge=edge;this.position=position;this.x=x;this.y=y;}
    }
    private final int ledCount,pointsPerEdge;
    private final int[] indices, edgeIndices;
    private final double[] positions, previous;
    private final boolean closed;
    private long lastOutputMs;
    private boolean hasOutputTime;

    EdgeColorEngine(int ledCount,int[] physicalIndices,String[] edgeNames,double[] edgePositions,boolean closed){
        this(ledCount,physicalIndices,edgeNames,edgePositions,closed,POINTS_PER_EDGE);
    }
    EdgeColorEngine(int ledCount,int[] physicalIndices,String[] edgeNames,double[] edgePositions,boolean closed,int pointsPerEdge){
        validatePointsPerEdge(pointsPerEdge);this.pointsPerEdge=pointsPerEdge;
        if(ledCount<1||ledCount>480||physicalIndices==null||edgeNames==null||edgePositions==null||physicalIndices.length<1||physicalIndices.length>ledCount||physicalIndices.length!=edgeNames.length||physicalIndices.length!=edgePositions.length)
            throw new IllegalArgumentException("Invalid LED mapping");
        this.ledCount=ledCount;this.indices=physicalIndices.clone();this.positions=edgePositions.clone();this.closed=closed;
        this.edgeIndices=new int[indices.length];this.previous=new double[ledCount*3];
        boolean[] used=new boolean[ledCount];
        for(int i=0;i<indices.length;i++){
            if(indices[i]<0||indices[i]>=ledCount||used[indices[i]]||!finite(positions[i])||positions[i]<0||positions[i]>1)
                throw new IllegalArgumentException("Invalid LED position/index");
            used[indices[i]]=true;edgeIndices[i]=edgeIndex(edgeNames[i]);
        }
    }
    static Point[] plan(Region region){return plan(region,INSET);}
    static Point[] plan(Region region,double inset){return plan(region,inset,POINTS_PER_EDGE);}
    static Point[] plan(Region region,double inset,int pointsPerEdge){
        validatePointsPerEdge(pointsPerEdge);
        if(region==null)throw new IllegalArgumentException("Active region required");
        if(!finite(inset)||inset<0||inset>0.2)throw new IllegalArgumentException("Sampling inset outside 0..0.2");
        Point[] out=new Point[pointsPerEdge*4];double w=region.right-region.left,h=region.bottom-region.top;
        for(int edge=0;edge<4;edge++)for(int i=0;i<pointsPerEdge;i++){
            double u=(i+0.5)/pointsPerEdge,x,y;
            switch(edge){
                case 0:x=region.left+u*w;y=region.top+inset*h;break;
                case 1:x=region.right-inset*w;y=region.top+u*h;break;
                case 2:x=region.right-u*w;y=region.bottom-inset*h;break;
                default:x=region.left+inset*w;y=region.bottom-u*h;
            }
            out[edge*pointsPerEdge+i]=new Point(EDGES[edge],u,x,y);
        }
        return out;
    }
    static int[][] coordinates(Point[] points,int width,int height){
        if(points==null||points.length<1||points.length>MAX_POINT_COUNT||width<4||height<4)throw new IllegalArgumentException("Invalid sampling plan");
        int[][] out=new int[points.length][2];
        for(int i=0;i<points.length;i++){
            if(points[i]==null||!finite(points[i].x)||!finite(points[i].y))throw new IllegalArgumentException("Invalid sampling point");
            out[i][0]=pixel(points[i].x,width);out[i][1]=pixel(points[i].y,height);
        }
        return out;
    }
    /** Input colors stay untouched; mapping can run independently of the output sender. */
    int[] map(int[][] colors,double gain,boolean softening,double strength){
        validateColors(colors,pointsPerEdge*4);unit(gain);unit(strength);
        double[] walk=new double[indices.length*3];
        for(int i=0;i<indices.length;i++){
            double at=Math.max(0,Math.min(pointsPerEdge-1,positions[i]*pointsPerEdge-0.5));
            int low=(int)Math.floor(at),high=Math.min(pointsPerEdge-1,low+1),base=edgeIndices[i]*pointsPerEdge;
            double mix=at-low;
            for(int c=0;c<3;c++)walk[i*3+c]=(colors[base+low][c]*(1-mix)+colors[base+high][c]*mix)*gain;
        }
        int[] out=new int[ledCount*3];
        double blur=softening?strength:0;
        for(int i=0;i<indices.length;i++){
            int before=i==0?(closed?indices.length-1:0):i-1;
            int after=i==indices.length-1?(closed?0:i):i+1;
            for(int c=0;c<3;c++){
                double own=walk[i*3+c],near=0.25*walk[before*3+c]+0.5*own+0.25*walk[after*3+c];
                out[indices[i]*3+c]=channel(own*(1-blur)+near*blur);
            }
        }
        return out;
    }
    /** Called by one sender; exponential time smoothing remains stable across frame rates. */
    synchronized int[] smooth(int[] goal,long nowMs,boolean enabled,double strength){
        if(goal==null||goal.length!=ledCount*3)throw new IllegalArgumentException("Invalid output length");
        unit(strength);for(int value:goal)if(value<0||value>255)throw new IllegalArgumentException("Invalid output RGB");
        double dt=hasOutputTime?Math.max(1,Math.min(1000,nowMs-lastOutputMs)):33;
        double alpha=!enabled||strength==0?1:1-Math.exp(-dt/(strength*600));
        int[] out=new int[goal.length];
        for(int i=0;i<goal.length;i++){previous[i]+=(goal[i]-previous[i])*alpha;out[i]=channel(previous[i]);}
        lastOutputMs=nowMs;hasOutputTime=true;return out;
    }
    static void validateColors(int[][] colors,int count){
        if(colors==null||colors.length!=count)throw new IllegalArgumentException("Sampling count mismatch");
        for(int[] rgb:colors){if(rgb==null||rgb.length!=3)throw new IllegalArgumentException("Sampling RGB format");for(int v:rgb)if(v<0||v>255)throw new IllegalArgumentException("Sampling RGB range");}
    }
    static int pixel(double normalized,int size){return Math.max(0,Math.min(size-1,(int)Math.round(normalized*(size-1))));}
    private static void validatePointsPerEdge(int count){
        if(count!=5&&count!=10)throw new IllegalArgumentException("Sampling quality requires 5 or 10 points per edge");
    }
    private static int edgeIndex(String edge){for(int i=0;i<EDGES.length;i++)if(EDGES[i].equals(edge))return i;throw new IllegalArgumentException("Unknown screen edge");}
    private static int channel(double value){return Math.max(0,Math.min(255,(int)Math.round(value)));}
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private static void unit(double value){if(!finite(value)||value<0||value>1)throw new IllegalArgumentException("Value outside 0..1");}
}
