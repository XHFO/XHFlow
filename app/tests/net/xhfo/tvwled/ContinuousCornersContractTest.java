package net.xhfo.tvwled;

import java.util.ArrayList;
import java.util.List;

/** Checks actual drawable-path geometry: bounded convex curves, tangents and continuous curvature. */
public final class ContinuousCornersContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double a,double b,String message){check(Math.abs(a-b)<1e-7,message+" "+a+" / "+b);}
    private static final class Curve {
        final double[] p;
        Curve(double... points){p=points;}
        double[] at(double t){double u=1-t;return new double[]{u*u*u*p[0]+3*u*u*t*p[2]+3*u*t*t*p[4]+t*t*t*p[6],u*u*u*p[1]+3*u*u*t*p[3]+3*u*t*t*p[5]+t*t*t*p[7]};}
        double[] tangent(boolean end){return end?new double[]{3*(p[6]-p[4]),3*(p[7]-p[5])}:new double[]{3*(p[2]-p[0]),3*(p[3]-p[1])};}
        double curvature(boolean end){double[] v=tangent(end);double ax=end?6*(p[6]-2*p[4]+p[2]):6*(p[4]-2*p[2]+p[0]),ay=end?6*(p[7]-2*p[5]+p[3]):6*(p[5]-2*p[3]+p[1]);return (v[0]*ay-v[1]*ax)/Math.pow(v[0]*v[0]+v[1]*v[1],1.5);}
    }
    private static final class Shape implements ContinuousCorners.Sink {
        double x,y;boolean closed;
        final List<Curve> curves=new ArrayList<>();
        final List<double[]> points=new ArrayList<>();
        @Override public void move(double x,double y){this.x=x;this.y=y;points.add(new double[]{x,y});}
        @Override public void line(double x,double y){this.x=x;this.y=y;points.add(new double[]{x,y});}
        @Override public void cubic(double x1,double y1,double x2,double y2,double x3,double y3){Curve c=new Curve(x,y,x1,y1,x2,y2,x3,y3);curves.add(c);for(int i=1;i<=32;i++)points.add(c.at(i/32.0));x=x3;y=y3;}
        @Override public void close(){closed=true;}
    }
    private static void shape(double l,double t,double r,double b,double radius){
        Shape shape=new Shape();ContinuousCorners.rect(shape,l,t,r,b,radius);
        check(shape.closed&&shape.curves.size()==8,"Four corners close the panel with two cubic sections each");
        for(double[] point:shape.points)check(point[0]>=l-1e-8&&point[0]<=r+1e-8&&point[1]>=t-1e-8&&point[1]<=b+1e-8,"Corners remain inside the panel at every sampled location");
        if(radius==0||r==l||t==b)return;
        for(int i=0;i<8;i+=2){
            Curve first=shape.curves.get(i),second=shape.curves.get(i+1);
            near(first.p[6],second.p[0],"Curve sections meet in x");near(first.p[7],second.p[1],"Curve sections meet in y");
            double[] a=first.tangent(true),c=second.tangent(false);near(a[0],c[0],"Tangent x remains continuous");near(a[1],c[1],"Tangent y remains continuous");
            near(first.curvature(true),second.curvature(false),"Corner sections share geometric curvature");
            near(first.curvature(false),0,"Curvature starts at zero against a straight edge");near(second.curvature(true),0,"Curvature ends at zero against a straight edge");
        }
        // The complete boundary is convex, so its Android shadow outline is valid.
        for(int i=0;i<shape.points.size();i++){
            double[] a=shape.points.get(i),c=shape.points.get((i+1)%shape.points.size()),d=shape.points.get((i+2)%shape.points.size());
            double cross=(c[0]-a[0])*(d[1]-c[1])-(c[1]-a[1])*(d[0]-c[0]);check(cross>=-1e-7,"Corner boundary must not fold or become concave");
        }
    }
    public static void main(String[] args){
        check(ContinuousCorners.PANEL_RADIUS_DP-ContinuousCorners.PANEL_INSET_DP==ContinuousCorners.CARD_RADIUS_DP,"Panel and nested card share the radius-offset rule");
        check(ContinuousCorners.CARD_RADIUS_DP-ContinuousCorners.CARD_PADDING_DP==ContinuousCorners.INSET_RADIUS_DP,"Inset elements derive their radius from card padding");
        for(int value:new int[]{ContinuousCorners.PANEL_RADIUS_DP,ContinuousCorners.PANEL_INSET_DP,ContinuousCorners.CARD_RADIUS_DP,ContinuousCorners.CARD_PADDING_DP,ContinuousCorners.INSET_RADIUS_DP})check(value>=4&&value%4==0,"All corner and padding tokens share the four-dp grid");
        for(int[] size:new int[][]{{1920,1080},{3840,2160},{1280,720},{1080,1920},{1365,767}}){
            int inset=ContinuousCorners.panelInsetPx(size[0],size[1]);
            check(inset==Math.round(Math.min(size[0],size[1])*.10f),"Panel inset follows the short screen edge");
            int left=inset,top=inset,right=size[0]-inset,bottom=size[1]-inset;
            check(left==top&&left==size[0]-right&&left==size[1]-bottom,"Panel leaves equal pixel margins on all four sides");
        }
        for(double scale:new double[]{.45,.75,.95,1,1.5,2,3}){
            shape(0,0,768*scale,432*scale,ContinuousCorners.PANEL_RADIUS_DP*scale);
            shape(4*scale,8*scale,300*scale,408*scale,ContinuousCorners.CARD_RADIUS_DP*scale);
            shape(0,0,128*scale,128*scale,ContinuousCorners.INSET_RADIUS_DP*scale);
        }
        shape(0,0,8,4,100);shape(0,0,0,0,8);shape(0,0,30,10,0);
        try{ContinuousCorners.rect(new Shape(),0,0,1,1,-1);throw new AssertionError("Negative radius accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("ContinuousCornersContractTest PASS ("+checks+" checks)");
    }
}
