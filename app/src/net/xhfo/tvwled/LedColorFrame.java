package net.xhfo.tvwled;

/** One immutable output goal and its source-derived black limits, published together. */
final class LedColorFrame {
    private static final double BLACK=24, FULL_COLOR=40;
    private final int[] goal;
    private final double[] limits;

    LedColorFrame(int[] source,int[] corrected,double brightness){
        if(source==null||corrected==null||source.length!=corrected.length||source.length%3!=0
                ||!Double.isFinite(brightness)||brightness<0||brightness>1)
            throw new IllegalArgumentException("Invalid protected output frame");
        goal=corrected.clone();limits=new double[source.length/3];
        for(int i=0;i<limits.length;i++){
            int base=i*3,peak=0;
            for(int c=0;c<3;c++){
                if(source[base+c]<0||source[base+c]>255||goal[base+c]<0||goal[base+c]>255)
                    throw new IllegalArgumentException("Invalid protected output RGB");
                peak=Math.max(peak,source[base+c]);
            }
            // Use the largest channel before blur/correction: saturated blue is not black.
            // Fade out the protection above black instead of introducing a threshold flash.
            if(peak<=BLACK)limits[i]=peak*(4/BLACK)*brightness;
            else if(peak<FULL_COLOR){
                double t=(peak-BLACK)/(FULL_COLOR-BLACK);
                limits[i]=(4+251*t*t*(3-2*t))*brightness;
            }else limits[i]=255;
            double maximum=Math.max(goal[base],Math.max(goal[base+1],goal[base+2]));
            if(maximum>limits[i])for(int c=0;c<3;c++)
                goal[base+c]=(int)Math.round(goal[base+c]*limits[i]/maximum);
        }
    }

    int[] goal(){return goal.clone();}

    /** Limit the sender's history too, so an old bright frame cannot return after black. */
    void limit(double[] rgb){
        if(rgb.length!=goal.length)throw new IllegalArgumentException("Protected output length mismatch");
        for(int i=0;i<limits.length;i++){
            int base=i*3;double peak=Math.max(rgb[base],Math.max(rgb[base+1],rgb[base+2]));
            if(peak>limits[i])for(int c=0;c<3;c++)rgb[base+c]*=limits[i]/peak;
        }
    }
}
