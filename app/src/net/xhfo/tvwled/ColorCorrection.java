package net.xhfo.tvwled;

/** Pure per-LED color correction. Apply before temporal smoothing, with brightness last. */
final class ColorCorrection {
    private final int ledCount;
    private final int[] assignedIndices;
    private final double saturation,gain,offset,gamma,redGain,greenGain,blueGain;
    private final boolean identity;

    ColorCorrection(int ledCount,int[] assignedIndices,double saturation,double gain,double offset,double gamma,
                    double redGain,double greenGain,double blueGain){
        if(ledCount<1||ledCount>480||assignedIndices==null)throw new IllegalArgumentException("Invalid LED correction mapping");
        range(saturation,0,2);range(gain,0,2);range(offset,-64,64);range(gamma,0.5,3);
        range(redGain,0,2);range(greenGain,0,2);range(blueGain,0,2);
        this.ledCount=ledCount;this.assignedIndices=assignedIndices.clone();
        boolean[] used=new boolean[ledCount];
        for(int index:this.assignedIndices){
            if(index<0||index>=ledCount||used[index])throw new IllegalArgumentException("Invalid or duplicate assigned LED");
            used[index]=true;
        }
        this.saturation=saturation;this.gain=gain;this.offset=offset;this.gamma=gamma;
        this.redGain=redGain;this.greenGain=greenGain;this.blueGain=blueGain;
        identity=saturation==1&&gain==1&&offset==0&&gamma==1&&redGain==1&&greenGain==1&&blueGain==1;
    }

    /** A caller can retain its original mapping/brightness path when every correction is neutral. */
    boolean isIdentity(){return identity;}

    int[] apply(int[] rgb,double brightness){
        range(brightness,0,1);
        if(rgb==null||rgb.length!=ledCount*3)throw new IllegalArgumentException("Invalid RGB correction input length");
        for(int value:rgb)if(value<0||value>255)throw new IllegalArgumentException("Invalid RGB correction channel");
        int[] out=new int[rgb.length];
        if(brightness==0)return out;
        for(int index:assignedIndices){
            int base=index*3;
            if(identity){
                for(int channel=0;channel<3;channel++)out[base+channel]=quantize(rgb[base+channel]*brightness);
                continue;
            }
            double red=rgb[base],green=rgb[base+1],blue=rgb[base+2];
            if(saturation!=1){
                double luma=0.2126*red+0.7152*green+0.0722*blue;
                red=luma+(red-luma)*saturation;
                green=luma+(green-luma)*saturation;
                blue=luma+(blue-luma)*saturation;
            }
            out[base]=correct(red,redGain,brightness);
            out[base+1]=correct(green,greenGain,brightness);
            out[base+2]=correct(blue,blueGain,brightness);
        }
        // Only assigned indices are visited: positive offset cannot light unused strip pixels.
        return out;
    }
    private int correct(double value,double channelGain,double brightness){
        double clamped=Math.max(0,Math.min(255,value*gain*channelGain+offset));
        double corrected=gamma==1?clamped:255*Math.pow(clamped/255,1/gamma);
        return quantize(corrected*brightness);
    }
    private static int quantize(double value){return Math.max(0,Math.min(255,(int)Math.round(value)));}
    private static void range(double value,double min,double max){
        if(Double.isNaN(value)||Double.isInfinite(value)||value<min||value>max)
            throw new IllegalArgumentException("Color correction value out of range");
    }
}
