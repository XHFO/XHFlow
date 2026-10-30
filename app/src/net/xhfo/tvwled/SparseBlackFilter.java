package net.xhfo.tvwled;

/** Spatial replacement for sparse dark spots; never invents a color for a black scene. */
final class SparseBlackFilter {
    static final class Result {
        final int[] colors;
        final double blackRatio;
        final int filledLeds;
        Result(int[] colors,double blackRatio,int filledLeds){this.colors=colors;this.blackRatio=blackRatio;this.filledLeds=filledLeds;}
    }

    static Result apply(int[] source,int[] indices,boolean closed,boolean enabled,double threshold){
        if(!Double.isFinite(threshold)||threshold<.01||threshold>.5)
            throw new IllegalArgumentException("Black filter threshold outside 0.01..0.5");
        int count=indices.length;double[] dark=new double[count];double sum=0;
        for(int i=0;i<count;i++){
            int peak=peak(source,indices[i]);
            dark[i]=peak<=24?1:peak>=40?0:1-ease((peak-24)/16.0);
            sum+=dark[i];
        }
        double ratio=sum/count;
        if(!enabled||ratio==0||ratio>=threshold)return new Result(source,ratio,0);
        // Ease out before the configured maximum; an exact threshold crossing cannot flash.
        double coverage=1-ease((ratio-threshold*.75)/(threshold*.25));
        int[] out=source.clone();int filled=0;
        for(int i=0;i<count;i++){
            if(dark[i]==0)continue;
            int before=neighbor(dark,i,-1,closed),after=neighbor(dark,i,1,closed);
            if(before<0&&after<0)continue;
            if(before<0)before=after;
            if(after<0)after=before;
            int back=i-before,forward=after-i;
            if(closed){if(back<0)back+=count;if(forward<0)forward+=count;}
            double mix=before==after?0:(double)back/(back+forward);
            double[] color=new double[3];double maximum=0;
            for(int c=0;c<3;c++){
                color[c]=source[indices[before]*3+c]*(1-mix)+source[indices[after]*3+c]*mix;
                maximum=Math.max(maximum,color[c]);
            }
            // Mixing two dark saturated colors must not produce another protected black.
            double donorPeak=peak(source,indices[before])*(1-mix)+peak(source,indices[after])*mix;
            double weight=dark[i]*coverage;int base=indices[i]*3;boolean changed=false;
            for(int c=0;c<3;c++){
                double replacement=maximum==0?0:color[c]*donorPeak/maximum;
                out[base+c]=(int)Math.round(source[base+c]*(1-weight)+replacement*weight);
                changed|=out[base+c]!=source[base+c];
            }
            if(changed)filled++;
        }
        return new Result(filled==0?source:out,ratio,filled);
    }
    private static int neighbor(double[] dark,int at,int step,boolean closed){
        for(int distance=1;distance<dark.length;distance++){
            int next=at+step*distance;
            if(closed)next=(next%dark.length+dark.length)%dark.length;
            else if(next<0||next>=dark.length)break;
            if(dark[next]==0)return next;
        }
        return -1;
    }
    private static int peak(int[] rgb,int index){int base=index*3;return Math.max(rgb[base],Math.max(rgb[base+1],rgb[base+2]));}
    private static double ease(double value){double t=Math.max(0,Math.min(1,value));return t*t*(3-2*t);}
}
