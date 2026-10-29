package net.xhfo.tvwled;

/** Shared 4dp geometry tokens and a G2-continuous approximation of superellipse(1.25). */
final class ContinuousCorners {
    static final int PANEL_RADIUS_DP=32,PANEL_INSET_DP=12;
    static final int CARD_RADIUS_DP=PANEL_RADIUS_DP-PANEL_INSET_DP,CARD_PADDING_DP=12;
    static final int INSET_RADIUS_DP=CARD_RADIUS_DP-CARD_PADDING_DP,GRID_DP=4;
    static int panelInsetPx(int width,int height){return Math.round(Math.min(width,height)*.10f);}
    private static final double MID=Math.pow(.5,1/Math.pow(2,1.25)),FIRST=.25,SECOND=2*MID-1;
    interface Sink {
        void move(double x,double y);
        void line(double x,double y);
        void cubic(double x1,double y1,double x2,double y2,double x3,double y3);
        void close();
    }
    /** Two cubic segments per corner; curvature reaches zero where each straight edge begins. */
    static void rect(Sink sink,double left,double top,double right,double bottom,double radius){
        if(sink==null||!finite(left)||!finite(top)||!finite(right)||!finite(bottom)||!finite(radius)||right<left||bottom<top||radius<0)
            throw new IllegalArgumentException("Invalid continuous corner bounds");
        double r=Math.min(radius,Math.min(right-left,bottom-top)/2);
        sink.move(left+r,top);sink.line(right-r,top);
        corner(sink,right-r,top,r,0,0,r);
        sink.line(right,bottom-r);corner(sink,right,bottom-r,0,r,-r,0);
        sink.line(left+r,bottom);corner(sink,left+r,bottom,-r,0,0,-r);
        sink.line(left,top+r);corner(sink,left,top+r,0,-r,r,0);sink.close();
    }
    private static void corner(Sink sink,double x,double y,double ax,double ay,double bx,double by){
        sink.cubic(x+FIRST*ax,y+FIRST*ay,x+SECOND*ax,y+SECOND*ay,x+MID*ax+(1-MID)*bx,y+MID*ay+(1-MID)*by);
        sink.cubic(x+ax+(1-SECOND)*bx,y+ay+(1-SECOND)*by,x+ax+(1-FIRST)*bx,y+ay+(1-FIRST)*by,x+ax+bx,y+ay+by);
    }
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
}
